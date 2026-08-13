package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Writer

class UndoLogRepositoryTest {
    private val repository = UndoLogRepository()

    @Test
    fun v2RoundTripPreservesPathsWithSpacesUnicodeAndPipes() {
        val output = FlushRecordingWriter()
        val header = UndoHeader(
            runId = "run-東京",
            startedAt = "2026-08-13T19:42:00Z",
            mode = OptimizeMode.AGGRESSIVE,
            apkLabMode = true,
            textMinify = true,
            dryRun = false,
            appVersion = "1.2.3",
            buildVariant = "release"
        )
        val entry = UndoEntry(
            relativePath = "reports/東京 | final copy.zip",
            originalBytes = 4_096,
            optimizedBytes = 2_048,
            backupPath = "FileForge_Backups_run-東京/reports/東京 | final copy.zip",
            originalSha256 = "a".repeat(64),
            optimizedSha256 = "b".repeat(64),
            fileKind = FileKind.ZIP_LIKE,
            toolId = "streaming-zip-v1",
            note = "streamed safely",
            completedAt = "2026-08-13T19:42:30Z"
        )

        repository.start(output, header)
        repository.appendEntry(output, entry)
        val terminal = UndoTerminalSummary(
            status = RunStatus.COMPLETED,
            completedAt = "2026-08-13T19:43:00Z",
            entriesCommitted = 1,
            scanned = 2,
            optimized = 1,
            skipped = 1,
            errors = 0,
            savedBytes = 2_048,
            bytesRead = 4_096,
            bytesWritten = 2_048,
            potentialSavingsBytes = 2_048
        )
        repository.appendTerminal(output, terminal)

        val run = repository.read(output.contents.reader())

        assertEquals(2, run.header.schemaVersion)
        assertEquals(header, run.header)
        assertEquals(listOf(entry), run.entries)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(terminal, run.terminal)
        assertEquals("{\"schemaVersion\":2", output.contents.lineSequence().first().take(18))
    }

    @Test
    fun eachAppendedV2LineIsFlushedBeforeTheNextOperation() {
        val output = FlushRecordingWriter()

        repository.start(output, UndoHeader(runId = "flush-run", startedAt = "2026-08-13T19:42:00Z"))
        assertEquals(1, output.flushCount)
        assertEquals(1, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))
        assertJsonObjectLines(output.contents)

