package com.fileforge.optimizer

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipOptimizationSummary(
    val entries: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val note: String
)

data class ZipVerification(
    val entries: Int,
    val bytesRead: Long
)

class UnsafeArchivePathException(path: String) : ZipException("Unsafe archive entry path: $path")
class UnsupportedZipFeatureException(feature: String) : ZipException("$feature is not supported")
class ArchiveResourceLimitException(message: String) : ZipException(message)

object ArchivePathPolicy {
    fun requireSafe(path: String) {
        if (path.isEmpty() || path.indexOf('\u0000') >= 0 || isAbsolute(path)) {
            throw UnsafeArchivePathException(path)
        }

        path.replace('\\', '/').split('/').forEach { segment ->
            if (segment == "." || segment == "..") throw UnsafeArchivePathException(path)
        }
    }

    fun normalizedName(path: String): String = path.replace('\\', '/')

    private fun isAbsolute(path: String): Boolean =
        path.startsWith('/') || path.startsWith('\\') ||
            (path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '/' || path[2] == '\\'))
}

open class StreamingZipOptimizer(
    private val maxInflatedBytes: Long = DEFAULT_MAX_INFLATED_BYTES
) {
    init {
        require(maxInflatedBytes > 0) { "Inflated payload limit must be positive" }
    }

    fun optimize(
        input: InputStream,
        output: OutputStream,
        mode: OptimizeMode,
        cancellation: CancellationToken,
        onBytes: (Long) -> Unit
    ): ZipOptimizationSummary {
        val countedOutput = CountingOutputStream(output)
        val trackedInput = TailTrackingInputStream(input)
        val buffer = ByteArray(BUFFER_BYTES)
        var entries = 0
        var inputBytes = 0L
        val level = if (mode == OptimizeMode.AGGRESSIVE) Deflater.BEST_COMPRESSION else 7
        val names = HashSet<String>()
        val localEntries = mutableListOf<LocalEntryMetadata>()
        var nextLocalOffset = 0L

        ZipInputStream(NonClosingInputStream(trackedInput), ZIP_LEGACY_CHARSET).use { zipIn ->
            createZipOutputStream(NonClosingOutputStream(countedOutput)).use { zipOut ->
                zipOut.setLevel(level)
                while (true) {
                    cancellation.throwIfCancelled()
                    val source = zipIn.nextEntry ?: break
                    val localHeader = trackedInput.requireLocalHeader(nextLocalOffset, source)
                    ArchivePathPolicy.requireSafe(source.name)
                    if (!names.add(ArchivePathPolicy.normalizedName(source.name))) {
                        throw ZipException("Duplicate archive entry: ${source.name}")
                    }

                    val preserveEpubMimetype = entries == 0 &&
                        source.name == EPUB_MIMETYPE_ENTRY &&
                        source.method == ZipEntry.STORED
                    zipOut.putNextEntry(copyMetadata(source, preserveEpubMimetype))
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = zipIn.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            cancellation.throwIfCancelled()
                            val updatedInputBytes = addInflatedBytes(inputBytes, read)
                            zipOut.write(buffer, 0, read)
                            cancellation.throwIfCancelled()
                            inputBytes = updatedInputBytes
                            onBytes(read.toLong())
                        }
                    }
                    zipOut.closeEntry()
                    zipIn.closeEntry()
                    val localEntry = LocalEntryMetadata.from(source, localHeader)
                    nextLocalOffset = trackedInput.requireLocalEntryEnd(localEntry)
                    localEntries += localEntry
                    entries++
                }
                drainAndRequireCentralDirectory(
                    trackedInput,
                    buffer,
                    localEntries,
                    nextLocalOffset,
                    cancellation
                )
            }
        }

        if (entries == 0) throw ZipException("Archive contains no entries")
        return ZipOptimizationSummary(
            entries = entries,
            inputBytes = inputBytes,
            outputBytes = countedOutput.bytesWritten,
            note = "Recompressed ZIP container at deflate level $level. " +
                "Entry comments are best-effort with forward-only input."
        )
    }

    fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification {
        val trackedInput = TailTrackingInputStream(input)
        var entries = 0
        var bytesRead = 0L
        val names = HashSet<String>()
        val localEntries = mutableListOf<LocalEntryMetadata>()
        var nextLocalOffset = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        ZipInputStream(NonClosingInputStream(trackedInput), ZIP_LEGACY_CHARSET).use { zipIn ->
            while (true) {
                cancellation.throwIfCancelled()
                val entry = zipIn.nextEntry ?: break
                val localHeader = trackedInput.requireLocalHeader(nextLocalOffset, entry)
                ArchivePathPolicy.requireSafe(entry.name)
                if (!names.add(ArchivePathPolicy.normalizedName(entry.name))) {
                    throw ZipException("Duplicate archive entry: ${entry.name}")
                }
                val possibleEpubMimetype = entries == 0 && entry.name == EPUB_MIMETYPE_ENTRY
                val mimetypePrefix = if (possibleEpubMimetype) ByteArray(EPUB_MIMETYPE_BYTES.size) else null
                var entryBytes = 0L

                while (true) {
                    cancellation.throwIfCancelled()
                    val read = zipIn.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        val updatedBytesRead = addInflatedBytes(bytesRead, read)
                        if (mimetypePrefix != null && entryBytes < mimetypePrefix.size) {
                            val copyLength = minOf(read, mimetypePrefix.size - entryBytes.toInt())
                            buffer.copyInto(mimetypePrefix, entryBytes.toInt(), 0, copyLength)
                        }
                        entryBytes += read.toLong()
                        bytesRead = updatedBytesRead
                    }
                }
                zipIn.closeEntry()
                if (
                    mimetypePrefix != null &&
                    entryBytes == EPUB_MIMETYPE_BYTES.size.toLong() &&
                    mimetypePrefix.contentEquals(EPUB_MIMETYPE_BYTES) &&
                    entry.method != ZipEntry.STORED
                ) {
                    throw ZipException("EPUB mimetype entry must be stored without compression")
                }
                val localEntry = LocalEntryMetadata.from(entry, localHeader)
                nextLocalOffset = trackedInput.requireLocalEntryEnd(localEntry)
                localEntries += localEntry
                entries++
            }
        }
        drainAndRequireCentralDirectory(
            trackedInput,
            buffer,
            localEntries,
            nextLocalOffset,
            cancellation
        )

        if (entries == 0) throw ZipException("Archive contains no entries")
        return ZipVerification(entries, bytesRead)
    }

    private fun copyMetadata(source: ZipEntry, preserveStored: Boolean): ZipEntry = ZipEntry(source.name).apply {
        method = if (preserveStored) ZipEntry.STORED else ZipEntry.DEFLATED
        if (preserveStored) {
            if (source.size < 0 || source.crc < 0) {
                throw ZipException("Stored EPUB mimetype metadata is incomplete")
            }
            size = source.size
            compressedSize = source.size
            crc = source.crc
        }
        if (source.time >= 0) time = source.time
        source.extra?.let { extra = it }
        source.comment?.let { comment = it }
    }

    protected open fun createZipOutputStream(output: OutputStream): ZipOutputStream = ZipOutputStream(output)

    private fun addInflatedBytes(total: Long, read: Int): Long {
        val updated = try {
            Math.addExact(total, read.toLong())
        } catch (_: ArithmeticException) {
            throw ArchiveResourceLimitException("Archive inflated payload exceeded its work limit")
        }
        if (updated > maxInflatedBytes) {
            throw ArchiveResourceLimitException(
                "Archive inflated payload exceeded the $maxInflatedBytes-byte work limit"
            )
        }
        return updated
    }

    private fun drainAndRequireCentralDirectory(
        input: TailTrackingInputStream,
        buffer: ByteArray,
        localEntries: List<LocalEntryMetadata>,
        expectedCentralOffset: Long,
        cancellation: CancellationToken
    ) {
        while (true) {
            cancellation.throwIfCancelled()
            if (input.read(buffer) < 0) break
        }
        input.requireCentralDirectoryAndEocd(localEntries, expectedCentralOffset)
    }

    private data class LocalHeaderMetadata(
        val offset: Long,
        val headerBytes: Long,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long
    )

    private data class LocalEntryMetadata(
        val name: String,
        val localOffset: Long,
        val headerBytes: Long,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long
    ) {
        companion object {
            fun from(entry: ZipEntry, header: LocalHeaderMetadata): LocalEntryMetadata {
                if (entry.method < 0 || entry.crc < 0 || entry.compressedSize < 0 || entry.size < 0) {
                    throw ZipException("ZIP entry metadata is incomplete")
                }
                if (entry.compressedSize >= ZIP64_SENTINEL || entry.size >= ZIP64_SENTINEL) {
                    throw UnsupportedZipFeatureException("ZIP64 archives")
                }
                if (entry.method != header.method) {
                    throw ZipException("Local ZIP header does not match streamed entry")
                }
                if (header.flags and DATA_DESCRIPTOR_FLAG == 0 &&
                    (entry.crc != header.crc || entry.compressedSize != header.compressedSize ||
                        entry.size != header.size)
                ) {
                    throw ZipException("Local ZIP header does not match streamed entry")
                }
                return LocalEntryMetadata(
                    entry.name,
                    header.offset,
                    header.headerBytes,
                    header.flags,
                    entry.method,
                    entry.crc,
                    entry.compressedSize,
                    entry.size
                )
            }
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length.toLong()
        }
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private class TailTrackingInputStream(private val input: InputStream) : InputStream() {
        private val tail = ByteArray(MAX_EOCD_TAIL_BYTES)
        private var tailSize = 0
        private var nextTailIndex = 0
        private var totalBytesRead = 0L

        override fun read(): Int {
            val value = input.read()
            if (value >= 0) record(value.toByte())
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = input.read(buffer, offset, length)
            if (read > 0) {
                var index = offset
                repeat(read) {
                    record(buffer[index])
                    index++
                }
            }
            return read
        }

        fun requireLocalHeader(expectedOffset: Long, entry: ZipEntry): LocalHeaderMetadata {
            if (expectedOffset < 0 || expectedOffset >= ZIP64_SENTINEL ||
                requiredUnsignedIntAtAbsolute(expectedOffset) != LOCAL_FILE_HEADER_SIGNATURE
            ) {
                throw ZipException("Invalid local ZIP header offset")
            }
            val flags = requiredUnsignedShortAtAbsolute(expectedOffset + 6)
            if (flags and ENCRYPTED_FLAG != 0) throw UnsupportedZipFeatureException("Encrypted ZIP entries")
            val method = requiredUnsignedShortAtAbsolute(expectedOffset + 8)
            val crc = requiredUnsignedIntAtAbsolute(expectedOffset + 14)
            val compressedSize = requiredUnsignedIntAtAbsolute(expectedOffset + 18)
            val size = requiredUnsignedIntAtAbsolute(expectedOffset + 22)
            if (compressedSize == ZIP64_SENTINEL || size == ZIP64_SENTINEL) {
                throw UnsupportedZipFeatureException("ZIP64 archives")
            }
            val nameLength = requiredUnsignedShortAtAbsolute(expectedOffset + 26)
            val extraLength = requiredUnsignedShortAtAbsolute(expectedOffset + 28)
            val headerBytes = checkedZipOffset(
                LOCAL_FILE_HEADER_FIXED_BYTES.toLong(),
                nameLength.toLong(),
                extraLength.toLong()
            )
            val nameOffset = expectedOffset + LOCAL_FILE_HEADER_FIXED_BYTES
            val nameBytes = ByteArray(nameLength) { index -> requiredByteAtAbsolute(nameOffset + index).toByte() }
            val name = nameBytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else ZIP_LEGACY_CHARSET)
            if (name != entry.name || method != entry.method) {
                throw ZipException("Local ZIP header does not match streamed entry")
            }
            return LocalHeaderMetadata(expectedOffset, headerBytes, flags, method, crc, compressedSize, size)
        }

        fun requireLocalEntryEnd(entry: LocalEntryMetadata): Long {
            val dataEnd = checkedZipOffset(entry.localOffset, entry.headerBytes, entry.compressedSize)
            if (entry.flags and DATA_DESCRIPTOR_FLAG == 0) return dataEnd

            val signedDescriptor = unsignedIntAtAbsolute(dataEnd) == DATA_DESCRIPTOR_SIGNATURE &&
                descriptorMatches(dataEnd + 4, entry)
            if (signedDescriptor) return checkedZipOffset(dataEnd, DATA_DESCRIPTOR_WITH_SIGNATURE_BYTES)
            if (descriptorMatches(dataEnd, entry)) {
                return checkedZipOffset(dataEnd, DATA_DESCRIPTOR_WITHOUT_SIGNATURE_BYTES)
            }
            throw ZipException("ZIP data descriptor does not match streamed entry")
        }

        fun requireCentralDirectoryAndEocd(
            expectedLocalEntries: List<LocalEntryMetadata>,
            expectedCentralOffset: Long
        ) {
            val eocd = findEocdOffset()
            if (eocd < 0) throw ZipException("Archive is missing a valid end of central directory record")

            val diskNumber = unsignedShortAt(eocd + 4)
            val centralDirectoryDisk = unsignedShortAt(eocd + 6)
            val entriesOnDisk = unsignedShortAt(eocd + 8)
            val entries = unsignedShortAt(eocd + 10)
            val centralDirectorySize = unsignedIntAt(eocd + 12)
            val centralDirectoryOffset = unsignedIntAt(eocd + 16)
            if (entriesOnDisk == ZIP64_ENTRY_SENTINEL || entries == ZIP64_ENTRY_SENTINEL ||
                centralDirectorySize == ZIP64_SENTINEL || centralDirectoryOffset == ZIP64_SENTINEL
            ) {
                throw UnsupportedZipFeatureException("ZIP64 archives")
            }
            if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != entries ||
                centralDirectoryOffset != expectedCentralOffset ||
                entries != expectedLocalEntries.size
            ) {
                throw ZipException("Unsupported or invalid central directory")
            }

            val absoluteEocdOffset = totalBytesRead - tailSize + eocd
            if (centralDirectoryOffset + centralDirectorySize != absoluteEocdOffset) {
                throw ZipException("Invalid central directory bounds")
            }
            val tailStartOffset = totalBytesRead - tailSize
            val centralDirectoryTailOffset = centralDirectoryOffset - tailStartOffset
            if (centralDirectoryTailOffset < 0L || centralDirectorySize > tailSize.toLong() ||
                centralDirectoryTailOffset + centralDirectorySize > tailSize.toLong()
            ) {
                throw ZipException("Central directory exceeds retained validation tail")
            }
            requireClassicCentralDirectory(
                centralDirectoryTailOffset.toInt(),
                centralDirectorySize.toInt(),
                centralDirectoryOffset,
                expectedLocalEntries
            )
        }

        private fun requireClassicCentralDirectory(
            start: Int,
            size: Int,
            centralDirectoryOffset: Long,
            expectedLocalEntries: List<LocalEntryMetadata>
        ) {
            val end = start + size
            var cursor = start
            var records = 0
            val localOffsets = hashSetOf<Long>()
            val remainingLocalEntries = expectedLocalEntries.associateByTo(linkedMapOf()) { it.name }
            while (records < expectedLocalEntries.size) {
                if (end - cursor < CENTRAL_DIRECTORY_FIXED_BYTES ||
                    unsignedIntAt(cursor) != CENTRAL_DIRECTORY_SIGNATURE
                ) {
                    throw ZipException("Malformed central directory record")
                }

                val nameLength = unsignedShortAt(cursor + 28)
                val extraLength = unsignedShortAt(cursor + 30)
                val commentLength = unsignedShortAt(cursor + 32)
                val recordSize = CENTRAL_DIRECTORY_FIXED_BYTES + nameLength + extraLength + commentLength
                if (recordSize > end - cursor) throw ZipException("Malformed central directory variable lengths")

                val flags = unsignedShortAt(cursor + 8)
                if (flags and ENCRYPTED_FLAG != 0) throw UnsupportedZipFeatureException("Encrypted ZIP entries")
                val method = unsignedShortAt(cursor + 10)
                val crc = unsignedIntAt(cursor + 16)
                val compressedSize = unsignedIntAt(cursor + 20)
                val uncompressedSize = unsignedIntAt(cursor + 24)
                val localOffset = unsignedIntAt(cursor + 42)
                if (compressedSize == ZIP64_SENTINEL || uncompressedSize == ZIP64_SENTINEL ||
                    localOffset == ZIP64_SENTINEL || localOffset >= centralDirectoryOffset ||
                    !localOffsets.add(localOffset)
                ) {
                    throw UnsupportedZipFeatureException("ZIP64 or invalid local ZIP offsets")
                }
                val nameBytes = ByteArray(nameLength) { index -> byteAt(cursor + CENTRAL_DIRECTORY_FIXED_BYTES + index).toByte() }
                val name = nameBytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else ZIP_LEGACY_CHARSET)
                val local = remainingLocalEntries.remove(name)
                    ?: throw ZipException("Central directory does not match local ZIP entries")
                if (localOffset != local.localOffset || flags != local.flags || method != local.method || crc != local.crc ||
                    compressedSize != local.compressedSize || uncompressedSize != local.size
                ) {
                    throw ZipException("Central directory does not match local ZIP entries")
                }

                cursor += recordSize
                records++
            }
            if (cursor < end) {
                if (end - cursor < CENTRAL_DIRECTORY_DIGITAL_SIGNATURE_FIXED_BYTES ||
                    unsignedIntAt(cursor) != CENTRAL_DIRECTORY_DIGITAL_SIGNATURE
                ) {
                    throw ZipException("Malformed central directory digital signature")
                }
                val dataLength = unsignedShortAt(cursor + 4)
                val recordSize = CENTRAL_DIRECTORY_DIGITAL_SIGNATURE_FIXED_BYTES + dataLength
                if (recordSize != end - cursor) {
                    throw ZipException("Malformed central directory digital signature length")
                }
                cursor += recordSize
            }
            if (cursor != end || records != expectedLocalEntries.size || remainingLocalEntries.isNotEmpty()) {
                throw ZipException("Central directory record count or size mismatch")
            }
        }

        private fun findEocdOffset(): Int {
            for (offset in tailSize - EOCD_MIN_BYTES downTo 0) {
                if (unsignedIntAt(offset) == EOCD_SIGNATURE &&
                    offset + EOCD_MIN_BYTES + unsignedShortAt(offset + 20) == tailSize
                ) {
                    return offset
                }
            }
            return -1
        }

        private fun record(value: Byte) {
            tail[nextTailIndex] = value
            nextTailIndex = (nextTailIndex + 1) % tail.size
            if (tailSize < tail.size) tailSize++
            totalBytesRead++
        }

        private fun byteAt(offset: Int): Int {
            val firstTailIndex = (nextTailIndex - tailSize + tail.size) % tail.size
            return tail[(firstTailIndex + offset) % tail.size].toInt() and 0xff
        }

        private fun byteAtAbsolute(offset: Long): Int? {
            val tailStartOffset = totalBytesRead - tailSize
            if (offset < tailStartOffset || offset >= totalBytesRead) return null
            return byteAt((offset - tailStartOffset).toInt())
        }

        private fun requiredByteAtAbsolute(offset: Long): Int =
            byteAtAbsolute(offset) ?: throw ZipException("Local ZIP header exceeds retained validation data")

        private fun requiredUnsignedShortAtAbsolute(offset: Long): Int =
            requiredByteAtAbsolute(offset) or (requiredByteAtAbsolute(offset + 1) shl 8)

        private fun requiredUnsignedIntAtAbsolute(offset: Long): Long =
            requiredUnsignedShortAtAbsolute(offset).toLong() or
                (requiredUnsignedShortAtAbsolute(offset + 2).toLong() shl 16)

        private fun unsignedIntAtAbsolute(offset: Long): Long? {
            val first = byteAtAbsolute(offset) ?: return null
            val second = byteAtAbsolute(offset + 1) ?: return null
            val third = byteAtAbsolute(offset + 2) ?: return null
            val fourth = byteAtAbsolute(offset + 3) ?: return null
            return first.toLong() or (second.toLong() shl 8) or
                (third.toLong() shl 16) or (fourth.toLong() shl 24)
        }

        private fun descriptorMatches(offset: Long, entry: LocalEntryMetadata): Boolean =
            unsignedIntAtAbsolute(offset) == entry.crc &&
                unsignedIntAtAbsolute(offset + 4) == entry.compressedSize &&
                unsignedIntAtAbsolute(offset + 8) == entry.size

        private fun unsignedShortAt(offset: Int): Int = byteAt(offset) or (byteAt(offset + 1) shl 8)

        private fun unsignedIntAt(offset: Int): Long =
            unsignedShortAt(offset).toLong() or (unsignedShortAt(offset + 2).toLong() shl 16)

        private companion object {
            const val EOCD_SIGNATURE = 0x06054b50L
            const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
            const val CENTRAL_DIRECTORY_DIGITAL_SIGNATURE = 0x05054b50L
            const val EOCD_MIN_BYTES = 22
            const val CENTRAL_DIRECTORY_FIXED_BYTES = 46
            const val CENTRAL_DIRECTORY_DIGITAL_SIGNATURE_FIXED_BYTES = 6
            const val MAX_EOCD_TAIL_BYTES = 256 * 1024
            const val ZIP64_ENTRY_SENTINEL = 0xffff
            const val ENCRYPTED_FLAG = 1
            const val UTF8_FLAG = 1 shl 11
            const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
            const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
            const val LOCAL_FILE_HEADER_FIXED_BYTES = 30
            const val DATA_DESCRIPTOR_WITHOUT_SIGNATURE_BYTES = 12L
            const val DATA_DESCRIPTOR_WITH_SIGNATURE_BYTES = 16L
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val DATA_DESCRIPTOR_FLAG = 1 shl 3
        const val ZIP64_SENTINEL = 0xffffffffL
        const val DEFAULT_MAX_INFLATED_BYTES = 1024L * 1024L * 1024L
        const val EPUB_MIMETYPE_ENTRY = "mimetype"
        val EPUB_MIMETYPE_BYTES = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val ZIP_LEGACY_CHARSET: Charset = Charset.forName("IBM437")

        fun checkedZipOffset(vararg values: Long): Long = try {
            values.fold(0L) { total, value -> Math.addExact(total, value) }
        } catch (_: ArithmeticException) {
            throw UnsupportedZipFeatureException("ZIP64 archives")
        }
    }
}
