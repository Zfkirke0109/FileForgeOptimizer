package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCoordinatorTest {
    @Test
    fun uppercaseV2UndoHashIsAcceptedAndRestoresAgainstLowercaseIntegrityDigest() = withRestore {
            _, coordinator, _, _ ->
        val uppercaseEntry = entry("docs/a.zip").copy(originalSha256 = backupA.sha256().uppercase())
        val undoText = java.io.StringWriter().also { writer ->
            UndoLogRepository().start(writer, UndoHeader("run-1", "2026-08-13T19:00:00Z"))
            UndoLogRepository().appendEntry(writer, uppercaseEntry)
        }.toString()
        val parsed = UndoLogRepository().read(undoText.reader())

        val report = coordinator.restore(parsed, RestoreSelection.All, NeverCancelled)

        assertEquals(backupA.sha256().uppercase(), parsed.entries.single().originalSha256)
        assertEquals(RestoreEntryStatus.RESTORED, report.entries.single().status)
    }

    @Test
    fun v2EntryStreamsVerifiedBackupToOriginalAndWritesTimestampedJsonlReceiptWithoutMutatingBackupOrUndo() = withRestore {
            gateway, coordinator, receipt, run ->
        val backupBefore = gateway.contents("FileForge_Backups_run-1/docs/a.zip")
        gateway.maximumTransferRequest = 32 * 1024
        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        val result = report.entries.single()
        assertEquals("docs/a.zip", result.relativePath)
        assertEquals(RestoreEntryStatus.RESTORED, result.status)
        assertEquals(UndoVerificationLevel.SHA_256, result.verification)
        assertEquals(backupBefore.toList(), gateway.contents("FileForge_Backups_run-1/docs/a.zip").toList())
        assertEquals(backupBefore.toList(), gateway.contents("docs/a.zip").toList())
        assertEquals("untouched", gateway.contents("FileForge_Undo_v2_run-1.jsonl").decodeToString())
        assertEquals("FileForge_Restore_run-1_20260813T200000Z.jsonl", receipt.names.single())
        assertEquals(receipt.names.single(), report.receiptName)
        assertTrue(receipt.contents.single().trim().startsWith("{"))
        assertEquals(run, report.run)
    }

    @Test
    fun preWriteBackupHashMismatchReturnsFailureWithoutOriginalWriteOrReceipt() = withRestore { gateway, coordinator, receipt, run ->
        gateway.put("FileForge_Backups_run-1/docs/a.zip", byteArrayOf(9, 9, 9, 9, 9, 9))

        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(RestoreEntryStatus.BACKUP_HASH_MISMATCH, report.entries.single().status)
        assertTrue(gateway.events.none { it == "write:root/docs/a.zip" })
        assertTrue(receipt.names.isEmpty())
    }

    @Test
    fun missingBackupAndUnsafeRelativeOrBackupPathsAreRejectedBeforeAnyRestoreAction() {
        listOf(
            entry(relativePath = "missing.zip", backupPath = "FileForge_Backups_run-1/missing.zip"),
            entry(relativePath = "../escape.zip"),
            entry(relativePath = "/absolute.zip"),
            entry(relativePath = "safe.zip", backupPath = "FileForge_Backups_run-1/../safe.zip"),
            entry(relativePath = "safe.zip", backupPath = "/outside/safe.zip")
        ).forEach { unsafe ->
            withRestore(entries = listOf(unsafe)) { gateway, coordinator, receipt, run ->
                if (unsafe.relativePath == "missing.zip") gateway.put("missing.zip", byteArrayOf(1))
                val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

                assertTrue(report.entries.single().status in setOf(
                    RestoreEntryStatus.BACKUP_MISSING,
                    RestoreEntryStatus.PATH_REJECTED
                ))
                assertTrue(gateway.events.none { it.startsWith("write:root/") && !it.contains("FileForge_Backups") })
                assertTrue(receipt.names.isEmpty())
            }
        }
    }

    @Test
    fun selectedSubsetRestoresOnlyRequestedEntriesAndResultIsUiUsable() = withRestore(entries = listOf(entry("docs/a.zip"), entry("docs/b.zip"))) {
            gateway, coordinator, receipt, run ->
        gateway.put("docs/b.zip", byteArrayOf(7))
        gateway.put("FileForge_Backups_run-1/docs/b.zip", backupB)
        val report = coordinator.restore(run, RestoreSelection.Entries(setOf("docs/b.zip")), NeverCancelled)

        assertEquals(listOf("docs/b.zip"), report.entries.map { it.relativePath })
        assertEquals(backupB.toList(), gateway.contents("docs/b.zip").toList())
        assertEquals(byteArrayOf(9).toList(), gateway.contents("docs/a.zip").toList())
        assertEquals(1, report.restoredCount)
        assertEquals(1, receipt.names.size)
    }

    @Test
    fun perEntryProgressStartsAtZeroAndPublishesEverySelectedResult() {
        val progress = mutableListOf<RestoreProgressSnapshot>()
        withRestore(
            entries = listOf(entry("docs/a.zip"), entry("docs/b.zip")),
            onProgress = progress::add
        ) { gateway, coordinator, _, run ->
            gateway.put("docs/b.zip", byteArrayOf(7))
            gateway.put("FileForge_Backups_run-1/docs/b.zip", backupB)

            val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

            assertEquals(2, report.restoredCount)
            assertEquals(listOf(0, 1, 2), progress.map { it.processedEntries })
            assertTrue(progress.all { it.totalEntries == 2 })
            assertEquals(listOf(0, 1, 2), progress.map { it.restoredEntries })
            assertEquals(
                listOf(null, "docs/a.zip", "docs/b.zip"),
                progress.map { it.lastResult?.relativePath }
            )
        }
    }

    @Test
    fun oneEntryFailureDoesNotStopLaterEntriesAndReceiptIncludesOnlyRealWrites() = withRestore(entries = listOf(entry("docs/a.zip"), entry("docs/b.zip"))) {
            gateway, coordinator, receipt, run ->
        gateway.put("FileForge_Backups_run-1/docs/b.zip", backupB)
        gateway.put("docs/b.zip", byteArrayOf(7))
        gateway.fail = { event -> if (event == "write:root/docs/a.zip") IllegalStateException("provider write failed") else null }

        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(listOf(RestoreEntryStatus.WRITE_FAILED, RestoreEntryStatus.RESTORED), report.entries.map { it.status })
        assertEquals(backupB.toList(), gateway.contents("docs/b.zip").toList())
        assertEquals(1, receipt.names.size)
        assertTrue(receipt.contents.single().contains("docs/a.zip"))
        assertTrue(receipt.contents.single().contains("docs/b.zip"))
    }

    @Test
    fun validLegacyEntryUsesSizeOnlyVerificationAndRepeatedRestoreIsIdempotent() = withRestore(
        entries = listOf(entry("docs/a.zip").copy(
            originalSha256 = null,
            optimizedSha256 = null,
            verificationLevel = UndoVerificationLevel.LEGACY_SIZE_ONLY
        ))
    ) { gateway, coordinator, receipt, run ->
        val first = coordinator.restore(run, RestoreSelection.All, NeverCancelled)
        val second = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(RestoreEntryStatus.RESTORED, first.entries.single().status)
        assertEquals(UndoVerificationLevel.LEGACY_SIZE_ONLY, first.entries.single().verification)
        assertEquals(RestoreEntryStatus.RESTORED, second.entries.single().status)
        assertEquals(2, receipt.names.size)
        assertEquals(backupA.toList(), gateway.contents("docs/a.zip").toList())
    }

    @Test
    fun cancellationBetweenEntriesStopsBeforeNextActionAndDoesNotCreateAnEmptyReceipt() = withRestore(entries = listOf(entry("docs/a.zip"), entry("docs/b.zip"))) {
            gateway, coordinator, receipt, run ->
        gateway.put("FileForge_Backups_run-1/docs/b.zip", backupB)
        var cancelled = false
        gateway.afterEvent = { event -> if (event == "write-closed:root/docs/a.zip") cancelled = true }
        val cancellation = CancellationToken {
            if (cancelled) throw OptimizationCancelledException()
        }

        val report = coordinator.restore(run, RestoreSelection.All, cancellation)

        assertEquals(RunStatus.CANCELLED, report.status)
        assertEquals(listOf("docs/a.zip"), report.entries.map { it.relativePath })
        assertFalse(gateway.events.any { it == "write:root/docs/b.zip" })
        assertEquals(1, receipt.names.size)
    }

    @Test
    fun restoreRejectsForeignOrArbitraryInRootBackupForV2AndLegacyBeforeAnyWrite() {
        listOf(
            entry("docs/a.zip", "FileForge_Backups_other-run/docs/a.zip"),
            entry("docs/a.zip", "unrelated/same-sized.zip"),
            entry("docs/a.zip", "FileForge_Backups_other-run/docs/a.zip").copy(
                originalSha256 = null, optimizedSha256 = null, verificationLevel = UndoVerificationLevel.LEGACY_SIZE_ONLY
            )
        ).forEach { forged ->
            withRestore(entries = listOf(forged)) { gateway, coordinator, receipt, run ->
                gateway.put("FileForge_Backups_other-run/docs/a.zip", backupA)
                gateway.put("unrelated/same-sized.zip", backupA)

                val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

                assertEquals(RestoreEntryStatus.PATH_REJECTED, report.entries.single().status)
                assertFalse(gateway.events.any { it == "write:root/docs/a.zip" })
                assertTrue(receipt.names.isEmpty())
            }
        }
    }

    @Test
    fun restoreRejectsDirectoryOriginalOrBackupBeforeReadWriteOrReceipt() {
        listOf(
            entry("docs/original-dir") to "docs/original-dir",
            entry("docs/backup-dir") to "FileForge_Backups_run-1/docs/backup-dir"
        ).forEach { (directoryEntry, directoryPath) ->
            withRestore(entries = listOf(directoryEntry)) { gateway, coordinator, receipt, run ->
                gateway.put(directoryEntry.relativePath, backupA)
                gateway.putDirectory(directoryPath)

                val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

                assertEquals(RestoreEntryStatus.DIRECTORY_REJECTED, report.entries.single().status)
                assertFalse(gateway.events.any { it == "write:root/${directoryEntry.relativePath}" })
                assertTrue(receipt.names.isEmpty())
            }
        }
    }

    @Test
    fun receiptUsesAnExclusiveUniqueNameForRepeatedSameTickRestore() = withRestore { _, coordinator, receipt, run ->
        val first = coordinator.restore(run, RestoreSelection.All, NeverCancelled)
        val second = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(RestoreEntryStatus.RESTORED, first.entries.single().status)
        assertEquals(RestoreEntryStatus.RESTORED, second.entries.single().status)
        assertEquals("FileForge_Restore_run-1_20260813T200000Z.jsonl", receipt.names.first())
        assertEquals("FileForge_Restore_run-1_20260813T200000Z-1.jsonl", receipt.names.last())
    }

    @Test
    fun unsafeRunIdOrTimestampNeverOpensReceiptOrWritesOriginal() {
        listOf("../run" to "20260813T200000Z", "run-1" to "../time").forEach { (runId, timestamp) ->
            withRestore(runId = runId, timestamp = timestamp) { gateway, coordinator, receipt, run ->
                val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

                assertEquals(RestoreEntryStatus.PATH_REJECTED, report.entries.single().status)
                assertFalse(gateway.events.any { it == "write:root/docs/a.zip" })
                assertTrue(receipt.names.isEmpty())
            }
        }
    }

    @Test
    fun receiptOpenFailurePreventsOriginalWrite() = assertReceiptFailure("open", originalWasWritten = false)

    @Test
    fun receiptWriteFailureRetainsCompletedRestoreReport() = assertReceiptFailure("write", originalWasWritten = true)

    @Test
    fun receiptFlushFailureRetainsCompletedRestoreReport() = assertReceiptFailure("flush", originalWasWritten = true)

    @Test
    fun receiptCloseFailureRetainsCompletedRestoreReport() = assertReceiptFailure("close", originalWasWritten = true)

    @Test
    fun cancellationDuringOriginalRestoreCopyRepairsCurrentEntryThenStops() = withRestore(entries = listOf(entry("docs/a.zip"), entry("docs/b.zip"))) {
            gateway, coordinator, receipt, run ->
        gateway.put("docs/b.zip", byteArrayOf(7))
        gateway.put("FileForge_Backups_run-1/docs/b.zip", backupB)
        var cancelled = false
        gateway.afterEvent = { if (it.startsWith("write-bytes:root/docs/a.zip")) cancelled = true }
        val token = CancellationToken { if (cancelled) throw OptimizationCancelledException() }

        val report = coordinator.restore(run, RestoreSelection.All, token)

        assertEquals(RunStatus.CANCELLED, report.status)
        assertEquals(listOf("docs/a.zip"), report.entries.map { it.relativePath })
        assertEquals(backupA.toList(), gateway.contents("docs/a.zip").toList())
        assertFalse(gateway.events.any { it == "write:root/docs/b.zip" })
        assertEquals(1, receipt.names.size)
    }

    @Test
    fun ordinaryPostWriteVerificationFailureRepairsFromBackupAndExposesRepairResult() = withRestore { gateway, coordinator, _, run ->
        var originalWrites = 0
        gateway.corruptAfterWrite = { it.id == "root/docs/a.zip" && ++originalWrites == 1 }

        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        val result = report.entries.single()
        assertEquals(RestoreEntryStatus.RESTORED_VERIFICATION_FAILED, result.status)
        assertEquals(RestoreRepairResult.Restored, result.repair)
        assertEquals(backupA.toList(), gateway.contents("docs/a.zip").toList())
    }

    @Test
    fun receiptContainsOnlyEntriesWhoseOriginalWriteWasAttempted() = withRestore(entries = listOf(entry("docs/a.zip"), entry("docs/b.zip"))) {
            gateway, coordinator, receipt, run ->
        gateway.put("docs/b.zip", byteArrayOf(7))

        coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(1, receipt.contents.single().lineSequence().count { it.isNotBlank() })
        assertTrue(receipt.contents.single().contains("docs/a.zip"))
        assertFalse(receipt.contents.single().contains("docs/b.zip"))
    }

    @Test
    fun legacyReceiptWriterRemainsSamCompatibleButCannotAuthorizeMutation() {
        var opened = false
        val legacy: RestoreReceiptWriter = RestoreReceiptWriter { opened = true; java.io.StringWriter() }
        withRestore(receiptWriter = legacy) { gateway, coordinator, _, run ->
            val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

            assertEquals(RestoreEntryStatus.RECEIPT_FAILED, report.entries.single().status)
            assertFalse(opened)
            assertFalse(gateway.events.any { it == "write:root/docs/a.zip" })
        }
    }

    @Test
    fun freshCoordinatorNeverOverwritesExistingExclusiveReceiptAndRetriesSuffix() {
        val receipts = RecordingReceiptWriter()
        withRestore(receiptWriter = receipts) { gateway, _, _, run ->
            val first = RestoreCoordinator(gateway, gateway.root, receipts) { "20260813T200000Z" }
            val second = RestoreCoordinator(gateway, gateway.root, receipts) { "20260813T200000Z" }

            assertEquals(RestoreEntryStatus.RESTORED, first.restore(run, RestoreSelection.All, NeverCancelled).entries.single().status)
            assertEquals(RestoreEntryStatus.RESTORED, second.restore(run, RestoreSelection.All, NeverCancelled).entries.single().status)
            assertEquals(
                listOf("FileForge_Restore_run-1_20260813T200000Z.jsonl", "FileForge_Restore_run-1_20260813T200000Z-1.jsonl"),
                receipts.names
            )
        }
    }

    @Test
    fun malformedUtf16PathIsRejectedBeforeReceiptOrWrite() = withRestore(entries = listOf(entry("docs/\uD800.zip"))) {
            gateway, coordinator, receipt, run ->
        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(RestoreEntryStatus.PATH_REJECTED, report.entries.single().status)
        assertFalse(gateway.events.any { it.startsWith("write:root/docs/") })
        assertTrue(receipt.names.isEmpty())
    }

    private fun withRestore(
        entries: List<UndoEntry> = listOf(entry("docs/a.zip")),
        receiptWriter: RestoreReceiptWriter = RecordingReceiptWriter(),
        runId: String = "run-1",
        timestamp: String = "20260813T200000Z",
        onProgress: (RestoreProgressSnapshot) -> Unit = {},
        block: (RecordingDocumentGateway, RestoreCoordinator, RecordingReceiptWriter, UndoRun) -> Unit
    ) {
        val gateway = RecordingDocumentGateway().apply {
            put("docs/a.zip", byteArrayOf(9))
            put("FileForge_Backups_run-1/docs/a.zip", backupA)
            put("FileForge_Undo_v2_run-1.jsonl", "untouched".toByteArray())
            events.clear()
        }
        val recording = receiptWriter as? RecordingReceiptWriter ?: RecordingReceiptWriter()
        val run = UndoRun(UndoHeader(runId, "2026-08-13T19:00:00Z"), entries, RunStatus.COMPLETED)
        val coordinator = RestoreCoordinator(
            documentGateway = gateway,
            selectedRoot = gateway.root,
            receiptWriter = receiptWriter,
            onProgress = onProgress,
            clock = { timestamp }
        )
        block(gateway, coordinator, recording, run)
    }

    private fun assertReceiptFailure(phase: String, originalWasWritten: Boolean) {
        withRestore(receiptWriter = FaultingReceiptWriter(phase)) { gateway, coordinator, _, run ->
            val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

            if (!originalWasWritten) assertFalse(gateway.events.any { it == "write:root/docs/a.zip" })
            else assertEquals(backupA.toList(), gateway.contents("docs/a.zip").toList())
            assertTrue("receipt phase=$phase error=${report.receiptError}", report.receiptError?.contains(phase) == true)
        }
    }

    private fun entry(
        relativePath: String,
        backupPath: String = "FileForge_Backups_run-1/$relativePath"
    ) = UndoEntry(
        relativePath = relativePath,
        originalBytes = if (relativePath.endsWith("b.zip")) backupB.size.toLong() else backupA.size.toLong(),
        optimizedBytes = 1,
        backupPath = backupPath,
        originalSha256 = if (relativePath.endsWith("b.zip")) backupB.sha256() else backupA.sha256(),
        optimizedSha256 = "0".repeat(64),
        note = "transaction",
        fileKind = FileKind.ZIP_LIKE,
        toolId = "streaming",
        completedAt = "2026-08-13T19:01:00Z"
    )

    private companion object {
        val backupA = byteArrayOf(0x50, 0x4b, 1, 2, 3, 4)
        val backupB = byteArrayOf(0x50, 0x4b, 5, 6, 7, 8)
    }

    private class FaultingReceiptWriter(private val phase: String) : ExclusiveRestoreReceiptWriter {
        override fun openExclusive(name: String): RestoreReceipt {
            if (phase == "open") error("open failed")
            return RestoreReceipt(name, object : java.io.StringWriter() {
                override fun write(cbuf: CharArray, off: Int, len: Int) {
                    if (phase == "write") error("write failed")
                    super.write(cbuf, off, len)
                }
                override fun write(str: String, off: Int, len: Int) {
                    if (phase == "write") error("write failed")
                    super.write(str, off, len)
                }
                override fun write(str: String) {
                    if (phase == "write") error("write failed")
                    super.write(str)
                }
                override fun flush() {
                    if (phase == "flush") error("flush failed")
                    super.flush()
                }
                override fun close() {
                    if (phase == "close") error("close failed")
                    super.close()
                }
            })
        }
    }
}