        repository.appendEntry(output, v2Entry(relativePath = "docs/one.txt"))
        assertEquals(2, output.flushCount)
        assertEquals(2, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))
        assertJsonObjectLines(output.contents)

        repository.appendTerminal(
            output,
            UndoTerminalSummary(RunStatus.COMPLETED, "2026-08-13T19:43:00Z", entriesCommitted = 1)
        )
        assertEquals(3, output.flushCount)
        assertEquals(3, output.contents.lineSequence().count { it.isNotBlank() })
        assertTrue(output.contents.endsWith("\n"))
        assertJsonObjectLines(output.contents)
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

    @Test
    fun legacyPipeInRelativePathIsReconstructedOnlyWhenItsBackupMatchesExactly() {
        val stamp = "20260813_121314"
        val relativePath = "victim | 200"
        val legacyLog = legacyLog(
            stamp,
            "$relativePath | 100 | 50 | FileForge_Backups_$stamp/$relativePath | note"
        )

        val run = repository.read(legacyLog.reader())

        assertEquals(1, run.entries.size)
        assertEquals(relativePath, run.entries.single().relativePath)
        assertEquals("FileForge_Backups_$stamp/$relativePath", run.entries.single().backupPath)
    }

    @Test
    fun legacyPipeInBackupPathIsOmittedWhenItCannotMatchTheOriginalPath() {
        val stamp = "20260813_121314"
        val legacyLog = legacyLog(
            stamp,
            "safe.txt | 100 | 50 | FileForge_Backups_$stamp/safe | extra.txt | note"
        )

        assertTrue(repository.read(legacyLog.reader()).entries.isEmpty())
    }

    @Test
    fun legacyRowWithMultipleValidSeparatorInterpretationsIsOmitted() {
        val stamp = "20260813_121314"
        val backupRoot = "FileForge_Backups_$stamp"
        val legacyLog = legacyLog(
            stamp,
            "a | 1 | 2 | $backupRoot/a | 3 | 4 | $backupRoot/a | 1 | 2 | $backupRoot/a | note"
        )

        assertTrue(repository.read(legacyLog.reader()).entries.isEmpty())
    }

    @Test
    fun legacyPipeInNoteRemainsPartOfTheUniqueLegacyNote() {
        val stamp = "20260813_121314"
        val legacyLog = legacyLog(
            stamp,
            "safe.txt | 100 | 50 | FileForge_Backups_$stamp/safe.txt | first | second"
        )

        val entry = repository.read(legacyLog.reader()).entries.single()

        assertEquals("safe.txt", entry.relativePath)
        assertEquals("first | second", entry.note)
    }

    @Test
    fun terminalBeforeAnEntryLeavesTheRunRunningButRetainsTheEntry() {
        val text = listOf(v2HeaderJson("ordered"), v2TerminalJson(entriesCommitted = 0), v2EntryJson("after-terminal.txt"))
            .joinToString("\n", postfix = "\n")

        val run = repository.read(text.reader())

        assertEquals(RunStatus.RUNNING, run.status)
        assertEquals(listOf("after-terminal.txt"), run.entries.map { it.relativePath })
    }

    @Test
    fun duplicateTerminalLeavesTheRunRunning() {
        val text = listOf(v2HeaderJson("duplicate-terminal"), v2EntryJson("one.txt"), v2TerminalJson(1), v2TerminalJson(1))
            .joinToString("\n", postfix = "\n")

        assertEquals(RunStatus.RUNNING, repository.read(text.reader()).status)
    }

    @Test
    fun terminalCountMismatchLeavesTheRunRunning() {
        val text = listOf(v2HeaderJson("mismatch"), v2EntryJson("one.txt"), v2TerminalJson(entriesCommitted = 2))
            .joinToString("\n", postfix = "\n")

        assertEquals(RunStatus.RUNNING, repository.read(text.reader()).status)
    }

    @Test
    fun eachNonRunningTerminalStatusIsAcceptedWhenItIsFinalAndCounted() {
        RunStatus.entries.filter { it != RunStatus.RUNNING }.forEach { status ->
            val text = listOf(v2HeaderJson("status-${status.name}"), v2EntryJson("one.txt"), v2TerminalJson(1, status))
                .joinToString("\n", postfix = "\n")

            assertEquals(status, repository.read(text.reader()).status)
        }
    }

    @Test
    fun v2JsonRoundTripEscapesControlsQuotesSlashesBackslashesAndAstralUnicode() {
        val output = FlushRecordingWriter()
        val value = "quote\" slash/ backslash\\ controls:${(0..31).map(Int::toChar).joinToString("")} emoji:\uD83D\uDE00"
        val header = UndoHeader(runId = value, startedAt = "2026-08-13T19:42:00Z")
        val entry = v2Entry(relativePath = "$value/file.txt").copy(note = value)

        repository.start(output, header)
        repository.appendEntry(output, entry)

        val run = repository.read(output.contents.reader())

        assertEquals(value, run.header.runId)
        assertEquals(entry, run.entries.single())
    }

    @Test
    fun writerRejectsUnpairedSurrogates() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.start(FlushRecordingWriter(), UndoHeader(runId = "bad\uD800", startedAt = "2026-08-13T19:42:00Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendEntry(FlushRecordingWriter(), v2Entry("bad\uDC00.txt"))
        }
    }

    @Test
    fun readerRejectsUnpairedEscapedOrRawSurrogatesButAcceptsAValidEscapedPair() {
        val validPair = v2EntryJson("pair\\uD83D\\uDE00.txt")
        val badHigh = v2EntryJson("bad\\uD800.txt")
        val badLow = v2EntryJson("bad\\uDC00.txt")
        val rawBad = v2EntryJson("bad\uD800.txt")
        val text = listOf(v2HeaderJson("surrogates"), badHigh, badLow, rawBad, validPair).joinToString("\n", postfix = "\n")

        val run = repository.read(text.reader())

        assertEquals(listOf("pair\uD83D\uDE00.txt"), run.entries.map { it.relativePath })
    }

    @Test
    fun nonJsonWhitespaceDoesNotMakeAV2HeaderValid() {
        val text = v2HeaderJson("not-json-whitespace").replace("{", "{\u000B")

        val run = repository.read(text.reader())

        assertEquals(1, run.header.schemaVersion)
        assertTrue(run.entries.isEmpty())
    }

    @Test
    fun missingDurableAuditFieldsFailClosedForEachV2RecordType() {
        val incompleteHeader = "{\"schemaVersion\":2,\"recordType\":\"header\",\"runId\":\"incomplete\",\"startedAt\":\"now\",\"status\":\"RUNNING\"}"
        assertEquals(1, repository.read(incompleteHeader.reader()).header.schemaVersion)

        val incompleteEntry = "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"missing.txt\"}"
        val incompleteTerminal = "{\"schemaVersion\":2,\"recordType\":\"terminal\",\"status\":\"COMPLETED\",\"completedAt\":\"now\",\"entriesCommitted\":0}"
        val text = listOf(v2HeaderJson("incomplete-records"), incompleteEntry, incompleteTerminal).joinToString("\n")

        val run = repository.read(text.reader())

        assertTrue(run.entries.isEmpty())
        assertEquals(RunStatus.RUNNING, run.status)
    }

    private fun v2Entry(relativePath: String) = UndoEntry(
        relativePath = relativePath,
        originalBytes = 100,
        optimizedBytes = 50,
        backupPath = "FileForge_Backups_run/$relativePath",
        originalSha256 = "b".repeat(64),
        optimizedSha256 = "c".repeat(64),
        fileKind = FileKind.TEXT,
        toolId = "text-minifier-v1",
        note = "verified",
        completedAt = "2026-08-13T19:42:30Z"
    )

    private fun v2HeaderJson(runId: String) =
        "{\"schemaVersion\":2,\"recordType\":\"header\",\"runId\":\"$runId\",\"startedAt\":\"2026-08-13T19:42:00Z\",\"mode\":\"SAFE\",\"apkLabMode\":false,\"textMinify\":false,\"dryRun\":false,\"appVersion\":\"test\",\"buildVariant\":\"debug\",\"status\":\"RUNNING\"}"

    private fun v2EntryJson(relativePath: String) =
        "{\"schemaVersion\":2,\"recordType\":\"entry\",\"relativePath\":\"$relativePath\",\"originalBytes\":100,\"optimizedBytes\":50,\"backupPath\":\"FileForge_Backups_run/$relativePath\",\"originalSha256\":\"${"b".repeat(64)}\",\"optimizedSha256\":\"${"c".repeat(64)}\",\"fileKind\":\"TEXT\",\"toolId\":\"text-minifier-v1\",\"verificationLevel\":\"SHA_256\",\"note\":\"verified\",\"completedAt\":\"2026-08-13T19:42:30Z\"}"

    private fun v2TerminalJson(entriesCommitted: Int = 2, status: RunStatus = RunStatus.COMPLETED) =
        "{\"schemaVersion\":2,\"recordType\":\"terminal\",\"status\":\"${status.name}\",\"completedAt\":\"2026-08-13T19:43:00Z\",\"entriesCommitted\":$entriesCommitted,\"scanned\":1,\"optimized\":1,\"skipped\":0,\"errors\":0,\"savedBytes\":50,\"bytesRead\":100,\"bytesWritten\":50,\"potentialSavingsBytes\":50}"

    private fun legacyLog(stamp: String, record: String) = """
        FileForge Undo Log $stamp
        Mode=OptimizerSettings(mode=SAFE, apkLabMode=false, textMinify=false)
        Format: relative_path | original_bytes | optimized_bytes | backup_path | note

        $record
    """.trimIndent() + "\n"

    private fun assertJsonObjectLines(contents: String) {
        assertTrue(contents.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("{") && it.endsWith("}") })
    }

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
