package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ByteArrayOptimizerAdapterHardeningTest {
    @Test
    fun overreportedSmallInputIsProcessedUsingActualBytes() {
        val gateway = RecordingDocumentGateway()
        val original = "%PDF-1.4\n%%EOF\ntrailing".toByteArray()
        val node = gateway.put("document.pdf", original)
        val dishonest = LengthOverrideGateway(gateway, reportedLength = 50_000)
        val adapter = ByteArrayOptimizerAdapter(dishonest, maxInputBytes = 64)

        val outcome = expectType<FileOutcome.WouldOptimize>(
            adapter.process(node, "document.pdf", FileKind.PDF, dryRun, NeverCancelled)
        )

        assertEquals(original.size.toLong(), outcome.oldBytes)
        assertTrue(outcome.newBytes < outcome.oldBytes)
    }

    @Test
    fun underreportedInputBeyondLimitStopsAtBoundedReadAndSkips() {
        val gateway = RecordingDocumentGateway().apply { maximumTransferRequest = 32 * 1024 }
        val node = gateway.put("document.pdf", "%PDF-1.4\n%%EOF\n".toByteArray() + ByteArray(256))
        gateway.events.clear()
        val dishonest = LengthOverrideGateway(gateway, reportedLength = 1)
        val adapter = ByteArrayOptimizerAdapter(dishonest, maxInputBytes = 64)

        val outcome = expectType<FileOutcome.Skipped>(
            adapter.process(node, "document.pdf", FileKind.PDF, dryRun, NeverCancelled)
        )

        assertEquals(SkipReason.MEMORY_LIMIT, outcome.reason)
        assertTrue(gateway.events.none { it.startsWith("write:") || it.startsWith("create-file:") || it.startsWith("mkdir:") })
    }

    @Test
    fun durableUndoFailureAfterNonZipReplacementReturnsObservableRollbackAndRestoresOriginal() {
        val gateway = RecordingDocumentGateway()
        val original = "%PDF-1.4\n%%EOF\ntrailing".toByteArray()
        val node = gateway.put("document.pdf", original)
        gateway.events.clear()
        val adapter = ByteArrayOptimizerAdapter(
            gateway,
            CommitContext(gateway.root, "run-1", UndoEntrySink { throw IOException("undo flush failed") }) {
                "2026-08-13T20:00:00Z"
            }
        )

        val failure = expectType<FileOutcome.Failed>(
            adapter.process(node, "document.pdf", FileKind.PDF, realRun, NeverCancelled)
        )

        assertEquals(RollbackResult.Restored, failure.rollback)
        assertEquals(original.toList(), gateway.contents("document.pdf").toList())
    }

    private class LengthOverrideGateway(
        private val delegate: DocumentGateway,
        private val reportedLength: Long
    ) : DocumentGateway by delegate {
        override fun length(node: DocumentNode): Long = reportedLength
    }

    private companion object {
        val dryRun = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = true)
        val realRun = dryRun.copy(dryRun = false)
    }
}
