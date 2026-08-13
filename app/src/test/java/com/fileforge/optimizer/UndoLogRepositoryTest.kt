package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Writer

class UndoLogRepositoryTest {
    private val repository = UndoLogRepository()

    @Test
    fun v2RoundTripPreservesPathsWithSpacesUnicodeAndPipes() {
        val output = FlushRecordingWriter()
        val header = UndoHeader(runId = "run-東京", startedAt = "2026-08-13T19:42:00Z")
        val entry = UndoEntry(
            relativePath = "reports/東京 | final copy.zip",
            originalBytes = 4_096,
            optimizedBytes = 2_048,
            backupPath = "FileForge_Backups_run-東京/reports/東京 | final copy.zip",
            originalSha256 = "a".repeat(64),
            note = "streamed safely"
        )

        repository.start(output, header)
        repository.appendEntry(output, entry)
        repository.appendTerminal(
            output,
            UndoTerminalSummary(
                status = RunStatus.COMPLETED,
                completedAt = "2026-08-13T19:43:00Z",
                entriesCommitted = 1
            )
        )

        val run = repository.read(output.contents.reader())

        assertEquals(2, run.header.schemaVersion)
        assertEquals(header.runId, run.header.runId)
        assertEquals(listOf(entry), run.entries)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals("{\"schemaVersion\":2", output.contents.lineSequence().first().take(18))
    }

    @Test
    fun eachAppendedV2LineIsFlushedBeforeTheNextOperation() {
        val output = FlushRecordingWriter()

        repository.start(output, UndoHeader(runId = "flush-run", startedAt = "2026-08-13T19:42:00Z"))
        assertEquals(1, output.flushCount)
        assertEquals(1, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))

        repository.appendEntry(output, v2Entry(relativePath = "docs/one.txt"))
        assertEquals(2, output.flushCount)
        assertEquals(2, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))

        repository.appendTerminal(
            output,
            UndoTerminalSummary(RunStatus.COMPLETED, "2026-08-13T19:43:00Z", entriesCommitted = 1)
        )
        assertEquals(3, output.flushCount)
        assertEquals(3, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))
    }

    @Test
    fun interruptedV2LogKeepsCompletedEntriesRestorable() {
        val text = listOf(
            v2HeaderJson(runId = "interrupted-run"),
            v2EntryJson(relativePath = "finished/one.txt")
        ).joinToString("\n", postfix = "\n")

        val run = repository.read(text.reader())

        assertEquals(RunStatus.RUNNING, run.status)
        assertEquals(1, run.entries.size)
        assertEquals("finished/one.txt", run.entries.single().relativePath)
    }

    @Test
    fun invalidV2LinesAreIgnoredWithoutDiscardingValidCommittedEntries() {
        val text = listOf(
            v2HeaderJson(runId = "partially-corrupt-run"),
            v2EntryJson(relativePath = "finished/one.txt"),
            "{not valid json}",
            "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"missing-fields.txt\"}",
            v2EntryJson(relativePath = "finished/two.txt"),
            v2TerminalJson()
        ).joinToString("\n", postfix = "\n")

        val run = repository.read(text.reader())

        assertEquals(listOf("finished/one.txt", "finished/two.txt"), run.entries.map { it.relativePath })
        assertEquals(RunStatus.COMPLETED, run.status)
    }

    @Test
    fun v2SchemaDoesNotTreatPipeRecordsAsLegacyEntries() {
        val text = listOf(
            v2HeaderJson(runId = "v2-only"),
            "should not parse.txt | 100 | 50 | FileForge_Backups_v2-only/should not parse.txt | old record",
            v2EntryJson(relativePath = "actual/v2-entry.txt")
        ).joinToString("\n", postfix = "\n")

        val run = repository.read(text.reader())

        assertEquals(2, run.header.schemaVersion)
        assertEquals(listOf("actual/v2-entry.txt"), run.entries.map { it.relativePath })
        assertEquals(RunStatus.RUNNING, run.status)
    }

    @Test
    fun readsCurrentLegacyHeaderAndPipeRecordsAsSizeOnlyEntries() {
        val legacyLog = """
            FileForge Undo Log 20260813_121314
            Mode=OptimizerSettings(mode=SAFE, apkLabMode=false, textMinify=false)
            Format: relative_path | original_bytes | optimized_bytes | backup_path | note

            photos/holiday image.jpg | 4096 | 2048 | FileForge_Backups_20260813_121314/photos/holiday image.jpg | legacy optimizer result
        """.trimIndent() + "\n"

        val run = repository.read(legacyLog.reader())

        assertEquals(1, run.header.schemaVersion)
        assertEquals("20260813_121314", run.header.runId)
        assertEquals(1, run.entries.size)
        assertEquals("photos/holiday image.jpg", run.entries.single().relativePath)
        assertEquals(4_096, run.entries.single().originalBytes)
        assertEquals(2_048, run.entries.single().optimizedBytes)
        assertEquals(
            "FileForge_Backups_20260813_121314/photos/holiday image.jpg",
            run.entries.single().backupPath
        )
        assertEquals(UndoVerificationLevel.LEGACY_SIZE_ONLY, run.entries.single().verificationLevel)
        assertNull(run.entries.single().originalSha256)
        assertTrue(run.entries.single().note.contains("legacy optimizer result"))
    }

    private fun v2Entry(relativePath: String) = UndoEntry(
        relativePath = relativePath,
        originalBytes = 100,
        optimizedBytes = 50,
        backupPath = "FileForge_Backups_run/$relativePath",
        originalSha256 = "b".repeat(64),
        note = "verified"
    )

    private fun v2HeaderJson(runId: String) =
        "{\"schemaVersion\":2,\"recordType\":\"header\",\"runId\":\"$runId\",\"startedAt\":\"2026-08-13T19:42:00Z\",\"status\":\"RUNNING\"}"

    private fun v2EntryJson(relativePath: String) =
        "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"$relativePath\",\"originalBytes\":100,\"optimizedBytes\":50,\"backupPath\":\"FileForge_Backups_run/$relativePath\",\"originalSha256\":\"${"b".repeat(64)}\",\"verificationLevel\":\"SHA_256\",\"note\":\"verified\"}"

    private fun v2TerminalJson() =
        "{\"schemaVersion\":2,\"recordType\":\"terminal\",\"status\":\"COMPLETED\",\"completedAt\":\"2026-08-13T19:43:00Z\",\"entriesCommitted\":2}"

    private class FlushRecordingWriter : Writer() {
        private val buffer = StringBuilder()
        var flushCount = 0
            private set

        val contents: String get() = buffer.toString()

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            buffer.append(cbuf, off, len)
        }

        override fun flush() {
            flushCount++
        }

        override fun close() = Unit
    }
}
