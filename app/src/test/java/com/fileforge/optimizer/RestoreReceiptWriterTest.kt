package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RestoreReceiptWriterTest {
    @Test
    fun productionReceiptWriterTranslatesExactCreateCollisionSoRestoreRetriesWithSuffix() {
        val gateway = RecordingDocumentGateway().apply {
            put("docs/a.zip", byteArrayOf(9))
            put("FileForge_Backups_run-1/docs/a.zip", backup)
            put("FileForge_Restore_run-1_20260813T200000Z.jsonl", "existing receipt".toByteArray())
            events.clear()
        }
        val writer = DocumentGatewayRestoreReceiptWriter(gateway, gateway.root)
        val coordinator = RestoreCoordinator(gateway, gateway.root, writer) { "20260813T200000Z" }
        val run = UndoRun(
            UndoHeader("run-1", "2026-08-13T19:00:00Z"),
            listOf(
                UndoEntry(
                    relativePath = "docs/a.zip",
                    originalBytes = backup.size.toLong(),
                    optimizedBytes = 1,
                    backupPath = "FileForge_Backups_run-1/docs/a.zip",
                    originalSha256 = backup.sha256(),
                    optimizedSha256 = byteArrayOf(9).sha256(),
                    note = "transaction",
                    originalDocumentId = "root/docs/a.zip"
                )
            ),
            RunStatus.COMPLETED
        )

        val report = coordinator.restore(run, RestoreSelection.All, NeverCancelled)

        assertEquals(RestoreEntryStatus.RESTORED, report.entries.single().status)
        assertEquals("existing receipt", gateway.contents("FileForge_Restore_run-1_20260813T200000Z.jsonl").decodeToString())
        assertTrue(gateway.contents("FileForge_Restore_run-1_20260813T200000Z-1.jsonl").decodeToString().contains("docs/a.zip"))
    }

    @Test
    fun productionReceiptWriterDoesNotRelabelNonCollisionCreationFailure() {
        val failure = IOException("provider storage is offline")
        val delegate = RecordingDocumentGateway()
        val failingGateway = object : DocumentGateway by delegate {
            override fun createFileExact(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
                throw failure
            }
        }
        val writer = DocumentGatewayRestoreReceiptWriter(failingGateway, delegate.root)

        val thrown = assertThrows(IOException::class.java) {
            writer.openExclusive("receipt.jsonl")
        }

        assertSame(failure, thrown)
    }

    private companion object {
        val backup = byteArrayOf(0x50, 0x4b, 1, 2, 3, 4)
    }
}
