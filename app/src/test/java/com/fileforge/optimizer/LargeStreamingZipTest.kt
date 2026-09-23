package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
            val baselineUsedHeap = stabilizedUsedHeap()
            val heapSampler = UsedHeapSampler(baselineUsedHeap)
            var wallTimeMillis = 0L
            var maxHeapDeltaBytes = 0L
            heapSampler.start()
            val startedAtNanos = System.nanoTime()
            try {
                Files.newInputStream(source).use { input ->
                    Files.newOutputStream(output).use { destination ->
                        optimizer.optimize(input, destination, OptimizeMode.SAFE, NeverCancelled) {}
                    }
                }

                Files.newInputStream(output).use { optimized ->
                    val verification = optimizer.verify(optimized, NeverCancelled)
                    assertEquals(1, verification.entries)
                    assertEquals(ARCHIVE_PAYLOAD_BYTES, verification.bytesRead)
                }
            } finally {
                heapSampler.sampleNow()
                wallTimeMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND
                maxHeapDeltaBytes = heapSampler.stopAndGetMaxDeltaBytes()
                println(
                    "FILEFORGE_LARGE_STREAMING_METRICS " +
                        "payloadBytes=$ARCHIVE_PAYLOAD_BYTES " +
                        "wallTimeMillis=$wallTimeMillis " +
                        "maxHeapDeltaBytes=$maxHeapDeltaBytes"
                )
            }
            assertTrue(
                "sampled peak heap delta was $maxHeapDeltaBytes bytes over ${wallTimeMillis}ms",
                maxHeapDeltaBytes < MAX_HEAP_GROWTH_BYTES
            )
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

    private class UsedHeapSampler(private val baselineUsedHeap: Long) {
        private val running = AtomicBoolean(false)
        private val peakUsedHeap = AtomicLong(baselineUsedHeap)
        private val thread = Thread({ sampleUntilStopped() }, "fileforge-large-streaming-heap-sampler").apply {
            isDaemon = true
        }

        fun start() {
            check(running.compareAndSet(false, true)) { "Heap sampler can only be started once" }
            thread.start()
        }

        fun sampleNow() {
            recordUsedHeap()
        }

        fun stopAndGetMaxDeltaBytes(): Long {
            running.set(false)
            thread.interrupt()
            try {
                thread.join(SAMPLER_JOIN_TIMEOUT_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted while stopping heap sampler", interrupted)
            }
            check(!thread.isAlive) { "Heap sampler did not stop" }
            return maxOf(0L, peakUsedHeap.get() - baselineUsedHeap)
        }

        private fun sampleUntilStopped() {
            while (running.get()) {
                recordUsedHeap()
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    // stopAndGetMaxDeltaBytes interrupts the sleep so the daemon can join promptly.
                }
            }
        }

        private fun recordUsedHeap() {
            val runtime = Runtime.getRuntime()
            val usedHeap = runtime.totalMemory() - runtime.freeMemory()
            while (true) {
                val previousPeak = peakUsedHeap.get()
                if (usedHeap <= previousPeak || peakUsedHeap.compareAndSet(previousPeak, usedHeap)) return
            }
        }
    }

    private companion object {
        const val BLOCK_BYTES = 8 * 1024
        const val ARCHIVE_PAYLOAD_BYTES = 301L * 1024L * 1024L
        const val MAX_HEAP_GROWTH_BYTES = 64L * 1024L * 1024L
        const val SAMPLE_INTERVAL_MILLIS = 5L
        const val SAMPLER_JOIN_TIMEOUT_MILLIS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
