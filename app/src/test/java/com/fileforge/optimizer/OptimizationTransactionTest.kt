package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class OptimizationTransactionTest {
    @Test
    fun acceptedVerifiedGainWritesAndReopensVerifiedBackupBeforeReplacingAndLogging() = withCoordinator { gateway, coordinator, undo ->
        gateway.maximumTransferRequest = 32 * 1024
        val outcome = coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)

        expectType<FileOutcome.Optimized>(outcome)
        gateway.events.assertOrdered(
            "candidate-verified",
            "mkdir:root:FileForge_Backups_run-1",
            "create-file:root/FileForge_Backups_run-1:archive.zip",
            "write:root/FileForge_Backups_run-1/archive.zip",
            "read:root/FileForge_Backups_run-1/archive.zip",
            "write:root/archive.zip",
            "read:root/archive.zip",
            "undo-entry-appended-and-flushed"
        )
        assertEquals(1, undo.entries.size)
        assertEquals("archive.zip", undo.entries.single().relativePath)
        assertEquals("root/archive.zip", undo.entries.single().originalDocumentId)
        assertEquals(original.sha256(), undo.entries.single().originalSha256)
        assertEquals(candidate.sha256(), undo.entries.single().optimizedSha256)
        assertEquals(candidate.toList(), gateway.contents("archive.zip").toList())
    }

    @Test
    fun backupCreationWriteOrVerificationFailureNeverWritesOriginalOrAppendsUndo() {
        listOf(
            "mkdir:root:FileForge_Backups_run-1",
            "write:root/FileForge_Backups_run-1/archive.zip",
            "read:root/FileForge_Backups_run-1/archive.zip"
        ).forEach { failingEvent ->
            withCoordinator { gateway, coordinator, undo ->
                gateway.fail = { event -> if (event == failingEvent) IOException("$event failed") else null }

                val outcome = coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)

                expectType<FileOutcome.Failed>(outcome)
                assertFalse("original was written after $failingEvent", gateway.events.contains("write:root/archive.zip"))
                assertTrue("undo was appended after $failingEvent", undo.entries.isEmpty())
                assertEquals(null, gateway.node("FileForge_Backups_run-1/archive.zip"))
                assertEquals(null, gateway.node("FileForge_Backups_run-1"))
            }
        }
    }

    @Test
    fun originalWriteOrVerificationFailureImmediatelyRollsBackFromVerifiedBackupAndExposesRollbackResult() {
        listOf("write:root/archive.zip", "read:root/archive.zip").forEach { failingEvent ->
            withCoordinator { gateway, coordinator, undo ->
                var failed = false
                gateway.fail = { event ->
                    if (event == failingEvent && !failed &&
                        (failingEvent != "read:root/archive.zip" || gateway.events.contains("write:root/archive.zip"))
                    ) IOException("$event failed").also { failed = true } else null
                }

                val outcome = coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)

                val failure = expectType<FileOutcome.Failed>(outcome)
                expectType<RollbackResult.Restored>(failure.rollback)
                gateway.events.assertOrdered(
                    "read:root/FileForge_Backups_run-1/archive.zip",
                    failingEvent,
                    "write:root/archive.zip",
                    "read:root/archive.zip"
                )
                assertEquals(original.toList(), gateway.contents("archive.zip").toList())
                assertTrue(undo.entries.isEmpty())
            }
        }
    }

    @Test
    fun rollbackFailureEscalatesWhenOriginalMutationCannotBeRepaired() = withCoordinator { gateway, coordinator, undo ->
        var originalWrites = 0
        gateway.corruptAfterWrite = { it.id == "root/archive.zip" }
        gateway.fail = { event ->
            if (event == "write:root/archive.zip" && ++originalWrites == 2) IOException("rollback write failed") else null
        }

        val failure = assertThrows(RunInvariantException::class.java) {
            coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
        }

        assertTrue(failure.message!!.contains("rollback", ignoreCase = true))
        assertTrue(failure.cause!!.message!!.contains("rollback write failed"))
        assertTrue(undo.entries.isEmpty())
    }

    @Test
    fun undoAppendFailureAfterVerifiedOriginalRollsBackAndLeavesNoUnloggedReplacement() = withCoordinator { gateway, coordinator, undo ->
        undo.failure = IOException("undo append failed")

        val failure = assertThrows(UndoDurabilityException::class.java) {
            coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
        }
        assertEquals(RollbackResult.Restored, failure.rollback)
        assertEquals(original.toList(), gateway.contents("archive.zip").toList())
        assertEquals(2, gateway.events.count { it == "read:root/FileForge_Backups_run-1/archive.zip" })
        assertTrue(undo.entries.isEmpty())
    }

    @Test
    fun noGainDryRunOrCandidateFailureDoesNotMutateSelectedTreeAndNeverCreatesBackup() {
        listOf(
            Triple(original, null, realRun),
            Triple(candidate, null, dryRun),
            Triple(candidate, IOException("candidate verification failed"), realRun)
        ).forEach { (producedCandidate, candidateFailure, run) ->
            withCoordinator(candidateBytes = producedCandidate, verificationFailure = candidateFailure) { gateway, coordinator, undo ->
                val outcome = coordinator.process(gateway.node("archive.zip")!!, "archive.zip", run, NeverCancelled)

                assertFalse(outcome is FileOutcome.Optimized)
                assertTrue(gateway.events.none { it.startsWith("mkdir:") || it.startsWith("create-file:") || it.startsWith("write:") })
                assertTrue(undo.entries.isEmpty())
            }
        }
    }

    @Test
    fun cancellationDuringBackupPropagatesBeforeOriginalMutationAndDuringOriginalCopyRollsBack() {
        listOf("backup" to "write-bytes:root/FileForge_Backups_run-1/archive.zip:32768", "original" to "write-bytes:root/archive.zip:32768").forEach { (phase, event) ->
            withCoordinator { gateway, coordinator, _ ->
                var cancelled = false
                gateway.afterEvent = { if (it == event) cancelled = true }
                val token = CancellationToken { if (cancelled) throw OptimizationCancelledException("$phase cancelled") }

                assertThrows(OptimizationCancelledException::class.java) {
                    coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, token)
                }
                if (phase == "backup") {
                    assertFalse(gateway.events.any { it == "write:root/archive.zip" })
                    assertEquals(null, gateway.node("FileForge_Backups_run-1/archive.zip"))
                    assertEquals(null, gateway.node("FileForge_Backups_run-1"))
                } else {
                    assertEquals(original.toList(), gateway.contents("archive.zip").toList())
                }
            }
        }
    }

    private fun withCoordinator(
        candidateBytes: ByteArray = candidate,
        verificationFailure: Throwable? = null,
        block: (RecordingDocumentGateway, OptimizationCoordinator, RecordingUndoEntrySink) -> Unit
    ) {
        val cache = Files.createTempDirectory("fileforge-transaction").toFile()
        try {
            val gateway = RecordingDocumentGateway().apply {
                put("archive.zip", original)
                events.clear()
            }
            val undo = RecordingUndoEntrySink(gateway.events)
            val coordinator = OptimizationCoordinator(
                gateway,
                CandidateStore(cache, "run-1"),
                FixedCandidateProcessor(candidateBytes, gateway.events, verificationFailure),
                CommitContext(gateway.root, "run-1", undo) { "2026-08-13T20:00:00Z" }
            )
            block(gateway, coordinator, undo)
        } finally {
            cache.deleteRecursively()
        }
    }

    private companion object {
        val original = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(96 * 1024) { (it % 7).toByte() }
        val candidate = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(48 * 1024) { (it % 3).toByte() }
        val realRun = RunIntent(OptimizeMode.SAFE, dryRun = false, apkLabMode = false, textMinify = false)
        val dryRun = realRun.copy(dryRun = true)
    }
}
