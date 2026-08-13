package com.fileforge.optimizer

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
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

class StreamingZipOptimizer {
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

        ZipInputStream(NonClosingInputStream(trackedInput)).use { zipIn ->
            ZipOutputStream(NonClosingOutputStream(countedOutput)).use { zipOut ->
                zipOut.setLevel(level)
                while (true) {
                    cancellation.throwIfCancelled()
                    val source = zipIn.nextEntry ?: break
                    ArchivePathPolicy.requireSafe(source.name)
                    if (!names.add(ArchivePathPolicy.normalizedName(source.name))) {
                        throw ZipException("Duplicate archive entry: ${source.name}")
                    }

                    zipOut.putNextEntry(copyMetadata(source))
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = zipIn.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            cancellation.throwIfCancelled()
                            zipOut.write(buffer, 0, read)
                            cancellation.throwIfCancelled()
                            inputBytes += read.toLong()
                            onBytes(read.toLong())
                        }
                    }
                    zipOut.closeEntry()
                    zipIn.closeEntry()
                    entries++
                }
                drainAndRequireCentralDirectory(trackedInput, buffer, entries, cancellation)
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
        val buffer = ByteArray(BUFFER_BYTES)

        ZipInputStream(NonClosingInputStream(trackedInput)).use { zipIn ->
            while (true) {
                cancellation.throwIfCancelled()
                val entry = zipIn.nextEntry ?: break
                ArchivePathPolicy.requireSafe(entry.name)
                if (!names.add(ArchivePathPolicy.normalizedName(entry.name))) {
                    throw ZipException("Duplicate archive entry: ${entry.name}")
                }

                while (true) {
                    cancellation.throwIfCancelled()
                    val read = zipIn.read(buffer)
                    if (read < 0) break
                    if (read > 0) bytesRead += read.toLong()
                }
                zipIn.closeEntry()
                entries++
            }
        }
        drainAndRequireCentralDirectory(trackedInput, buffer, entries, cancellation)

        if (entries == 0) throw ZipException("Archive contains no entries")
        return ZipVerification(entries, bytesRead)
    }

    private fun copyMetadata(source: ZipEntry): ZipEntry = ZipEntry(source.name).apply {
        method = ZipEntry.DEFLATED
        if (source.time >= 0) time = source.time
        source.extra?.let { extra = it }
        source.comment?.let { comment = it }
    }

    private fun drainAndRequireCentralDirectory(
        input: TailTrackingInputStream,
        buffer: ByteArray,
        entries: Int,
        cancellation: CancellationToken
    ) {
        while (true) {
            cancellation.throwIfCancelled()
            if (input.read(buffer) < 0) break
        }
        input.requireCentralDirectoryAndEocd(entries)
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

        fun requireCentralDirectoryAndEocd(expectedEntries: Int) {
            val eocd = findEocdOffset()
            if (eocd < 0) throw ZipException("Archive is missing a valid end of central directory record")

            val diskNumber = unsignedShortAt(eocd + 4)
            val centralDirectoryDisk = unsignedShortAt(eocd + 6)
            val entriesOnDisk = unsignedShortAt(eocd + 8)
            val entries = unsignedShortAt(eocd + 10)
            val centralDirectorySize = unsignedIntAt(eocd + 12)
            val centralDirectoryOffset = unsignedIntAt(eocd + 16)
            if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != entries ||
                entries != expectedEntries ||
                centralDirectorySize == ZIP64_SENTINEL || centralDirectoryOffset == ZIP64_SENTINEL
            ) {
                throw ZipException("Unsupported or invalid central directory")
            }

            val absoluteEocdOffset = totalBytesRead - tailSize + eocd
            if (centralDirectoryOffset + centralDirectorySize != absoluteEocdOffset) {
                throw ZipException("Invalid central directory bounds")
            }
            val tailStartOffset = totalBytesRead - tailSize
            val centralDirectoryTailOffset = centralDirectoryOffset - tailStartOffset
            if (centralDirectorySize >= 4L && centralDirectoryTailOffset >= 0L &&
                centralDirectoryTailOffset + 4L <= tailSize.toLong() &&
                unsignedIntAt(centralDirectoryTailOffset.toInt()) != CENTRAL_DIRECTORY_SIGNATURE
            ) {
                throw ZipException("Invalid central directory header")
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

        private fun unsignedShortAt(offset: Int): Int = byteAt(offset) or (byteAt(offset + 1) shl 8)

        private fun unsignedIntAt(offset: Int): Long =
            unsignedShortAt(offset).toLong() or (unsignedShortAt(offset + 2).toLong() shl 16)

        private companion object {
            const val EOCD_SIGNATURE = 0x06054b50L
            const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
            const val EOCD_MIN_BYTES = 22
            const val MAX_EOCD_TAIL_BYTES = EOCD_MIN_BYTES + 0xffff
            const val ZIP64_SENTINEL = 0xffffffffL
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
    }
}
