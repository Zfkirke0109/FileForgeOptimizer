package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guards the Restore destination's read-only, selected-root-only discovery boundary.
 */
class RestoreLogDiscoveryTest {
    @Test
    fun discoversOnlyDirectRecognizedNonEmptyUndoLogsWithoutWritingDocuments() {
        val gateway = RecordingDocumentGateway().apply {
            put("FileForge_Undo_v2_verified-run.jsonl", v2Log("verified-run", "docs/photo.jpg", 4_096, 2_048))
            put("FileForge_Undo_20260813_121314.txt", legacyLog("20260813_121314", "music/song.mp3", 8_192, 4_096))
            put("FileForge_Undo_v2_wrong-extension.txt", v2Log("wrong-extension", "ignore.txt", 1, 1))
            put("notes/FileForge_Undo_v2_nested.jsonl", v2Log("nested", "ignore.txt", 1, 1))
            put("not-an-undo-log.jsonl", v2Log("unrecognized", "ignore.txt", 1, 1))
            events.clear()
        }

        val result = RestoreLogDiscovery(gateway, gateway.root).discover()

        assertEquals(
            listOf("FileForge_Undo_v2_verified-run.jsonl", "FileForge_Undo_20260813_121314.txt"),
            result.runs.map { it.undoLogId }
        )
        assertEquals(
            listOf(UndoVerificationLevel.SHA_256, UndoVerificationLevel.LEGACY_SIZE_ONLY),
            result.runs.map { it.run.entries.single().verificationLevel }
        )
        assertTrue(result.failures.isEmpty())
        assertFalse(gateway.events.any { it.startsWith("write:") || it.startsWith("create-file:") || it.startsWith("mkdir:") })
        assertFalse(gateway.events.any { it.contains("wrong-extension") || it.contains("nested") || it.contains("not-an-undo-log") })
    }

    @Test
    fun reportsRecognizedMalformedAndEmptyLogsIndividuallyWhileKeepingValidRunsAvailable() {
        val gateway = RecordingDocumentGateway().apply {
            put("FileForge_Undo_v2_good.jsonl", v2Log("good", "docs/keep.txt", 9, 3))
            put("FileForge_Undo_v2_empty.jsonl", v2Header("empty"))
            put("FileForge_Undo_broken.txt", "not a FileForge undo log".encodeToByteArray())
            events.clear()
        }

        val result = RestoreLogDiscovery(gateway, gateway.root).discover()

        assertEquals(listOf("FileForge_Undo_v2_good.jsonl"), result.runs.map { it.undoLogId })
        assertEquals(
            setOf("FileForge_Undo_v2_empty.jsonl", "FileForge_Undo_broken.txt"),
            result.failures.map { it.undoLogId }.toSet()
        )
        assertFalse(gateway.events.any { it.startsWith("write:") || it.startsWith("create-file:") || it.startsWith("mkdir:") })
    }

    @Test
    fun strictDiscoveryRejectsAnOtherwiseValidV2LogWithOneMalformedRecord() {
        val gateway = RecordingDocumentGateway().apply {
            put(
                "FileForge_Undo_v2_partially-malformed.jsonl",
                (v2Header("partially-malformed") + "\n" +
                    "{\"schemaVersion\":2,\"recordType\":\"entry\"}\n" +
                    v2Log("partially-malformed", "docs/valid.txt", 4, 2).decodeToString()
                        .substringAfter('\n')).encodeToByteArray()
            )
            events.clear()
        }

        val result = RestoreLogDiscovery(gateway, gateway.root).discover()

        assertTrue(result.runs.isEmpty())
        assertEquals(listOf("FileForge_Undo_v2_partially-malformed.jsonl"), result.failures.map { it.undoLogId })
    }

    @Test
    fun strictStreamingReadHonorsCancellationAndLineCeilingWithoutMaterializingTheWholeLog() {
        val repository = UndoLogRepository()
        val cancelled = CancellationToken { throw OptimizationCancelledException("test cancellation") }

        try {
            repository.readStrictForRestore(StringReader(v2Header("cancelled") + "\n"), cancelled)
            throw AssertionError("Expected cancellation")
        } catch (_: OptimizationCancelledException) {
            // The reader is checked before consuming the first record.
        }
        try {
            repository.readStrictForRestore(StringReader("x".repeat(UndoLogRepository.RESTORE_MAX_LINE_CHARS + 1)))
            throw AssertionError("Expected an oversized-record rejection")
        } catch (_: IllegalArgumentException) {
            // Bounded restore parsing rejects before allocating an unbounded record.
        }
    }

