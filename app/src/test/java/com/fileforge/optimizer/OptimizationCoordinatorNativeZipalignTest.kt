package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OptimizationCoordinatorNativeZipalignTest {
    @Test
    fun apkLabPassesTheStreamingCandidateThroughVerifiedNativeZipalign() {
        withCoordinator { gateway, candidateStore, processor ->
            val calls = mutableListOf<NativeToolId>()
            val executor = NativeToolExecutor { tool, input, output, _, _ ->
                calls += tool
                output.writeBytes(input.readBytes())
                NativeExecution.Success(output, "aligned")
            }
            val coordinator = OptimizationCoordinator(gateway, candidateStore, processor, executor)
            val node = gateway.put("application.apk", apkShapedOriginal())

            val outcome = coordinator.process(node, "application.apk", apkDryRun, NeverCancelled)

            assertTrue(outcome is FileOutcome.WouldOptimize)
            assertEquals(listOf(NativeToolId.ZIPALIGN), calls)
            assertTrue((outcome as FileOutcome.WouldOptimize).tool.contains("zipalign"))
        }
    }

    @Test
    fun zipalignFailureKeepsTheAlreadyVerifiedStreamingCandidate() {
        withCoordinator { gateway, candidateStore, processor ->
            val executor = NativeToolExecutor { _, _, _, _, _ -> NativeExecution.Failed(3, "alignment failed") }
            val coordinator = OptimizationCoordinator(gateway, candidateStore, processor, executor)
            val node = gateway.put("application.apk", apkShapedOriginal())

            val outcome = coordinator.process(node, "application.apk", apkDryRun, NeverCancelled)

            assertTrue(outcome is FileOutcome.WouldOptimize)
            assertEquals("StreamingZipOptimizer", (outcome as FileOutcome.WouldOptimize).tool)
        }
    }

    @Test
    fun zipalignReceivesALiveLimitThatAccountsForTheStreamingCandidate() {
        val cache = Files.createTempDirectory("fileforge-native-zipalign-budget").toFile()
        try {
            val gateway = RecordingDocumentGateway()
            val optimized = alignedStoredZip("classes.dex", "candidate".toByteArray(), 4)
            val processor = FixedCandidateProcessor(optimized, gateway.events)
            val fileSystem = LiveSpaceCandidateFileSystem(
                CandidateStore.MIN_FREE_SPACE_BYTES + optimized.size + 32L
            )
            val store = CandidateStore(cache, RUN_ID, fileSystem)
            var limitTriggered = false
            val executor = NativeToolExecutor { _, _, output, _, cancellation ->
                output.writeBytes(ByteArray(33))
                try {
                    cancellation.throwIfCancelled()
                } catch (limited: CandidateSizeLimitExceededException) {
                    limitTriggered = true
                    throw limited
                }
                NativeExecution.Success(output, "should not complete")
            }
            val coordinator = OptimizationCoordinator(gateway, store, processor, executor)
            val node = gateway.put("application.apk", apkShapedOriginal())

            val outcome = coordinator.process(node, "application.apk", apkDryRun, NeverCancelled)

            assertTrue(limitTriggered)
            assertTrue(outcome is FileOutcome.WouldOptimize)
            assertEquals("StreamingZipOptimizer", (outcome as FileOutcome.WouldOptimize).tool)
            assertEquals(emptyList<String>(), cache.list().orEmpty().sorted())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun alignmentVerifierRejectsCentralAndLocalNameDisagreement() {
        val root = Files.createTempDirectory("fileforge-alignment-verifier").toFile()
        try {
            val archive = root.resolve("mismatch.zip")
            val bytes = alignedStoredZip("classes.dex", "candidate".toByteArray(), 4)
            val central = findSignature(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            bytes[central + 46] = 'X'.code.toByte()
            archive.writeBytes(bytes)

            assertFalse(ZipAlignmentVerifier.verify(archive, NeverCancelled))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withCoordinator(
        block: (RecordingDocumentGateway, CandidateStore, ZipCandidateProcessor) -> Unit
    ) {
        val cache = Files.createTempDirectory("fileforge-native-zipalign").toFile()
        try {
            val gateway = RecordingDocumentGateway()
            val alignedCandidate = alignedStoredZip("classes.dex", "candidate".toByteArray(), 4)
            val processor = FixedCandidateProcessor(alignedCandidate, gateway.events)
            block(gateway, CandidateStore(cache, RUN_ID), processor)
            assertEquals(emptyList<String>(), cache.list().orEmpty().sorted())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun apkShapedOriginal(): ByteArray = ByteArray(8 * 1024).also {
        it[0] = 'P'.code.toByte()
        it[1] = 'K'.code.toByte()
        it[2] = 3
        it[3] = 4
    }

    private fun alignedStoredZip(name: String, contents: ByteArray, alignment: Int): ByteArray {
        val crc = CRC32().apply { update(contents) }
        val fixedHeaderAndName = 30 + name.toByteArray(Charsets.UTF_8).size
        var extraLength = (alignment - (fixedHeaderAndName % alignment)) % alignment
        if (extraLength in 1..3) extraLength += alignment
        val extra = if (extraLength == 0) null else ByteArray(extraLength).also {
            it[0] = 0x7f
            it[1] = 0x7f
            val payloadLength = extraLength - 4
            it[2] = (payloadLength and 0xff).toByte()
            it[3] = ((payloadLength ushr 8) and 0xff).toByte()
        }
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = contents.size.toLong()
                    compressedSize = contents.size.toLong()
                    this.crc = crc.value
                    this.extra = extra
                }
                zip.putNextEntry(entry)
                zip.write(contents)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun findSignature(bytes: ByteArray, signature: ByteArray): Int {
        for (index in 0..bytes.size - signature.size) {
            if (signature.indices.all { offset -> bytes[index + offset] == signature[offset] }) return index
        }
        error("ZIP signature not found")
    }

    private class LiveSpaceCandidateFileSystem(private val totalUsableSpace: Long) : CandidateFileSystem {
        override fun exists(file: java.io.File): Boolean = file.exists()
        override fun createDirectories(directory: java.io.File) {
            if (!directory.exists() && !directory.mkdirs()) error("cannot create candidate directory")
        }
        override fun createTempFile(directory: java.io.File, suffix: String): java.io.File =
            java.io.File.createTempFile("candidate-", suffix, directory)
        override fun delete(file: java.io.File): Boolean = !file.exists() || file.delete()
        override fun deleteRecursively(directory: java.io.File): Boolean =
            !directory.exists() || directory.deleteRecursively()
        override fun list(directory: java.io.File): Array<String>? = directory.list()
        override fun usableSpace(directory: java.io.File): Long {
            val used = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return (totalUsableSpace - used).coerceAtLeast(0)
        }
    }

    private companion object {
        const val RUN_ID = "run-native-zipalign"
        val apkDryRun = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = true, textMinify = false)
    }
}
