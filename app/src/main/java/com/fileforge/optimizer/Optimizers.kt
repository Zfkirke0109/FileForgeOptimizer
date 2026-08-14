package com.fileforge.optimizer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Bounded compatibility path for formats whose Kotlin optimizers still require complete byte arrays. */
class ByteArrayOptimizerAdapter(
    private val documentGateway: DocumentGateway,
    private val commitContext: CommitContext? = null,
    private val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES
) {
    init {
        require(maxInputBytes >= 1L && maxInputBytes <= Int.MAX_VALUE.toLong()) {
            "Byte-array limit must fit a JVM array"
        }
    }

    fun process(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        require(kind != FileKind.ZIP_LIKE && kind != FileKind.APK) { "ZIP-family input must use strict streaming" }
        return try {
            cancellation.throwIfCancelled()
            val original = readBounded(node, cancellation)
            val settings = OptimizerSettings(runIntent.mode, runIntent.apkLabMode, runIntent.textMinify)
            val result = Optimizers.optimize(node.name, kind, original, settings)
                ?: return FileOutcome.Skipped(relativePath, SkipReason.NO_CHANGE)
            if (result.bytes.size >= original.size) return FileOutcome.Skipped(relativePath, SkipReason.NO_GAIN)
            if (FileTypeDetector.detect(node.name, result.bytes) != kind || !Optimizers.verify(kind, result.bytes, settings)) {
                return FileOutcome.Skipped(relativePath, SkipReason.VERIFICATION_FAILED)
            }

            val oldBytes = original.size.toLong()
            val newBytes = result.bytes.size.toLong()
            if (runIntent.dryRun) {
                FileOutcome.WouldOptimize(relativePath, oldBytes, newBytes, TOOL_ID, result.note)
            } else {
                val context = commitContext
                    ?: return FileOutcome.Failed(relativePath, "Replacement is unavailable until the backup transaction is installed.")
                commit(node, relativePath, kind, original, result, context, cancellation)
            }
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (_: InputLimitExceededException) {
            FileOutcome.Skipped(relativePath, SkipReason.MEMORY_LIMIT, "Input exceeded the $maxInputBytes-byte in-memory limit while reading.")
        } catch (failure: Exception) {
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure)
        }
    }

    private fun readBounded(node: DocumentNode, cancellation: CancellationToken): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var total = 0L
        documentGateway.openRead(node).use { input ->
            while (true) {
                cancellation.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = try {
                    Math.addExact(total, read.toLong())
                } catch (overflow: ArithmeticException) {
                    throw InputLimitExceededException(overflow)
                }
                if (total > maxInputBytes) throw InputLimitExceededException()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun commit(
        originalNode: DocumentNode,
        relativePath: String,
        kind: FileKind,
        originalBytes: ByteArray,
        result: OptimizeResult,
        context: CommitContext,
        cancellation: CancellationToken
    ): FileOutcome {
        var backup: DocumentNode? = null
        var originalIntegrity: StreamIntegrity? = null
        var originalMutationStarted = false
        var transactionDurable = false
        return try {
            val expectedOriginal = originalBytes.inputStream().use { StreamIntegrityChecker.hash(it, cancellation) }
            val backupPath = backupPath(context.runId, relativePath)
            val backupNode = createBackup(context.selectedRoot, backupPath)
            backup = backupNode
            val backedUp = documentGateway.openRead(originalNode).use { source ->
                documentGateway.openWrite(backupNode).use { destination ->
                    StreamIntegrityChecker.copyAndHash(source, destination, cancellation)
                }
            }
            originalIntegrity = backedUp
            check(backedUp == expectedOriginal) { "Original changed before backup completed" }
            val verifiedBackup = documentGateway.openRead(backupNode).use { StreamIntegrityChecker.hash(it, cancellation) }
            check(verifiedBackup == expectedOriginal) { "Backup verification failed" }

            cancellation.throwIfCancelled()
            originalMutationStarted = true
            val writtenCandidate = result.bytes.inputStream().use { source ->
                documentGateway.openWrite(originalNode).use { destination ->
                    StreamIntegrityChecker.copyAndHash(source, destination, cancellation)
                }
            }
            val verifiedOriginal = documentGateway.openRead(originalNode).use { StreamIntegrityChecker.hash(it, cancellation) }
            check(verifiedOriginal == writtenCandidate) { "Optimized document verification failed" }

            context.undoEntrySink.appendAndFlush(
                UndoEntry(
                    relativePath = relativePath,
                    originalBytes = expectedOriginal.bytes,
                    optimizedBytes = writtenCandidate.bytes,
                    backupPath = backupPath,
                    originalSha256 = expectedOriginal.sha256,
                    optimizedSha256 = writtenCandidate.sha256,
                    note = result.note,
                    fileKind = kind,
                    toolId = TOOL_ID,
                    completedAt = context.completedAt()
                )
            )
            transactionDurable = true
            FileOutcome.Optimized(relativePath, expectedOriginal.bytes, writtenCandidate.bytes, TOOL_ID, result.note)
        } catch (cancelled: OptimizationCancelledException) {
            val rollbackBackup = backup
            val rollbackIntegrity = originalIntegrity
            if (originalMutationStarted && !transactionDurable && rollbackBackup != null && rollbackIntegrity != null) {
                val rollback = restoreBackup(originalNode, rollbackBackup, rollbackIntegrity)
                if (rollback is RollbackResult.Failed) cancelled.addSuppressed(rollback.cause)
            }
            throw cancelled
        } catch (failure: Exception) {
            val rollbackBackup = backup
            val rollbackIntegrity = originalIntegrity
            val rollback = if (originalMutationStarted && !transactionDurable && rollbackBackup != null && rollbackIntegrity != null) {
                restoreBackup(originalNode, rollbackBackup, rollbackIntegrity)
            } else {
                RollbackResult.NotNeeded
            }
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure, rollback)
        }
    }

    private fun backupPath(runId: String, relativePath: String): String {
        DocumentPathPolicy.requireSafeSegment(runId)
        DocumentPathPolicy.requireSafeRelative(relativePath)
        return "$BACKUP_PREFIX$runId/$relativePath"
    }

    private fun createBackup(root: DocumentNode, backupPath: String): DocumentNode {
        val segments = DocumentPathPolicy.requireSafeRelative(backupPath)
        var parent = root
        segments.dropLast(1).forEach { name ->
            val existing = documentGateway.resolve(parent, name)
            parent = when {
                existing == null -> documentGateway.createDirectoryExact(parent, name)
                existing.isDirectory -> existing
                else -> throw IOException("Backup path component is not a directory: $name")
            }
        }
        val name = segments.last()
        check(documentGateway.resolve(parent, name) == null) { "Backup already exists: $backupPath" }
        return documentGateway.createFileExact(parent, "application/octet-stream", name)
    }

    private fun restoreBackup(original: DocumentNode, backup: DocumentNode, expected: StreamIntegrity): RollbackResult = try {
        val restored = documentGateway.openRead(backup).use { source ->
            documentGateway.openWrite(original).use { destination ->
                StreamIntegrityChecker.copyAndHash(source, destination, NeverCancelled)
            }
        }
        val verified = documentGateway.openRead(original).use { StreamIntegrityChecker.hash(it, NeverCancelled) }
        check(restored == expected && verified == expected) { "Rollback verification failed" }
        RollbackResult.Restored
    } catch (failure: Exception) {
        RollbackResult.Failed(failure)
    }

    private class InputLimitExceededException(cause: Throwable? = null) : IOException("Input memory limit exceeded", cause)

    companion object {
        const val DEFAULT_MAX_INPUT_BYTES = 64L * 1024L * 1024L
        private const val TRANSFER_BUFFER_BYTES = 32 * 1024
        private const val TOOL_ID = "KotlinByteArrayOptimizer"
        private const val BACKUP_PREFIX = "FileForge_Backups_"
    }
}

object Optimizers {
    fun optimize(name: String, kind: FileKind, input: ByteArray, settings: OptimizerSettings): OptimizeResult? {
        return when (kind) {
            FileKind.ZIP_LIKE -> optimizeZipLike(input, settings)
            FileKind.APK -> if (settings.apkLabMode) optimizeZipLike(input, settings, apkWarning = true) else null
            FileKind.PNG -> optimizePng(input, settings)
            FileKind.JPEG -> optimizeJpeg(input, settings)
            FileKind.PDF -> optimizePdf(input)
            FileKind.JSON -> if (settings.textMinify) optimizeJson(input) else null
            FileKind.XML -> if (settings.textMinify) optimizeXmlLike(input, "xml") else null
            FileKind.SVG -> if (settings.textMinify) optimizeXmlLike(input, "svg") else null
            FileKind.TEXT -> if (settings.textMinify) optimizeTxt(input) else null
            FileKind.UNSUPPORTED -> null
        }
    }

    private fun optimizeZipLike(input: ByteArray, settings: OptimizerSettings, apkWarning: Boolean = false): OptimizeResult? {
        val output = ByteArrayOutputStream()
        val level = if (settings.mode == OptimizeMode.AGGRESSIVE) Deflater.BEST_COMPRESSION else 7
        var entries = 0

        ZipInputStream(ByteArrayInputStream(input)).use { zis ->
            ZipOutputStream(output).use { zos ->
                zos.setLevel(level)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val oldEntry = zis.nextEntry ?: break
                    val newEntry = ZipEntry(oldEntry.name).apply {
                        method = ZipEntry.DEFLATED
                        time = 0L
                        comment = null
                        extra = null
                    }
                    zos.putNextEntry(newEntry)
                    if (!oldEntry.isDirectory) {
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                    entries++
                }
            }
        }

        if (entries == 0) return null
        val note = if (apkWarning) {
            "APK Lab Mode recompressed ZIP container; original Android signature may be invalid."
        } else {
            "Recompressed ZIP container at deflate level $level."
        }
        return OptimizeResult(output.toByteArray(), note)
    }

    private data class PngChunk(val type: String, val data: ByteArray)

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )

    private fun optimizePng(input: ByteArray, settings: OptimizerSettings): OptimizeResult? {
        if (!input.copyOfRange(0, 8).contentEquals(pngSignature)) return null
        var pos = 8
        val chunks = mutableListOf<PngChunk>()
        val idatCombined = ByteArrayOutputStream()
        var firstIdatIndex: Int? = null
        var sawIend = false

        while (pos + 12 <= input.size) {
            val length = readInt(input, pos)
            if (length < 0) return null
            val typeStart = pos + 4
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            val crcEnd = dataEnd + 4
            if (dataEnd < dataStart || crcEnd > input.size) return null
            val type = input.copyOfRange(typeStart, typeStart + 4).toString(Charsets.US_ASCII)
            val data = input.copyOfRange(dataStart, dataEnd)

            if (type == "IDAT") {
                if (firstIdatIndex == null) firstIdatIndex = chunks.size
                idatCombined.write(data)
            } else {
                val shouldDrop = settings.mode == OptimizeMode.AGGRESSIVE &&
                    (type == "tEXt" || type == "zTXt" || type == "iTXt" || type == "tIME" || type == "eXIf")
                if (!shouldDrop) chunks.add(PngChunk(type, data))
            }
            pos = crcEnd
            if (type == "IEND") {
                sawIend = true
                break
            }
        }

        val insertAt = firstIdatIndex ?: return null
        if (!sawIend || idatCombined.size() == 0) return null

        val recompressedIdat = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            InflaterInputStream(ByteArrayInputStream(idatCombined.toByteArray())).use { inflater ->
                DeflaterOutputStream(recompressedIdat, deflater).use { deflaterStream ->
                    inflater.copyTo(deflaterStream)
                }
            }
        } finally {
            deflater.end()
        }

        val output = ByteArrayOutputStream()
        output.write(pngSignature)
        chunks.forEachIndexed { index, chunk ->
            if (index == insertAt) writePngChunk(output, "IDAT", recompressedIdat.toByteArray())
            writePngChunk(output, chunk.type, chunk.data)
        }
        if (insertAt >= chunks.size) writePngChunk(output, "IDAT", recompressedIdat.toByteArray())

        val note = if (settings.mode == OptimizeMode.AGGRESSIVE) {
            "Recompressed PNG IDAT and removed text/time metadata chunks."
        } else {
            "Recompressed PNG IDAT while preserving non-IDAT chunks."
        }
        return OptimizeResult(output.toByteArray(), note)
    }

    private fun optimizeJpeg(input: ByteArray, settings: OptimizerSettings): OptimizeResult? {
        if (input.size < 4 || input[0] != 0xff.toByte() || input[1] != 0xd8.toByte()) return null
        val output = ByteArrayOutputStream()
        output.write(0xff)
        output.write(0xd8)
        var pos = 2
        var removed = 0

        while (pos < input.size) {
            if (input[pos] != 0xff.toByte()) return null
            while (pos < input.size && input[pos] == 0xff.toByte()) pos++
            if (pos >= input.size) break
            val marker = input[pos].toInt() and 0xff
            pos++

            if (marker == 0xd9) {
                output.write(0xff)
                output.write(marker)
                return OptimizeResult(output.toByteArray(), "Removed $removed JPEG metadata/comment segments without recompressing image data.")
            }

            if (marker == 0x01 || marker in 0xd0..0xd7) {
                output.write(0xff)
                output.write(marker)
                continue
            }

            if (pos + 2 > input.size) return null
            val length = readU16(input, pos)
            if (length < 2 || pos + length > input.size) return null
            val segment = input.copyOfRange(pos, pos + length)

            val skip = when (marker) {
                0xfe -> true // COM comments
                0xe1 -> true // EXIF/XMP APP1
                0xed -> settings.mode == OptimizeMode.AGGRESSIVE // Photoshop/IPTC APP13
                else -> false
            }

            if (!skip) {
                output.write(0xff)
                output.write(marker)
                output.write(segment)
            } else {
                removed++
            }

            pos += length

            if (marker == 0xda) {
                // Start of Scan: after this point the entropy-coded image stream must be copied byte-for-byte.
                output.write(input, pos, input.size - pos)
                return OptimizeResult(output.toByteArray(), "Removed $removed JPEG metadata/comment segments without recompressing image data.")
            }
        }
        return null
    }

    private fun optimizePdf(input: ByteArray): OptimizeResult? {
        if (input.size < 10 || !input.copyOfRange(0, 5).toString(Charsets.ISO_8859_1).startsWith("%PDF-")) return null
        val haystack = input.toString(Charsets.ISO_8859_1)
        val eof = haystack.lastIndexOf("%%EOF")
        if (eof < 0) return null
        var end = eof + "%%EOF".length
        while (end < input.size) {
            val b = input[end]
            if (b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == ' '.code.toByte() || b == '\t'.code.toByte() || b == 0.toByte()) {
                end++
            } else {
                break
            }
        }
        if (end >= input.size) return null
        return OptimizeResult(input.copyOfRange(0, end), "Removed trailing bytes after final PDF EOF marker.")
    }

    private fun optimizeJson(input: ByteArray): OptimizeResult? {
        val text = input.toString(Charsets.UTF_8)
        val value = JSONTokener(text).nextValue()
        val compact = when (value) {
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value?.toString() ?: "null"
        }
        return OptimizeResult(compact.toByteArray(Charsets.UTF_8), "Minified JSON.")
    }

    private fun optimizeXmlLike(input: ByteArray, label: String): OptimizeResult? {
        val text = input.toString(Charsets.UTF_8)
        val compact = text
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex(">\\s+<"), "><")
            .trim()
        return OptimizeResult(compact.toByteArray(Charsets.UTF_8), "Minified ${label.uppercase(Locale.US)} markup.")
    }

    private fun optimizeTxt(input: ByteArray): OptimizeResult? {
        val text = input.toString(Charsets.UTF_8)
        val compact = text.lines().joinToString("\n") { it.trimEnd() }.trimEnd() + "\n"
        return OptimizeResult(compact.toByteArray(Charsets.UTF_8), "Trimmed trailing whitespace from TXT.")
    }

    fun verify(kind: FileKind, bytes: ByteArray, settings: OptimizerSettings): Boolean {
        return try {
            when (kind) {
                FileKind.ZIP_LIKE, FileKind.APK -> verifyZip(bytes)
                FileKind.PNG -> verifyPng(bytes)
                FileKind.JPEG -> verifyJpeg(bytes)
                FileKind.PDF -> bytes.size > 10 && bytes.copyOfRange(0, 5).toString(Charsets.ISO_8859_1).startsWith("%PDF-") &&
                    bytes.toString(Charsets.ISO_8859_1).contains("%%EOF")
                FileKind.JSON -> {
                    JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue(); true
                }
                FileKind.XML, FileKind.SVG -> {
                    if (!settings.textMinify) true else {
                        val factory = DocumentBuilderFactory.newInstance()
                        factory.isNamespaceAware = false
                        factory.isValidating = false
                        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
                        true
                    }
                }
                FileKind.TEXT -> true
                FileKind.UNSUPPORTED -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyZip(bytes: ByteArray): Boolean {
        var entries = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                zis.nextEntry ?: break
                while (zis.read(buffer) > 0) {
                    // Drain entry.
                }
                entries++
            }
        }
        return entries > 0
    }

    private fun verifyPng(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || !bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return false
        var pos = 8
        while (pos + 12 <= bytes.size) {
            val length = readInt(bytes, pos)
            if (length < 0) return false
            val typeStart = pos + 4
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            val crcEnd = dataEnd + 4
            if (dataEnd < dataStart || crcEnd > bytes.size) return false
            val type = bytes.copyOfRange(typeStart, typeStart + 4).toString(Charsets.US_ASCII)
            if (type == "IEND") return true
            pos = crcEnd
        }
        return false
    }

    private fun verifyJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return false
        var sawSos = false
        for (i in 2 until bytes.size - 1) {
            if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0xda.toByte()) {
                sawSos = true
                break
            }
        }
        return sawSos && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes[bytes.lastIndex] == 0xd9.toByte()
    }

    private fun readInt(bytes: ByteArray, pos: Int): Int {
        return ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readU16(bytes: ByteArray, pos: Int): Int {
        return ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff)
    }

    private fun writePngChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }
}
