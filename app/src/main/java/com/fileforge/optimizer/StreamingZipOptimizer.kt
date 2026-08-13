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
        var entries = 0
        var inputBytes = 0L
        val level = if (mode == OptimizeMode.AGGRESSIVE) Deflater.BEST_COMPRESSION else 7
        val names = HashSet<String>()

        ZipInputStream(NonClosingInputStream(input)).use { zipIn ->
            ZipOutputStream(NonClosingOutputStream(countedOutput)).use { zipOut ->
                zipOut.setLevel(level)
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    cancellation.throwIfCancelled()
                    val source = zipIn.nextEntry ?: break
                    ArchivePathPolicy.requireSafe(source.name)
                    if (!names.add(source.name)) throw ZipException("Duplicate archive entry: ${source.name}")

                    zipOut.putNextEntry(copyMetadata(source))
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = zipIn.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            zipOut.write(buffer, 0, read)
                            inputBytes += read.toLong()
                            onBytes(read.toLong())
                        }
                    }
                    zipOut.closeEntry()
                    zipIn.closeEntry()
                    entries++
                }
            }
        }

        if (entries == 0) throw ZipException("Archive contains no entries")
        return ZipOptimizationSummary(
            entries = entries,
            inputBytes = inputBytes,
            outputBytes = countedOutput.bytesWritten,
            note = "Recompressed ZIP container at deflate level $level."
        )
    }

    fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification {
        var entries = 0
        var bytesRead = 0L
        val names = HashSet<String>()
        val buffer = ByteArray(BUFFER_BYTES)

        ZipInputStream(NonClosingInputStream(input)).use { zipIn ->
            while (true) {
                cancellation.throwIfCancelled()
                val entry = zipIn.nextEntry ?: break
                ArchivePathPolicy.requireSafe(entry.name)
                if (!names.add(entry.name)) throw ZipException("Duplicate archive entry: ${entry.name}")

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

        if (entries == 0) throw ZipException("Archive contains no entries")
        return ZipVerification(entries, bytesRead)
    }

    private fun copyMetadata(source: ZipEntry): ZipEntry = ZipEntry(source.name).apply {
        method = ZipEntry.DEFLATED
        if (source.time >= 0) time = source.time
        source.extra?.let { extra = it }
        source.comment?.let { comment = it }
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

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
    }
}
