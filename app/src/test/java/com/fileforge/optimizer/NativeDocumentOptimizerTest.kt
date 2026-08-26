package com.fileforge.optimizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class NativeDocumentOptimizerTest {
    @Test
    fun verifiedNativePdfGainUsesQpdfWithoutWritingTheSelectedTree() = withOptimizer { gateway, optimizer, calls ->
        val original = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n${"x".repeat(128)}".toByteArray()
        val node = gateway.put("document.pdf", original)
        gateway.events.clear()

        val outcome = optimizer.process(node, "document.pdf", FileKind.PDF, dryRun, NeverCancelled)

        assertTrue(outcome is FileOutcome.WouldOptimize)
        assertEquals("qpdf", (outcome as FileOutcome.WouldOptimize).tool)
        assertEquals(listOf(NativeToolId.QPDF), calls)
        assertArrayEquals(original, gateway.contents("document.pdf"))
        assertFalse(gateway.events.any { it.startsWith("write:") || it.startsWith("create-file:") })
    }

    @Test
    fun nativeFailureFallsBackToTheExistingKotlinOptimizer() = withOptimizer(
        execution = { _, _, _, _, _ -> NativeExecution.Failed(2, "native failed") }
    ) { gateway, optimizer, calls ->
        val original = "%PDF-1.4\n%%EOF\ntrailing bytes".toByteArray()
        val node = gateway.put("fallback.pdf", original)
        gateway.events.clear()

        val outcome = optimizer.process(node, "fallback.pdf", FileKind.PDF, dryRun, NeverCancelled)

        assertTrue(outcome is FileOutcome.WouldOptimize)
        assertEquals("KotlinByteArrayOptimizer", (outcome as FileOutcome.WouldOptimize).tool)
        assertEquals(listOf(NativeToolId.QPDF), calls)
        assertArrayEquals(original, gateway.contents("fallback.pdf"))
    }

    @Test
    fun signedPdfAndAnimatedPngFailClosedBeforeNativeExecution() = withOptimizer { gateway, optimizer, calls ->
        val signed = gateway.put(
            "signed.pdf",
            "%PDF-1.7\n/Type /Sig\n/ByteRange [0 12 34 56]\n%%EOF\ntrailing".toByteArray()
        )
        val animated = gateway.put("animated.png", png(animated = true) + ByteArray(64))

        val signedOutcome = optimizer.process(signed, "signed.pdf", FileKind.PDF, dryRun, NeverCancelled)
        val animatedOutcome = optimizer.process(animated, "animated.png", FileKind.PNG, dryRun, NeverCancelled)

        assertTrue(signedOutcome is FileOutcome.Skipped)
        assertEquals(SkipReason.NO_CHANGE, (signedOutcome as FileOutcome.Skipped).reason)
        assertTrue(signedOutcome.note.contains("signed", ignoreCase = true))
        assertTrue(animatedOutcome is FileOutcome.Skipped)
        assertEquals(SkipReason.NO_CHANGE, (animatedOutcome as FileOutcome.Skipped).reason)
        assertTrue(animatedOutcome.note.contains("animated", ignoreCase = true))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun escapedPdfSignatureNamesAndSignatureFieldsWithoutTypeFailClosed() = withOptimizer { gateway, optimizer, calls ->
        val escapedByteRange = gateway.put(
            "escaped-byte-range.pdf",
            "%PDF-1.7\n<< /Byte#52ange [0 12 34 56] /Contents <00> >>\n%%EOF\ntrailing".toByteArray()
        )
        val signatureField = gateway.put(
            "signature-field.pdf",
            "%PDF-1.7\n<< /FT /S#69g /T (Approval) >>\n%%EOF\ntrailing".toByteArray()
        )

        listOf(escapedByteRange to "escaped-byte-range.pdf", signatureField to "signature-field.pdf")
            .forEach { (node, path) ->
                val outcome = optimizer.process(node, path, FileKind.PDF, dryRun, NeverCancelled)
                assertTrue(outcome is FileOutcome.Skipped)
                assertEquals(SkipReason.NO_CHANGE, (outcome as FileOutcome.Skipped).reason)
                assertTrue(outcome.note.contains("signed", ignoreCase = true))
            }

        assertTrue(calls.isEmpty())
    }

    @Test
    fun nativeOutputCannotCrossTheLiveCandidateStorageBudget() {
        val cache = Files.createTempDirectory("fileforge-native-live-limit").toFile()
        try {
            val gateway = RecordingDocumentGateway()
            val original = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n${"x".repeat(96)}".toByteArray()
            val node = gateway.put("bounded.pdf", original)
            val fileSystem = LiveSpaceCandidateFileSystem(
                CandidateStore.MIN_FREE_SPACE_BYTES + original.size + 32L
            )
            val optimizer = NativeDocumentOptimizer(
                documentGateway = gateway,
                candidateStore = CandidateStore(cache, RUN_ID, fileSystem),
                executor = NativeToolExecutor { _, _, output, _, cancellation ->
                    output.writeBytes(ByteArray(33))
                    cancellation.throwIfCancelled()
                    NativeExecution.Success(output, "should not complete")
                },
                fallback = ByteArrayOptimizerAdapter(gateway)
            )

            val outcome = optimizer.process(node, "bounded.pdf", FileKind.PDF, dryRun, NeverCancelled)

            assertTrue(outcome is FileOutcome.Skipped)
            assertEquals(SkipReason.STORAGE_LIMIT, (outcome as FileOutcome.Skipped).reason)
            assertArrayEquals(original, gateway.contents("bounded.pdf"))
            assertEquals(emptyList<String>(), cache.list().orEmpty().sorted())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun pngPolicyRoutesSafeToOxipngAndAggressiveToZopflipng() = withOptimizer(
        candidate = png(animated = false)
    ) { gateway, optimizer, calls ->
        val original = png(animated = false) + ByteArray(96)
        val safeNode = gateway.put("safe.png", original)
        val aggressiveNode = gateway.put("aggressive.png", original)

        assertTrue(optimizer.process(safeNode, "safe.png", FileKind.PNG, dryRun, NeverCancelled) is FileOutcome.WouldOptimize)
        assertTrue(
            optimizer.process(
                aggressiveNode,
                "aggressive.png",
                FileKind.PNG,
                dryRun.copy(mode = OptimizeMode.AGGRESSIVE),
                NeverCancelled
            ) is FileOutcome.WouldOptimize
        )

        assertEquals(listOf(NativeToolId.OXIPNG, NativeToolId.ZOPFLIPNG), calls)
    }

    @Test
    fun acceptedNativeCandidateUsesBackupVerificationAndDurableUndoBeforeSuccess() {
        val cache = Files.createTempDirectory("fileforge-native-transaction").toFile()
        try {
            val gateway = RecordingDocumentGateway()
            val original = "%PDF-1.7\n%%EOF\n${"x".repeat(128)}".toByteArray()
            val optimized = "%PDF-1.7\n%%EOF\n".toByteArray()
            val node = gateway.put("document.pdf", original)
            val undo = RecordingUndoEntrySink(gateway.events)
            val executor = NativeToolExecutor { _, _, output, _, _ ->
                output.writeBytes(optimized)
                NativeExecution.Success(output, "native ok")
            }
            val optimizer = NativeDocumentOptimizer(
                documentGateway = gateway,
                candidateStore = CandidateStore(cache, RUN_ID),
                executor = executor,
                fallback = ByteArrayOptimizerAdapter(gateway),
                commitContext = CommitContext(gateway.root, RUN_ID, undo) { "2026-08-25T00:00:01.000Z" }
            )

            val outcome = optimizer.process(
                node,
                "document.pdf",
                FileKind.PDF,
                dryRun.copy(dryRun = false),
                NeverCancelled
            )

            assertTrue(outcome is FileOutcome.Optimized)
            assertArrayEquals(optimized, gateway.contents("document.pdf"))
            assertArrayEquals(original, gateway.contents("FileForge_Backups_$RUN_ID/document.pdf"))
            assertEquals(1, undo.entries.size)
            assertEquals("qpdf", undo.entries.single().toolId)
            assertEquals(original.sha256(), undo.entries.single().originalSha256)
            assertEquals(optimized.sha256(), undo.entries.single().optimizedSha256)
            assertEquals(emptyList<String>(), cache.list().orEmpty().sorted())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun withOptimizer(
        candidate: ByteArray = "%PDF-1.7\n%%EOF\n".toByteArray(),
        execution: NativeToolExecutor = NativeToolExecutor { tool, _, output, _, _ ->
            output.writeBytes(candidate)
            NativeExecution.Success(output, "native ok")
        },
        block: (RecordingDocumentGateway, NativeDocumentOptimizer, MutableList<NativeToolId>) -> Unit
    ) {
        val cache = Files.createTempDirectory("fileforge-native-document").toFile()
        try {
            val gateway = RecordingDocumentGateway()
            val calls = mutableListOf<NativeToolId>()
            val recordingExecutor = NativeToolExecutor { tool, input, output, mode, cancellation ->
                calls += tool
                execution.run(tool, input, output, mode, cancellation)
            }
            val fallback = ByteArrayOptimizerAdapter(gateway)
            val optimizer = NativeDocumentOptimizer(
                documentGateway = gateway,
                candidateStore = CandidateStore(cache, RUN_ID),
                executor = recordingExecutor,
                fallback = fallback
            )
            block(gateway, optimizer, calls)
            assertEquals(emptyList<String>(), cache.list().orEmpty().sorted())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun png(animated: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0).array()
        writeChunk(output, "IHDR", ihdr)
        if (animated) writeChunk(output, "acTL", ByteBuffer.allocate(8).putInt(1).putInt(0).array())
        val compressed = ByteArrayOutputStream().also { data ->
            DeflaterOutputStream(data).use { it.write(byteArrayOf(0, 0, 0, 0)) }
        }.toByteArray()
        writeChunk(output, "IDAT", compressed)
        writeChunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
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
        const val RUN_ID = "run-native-document"
        val dryRun = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = false)
    }
}