    @Test
    fun closingDiscoveryPromptlyClosesAnActiveRecognizedLogStream() {
        val delegate = RecordingDocumentGateway()
        val stream = BlockingInputStream()
        val log = DocumentNode("blocked", "FileForge_Undo_v2_blocked.jsonl", isDirectory = false, length = 0)
        val gateway = object : DocumentGateway by delegate {
            override fun list(node: DocumentNode): List<DocumentNode> = listOf(log)
            override fun openRead(node: DocumentNode): InputStream = stream
        }
        val discovery = RestoreLogDiscovery(gateway, delegate.root)
        val worker = Thread {
            try {
                discovery.discover()
            } catch (_: OptimizationCancelledException) {
                // Closing the screen cancels this read rather than waiting for user-controlled bytes.
            }
        }
        worker.start()
        assertTrue(stream.started.await(1, TimeUnit.SECONDS))

        discovery.close()

        assertTrue("active stream was not closed", stream.closed.await(1, TimeUnit.SECONDS))
        worker.join(1_000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun cardProjectionShowsRunScopeVerificationAndSaturatesRecoverableBytes() {
        val run = UndoRun(
            header = UndoHeader("overflow-run", "2026-08-13T19:42:00Z"),
            entries = listOf(
                entry("photos/a.jpg", Long.MAX_VALUE, 7),
                entry("photos/b.jpg", 1, 1)
            ),
            status = RunStatus.COMPLETED_WITH_ERRORS
        )

        val card = RestoreRunCard.from("FileForge_Undo_v2_overflow-run.jsonl", run)

        assertEquals("overflow-run", card.runId)
        assertEquals("2026-08-13T19:42:00Z", card.runDate)
        assertEquals(RunStatus.COMPLETED_WITH_ERRORS, card.status)
        assertEquals(2, card.entryCount)
        assertEquals(Long.MAX_VALUE, card.recoverableBytes)
        assertEquals("SHA-256 verified", card.verificationLabel)
        assertEquals("photos/a.jpg", card.entries.first().relativePath)
        assertEquals(Long.MAX_VALUE, card.entries.first().originalBytes)
        assertEquals(7, card.entries.first().optimizedBytes)
        assertEquals("FileForge_Backups_overflow-run/photos/a.jpg", card.entries.first().backupPath)
        assertEquals("SHA-256 verified", card.entries.first().verificationLabel)
    }

    private fun entry(path: String, originalBytes: Long, optimizedBytes: Long) = UndoEntry(
        relativePath = path,
        originalBytes = originalBytes,
        optimizedBytes = optimizedBytes,
        backupPath = "FileForge_Backups_overflow-run/$path",
        originalSha256 = "a".repeat(64),
        optimizedSha256 = "b".repeat(64),
        note = "verified backup",
        fileKind = FileKind.JPEG,
        toolId = "image-optimizer",
        completedAt = "2026-08-13T19:43:00Z"
    )

    private fun v2Log(runId: String, path: String, originalBytes: Long, optimizedBytes: Long): ByteArray = (
        v2Header(runId) + "\n" +
            "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"$path\",\"originalBytes\":$originalBytes," +
            "\"optimizedBytes\":$optimizedBytes,\"backupPath\":\"FileForge_Backups_$runId/$path\"," +
            "\"originalSha256\":\"${"a".repeat(64)}\",\"optimizedSha256\":\"${"b".repeat(64)}\"," +
            "\"fileKind\":\"TEXT\",\"toolId\":\"test-tool\",\"verificationLevel\":\"SHA_256\"," +
            "\"note\":\"verified\",\"completedAt\":\"2026-08-13T19:43:00Z\"}\n" +
            "{\"schemaVersion\":2,\"recordType\":\"terminal\",\"status\":\"COMPLETED\",\"completedAt\":\"2026-08-13T19:44:00Z\"," +
            "\"entriesCommitted\":1,\"scanned\":1,\"optimized\":1,\"skipped\":0,\"errors\":0,\"savedBytes\":1," +
            "\"bytesRead\":1,\"bytesWritten\":1,\"potentialSavingsBytes\":1}\n"
        ).encodeToByteArray()

    private fun v2Header(runId: String): String =
        "{\"schemaVersion\":2,\"recordType\":\"header\",\"runId\":\"$runId\",\"startedAt\":\"2026-08-13T19:42:00Z\"," +
            "\"mode\":\"SAFE\",\"apkLabMode\":false,\"textMinify\":false,\"dryRun\":false," +
            "\"appVersion\":\"1.0\",\"buildVariant\":\"debug\",\"status\":\"RUNNING\"}"

    private fun legacyLog(stamp: String, path: String, originalBytes: Long, optimizedBytes: Long): ByteArray = """
        FileForge Undo Log $stamp
        Mode=OptimizerSettings(mode=SAFE, apkLabMode=false, textMinify=false)
        Format: relative_path | original_bytes | optimized_bytes | backup_path | note

        $path | $originalBytes | $optimizedBytes | FileForge_Backups_$stamp/$path | legacy backup
    """.trimIndent().plus("\n").encodeToByteArray()

    private class BlockingInputStream : InputStream() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun read(): Int {
            started.countDown()
            released.await(5, TimeUnit.SECONDS)
            return -1
        }

        override fun close() {
            closed.countDown()
            released.countDown()
        }
    }
}
