package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LargeStreamingZipTest {
    private val optimizer = StreamingZipOptimizer()

    @Test
    fun optimizesAndVerifiesA301MiBArchiveWithLessThan64MiBHeapGrowth() {
        assumeTrue(
            "Set FILEFORGE_RUN_LARGE_STREAMING_TEST=true to run the 301 MiB regression",
            "true".equals(System.getenv("FILEFORGE_RUN_LARGE_STREAMING_TEST"), ignoreCase = true),
        )

        val source = Files.createTempFile("fileforge-301m-source", ".zip")
        val output = Files.createTempFile("fileforge-301m-output", ".zip")
        try {
            write301MiBArchive(source)
            val before = stabilizedUsedHeap()

            Files.newInputStream(source).use { input ->
                Files.newOutputStream(output).use { destination ->
                    optimizer.optimize(input, destination, OptimizeMode.SAFE, NeverCancelled) {}
                }
            }

            val after = stabilizedUsedHeap()
            Files.newInputStream(output).use { optimized ->
                val verification = optimizer.verify(optimized, NeverCancelled)
                assertEquals(1, verification.entries)
                assertEquals(ARCHIVE_PAYLOAD_BYTES, verification.bytesRead)
            }
            assertTrue("heap growth was ${after - before} bytes", after - before < MAX_HEAP_GROWTH_BYTES)
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(output)
        }
    }

    private fun write301MiBArchive(path: Path) {
        Files.newOutputStream(path).use { file ->
            ZipOutputStream(file).use { zip ->
                zip.putNextEntry(ZipEntry("large/pseudorandom.bin"))
                writeDeterministicBytes(zip, ARCHIVE_PAYLOAD_BYTES)
                zip.closeEntry()
            }
        }
    }

    private fun writeDeterministicBytes(output: OutputStream, totalBytes: Long) {
        val block = ByteArray(BLOCK_BYTES)
        var state = 0x13579bdf
        var remaining = totalBytes
        while (remaining > 0) {
            for (index in block.indices) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                block[index] = state.toByte()
            }
            val count = minOf(remaining, block.size.toLong()).toInt()
            output.write(block, 0, count)
            remaining -= count
        }
    }

    private fun stabilizedUsedHeap(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val BLOCK_BYTES = 8 * 1024
        const val ARCHIVE_PAYLOAD_BYTES = 301L * 1024L * 1024L
        const val MAX_HEAP_GROWTH_BYTES = 64L * 1024L * 1024L
    }
}
