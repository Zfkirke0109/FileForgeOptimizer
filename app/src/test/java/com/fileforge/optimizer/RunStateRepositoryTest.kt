package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateRepositoryTest {
    @Test
    fun observationImmediatelyReplaysCurrentStateThenPublishesUntilClosed() {
        val repository = RunStateRepository(RecordingRunStateStorage())
        val observed = mutableListOf<RunState>()

        val subscription = repository.observe(observed::add)
        repository.publish(
            RunState.Running(
                snapshot = ProgressSnapshot(phase = "optimizing", filesProcessed = 1),
                dryRun = false
            )
        )
        subscription.close()
        repository.publish(RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED)))

        assertEquals(2, observed.size)
        assertEquals(RunState.Idle, observed[0])
        assertTrue(observed[1] is RunState.Running)
        assertEquals(1, (observed[1] as RunState.Running).snapshot.filesProcessed)
    }

    @Test
    fun runningAndTerminalStatesAreImmutableSnapshotsWithCopySafeCollectionsAndReports() {
        val mutableSkips = linkedMapOf(SkipReason.NO_GAIN to 2)
        val running = RunState.Running(
            snapshot = ProgressSnapshot(phase = "optimizing", skipsByReason = mutableSkips),
            dryRun = false
        )
        mutableSkips[SkipReason.NO_GAIN] = 99

        assertEquals(mapOf(SkipReason.NO_GAIN to 2), running.snapshot.skipsByReason)

        val reportSkips = linkedMapOf(SkipReason.UNSUPPORTED to 3)
        val terminalFailures = mutableListOf("close warning")
        val sourceReport = OptimizationReport(
            scanned = 8,
            optimized = 2,
            skipped = 3,
            errors = 1,
            savedBytes = 4_096,
            candidates = 2,
            potentialSavingsBytes = 5_120,
            bytesRead = 10_000,
            bytesWritten = 5_904,
            status = RunStatus.COMPLETED_WITH_ERRORS,
            skipsByReason = reportSkips,
            terminalError = "provider warning",
            terminalFailures = terminalFailures,
            rollbackFailure = null
        )
        val terminal = RunState.Terminal(sourceReport)
        sourceReport.optimized = 99
        reportSkips[SkipReason.UNSUPPORTED] = 99
        terminalFailures += "late mutation"
        val firstRead = terminal.report
        firstRead.optimized = 77
        firstRead.skipsByReason = mapOf(SkipReason.NO_GAIN to 77)
        firstRead.terminalFailures = listOf("consumer mutation")

        val secondRead = terminal.report

        assertEquals(2, secondRead.optimized)
        assertEquals(mapOf(SkipReason.UNSUPPORTED to 3), secondRead.skipsByReason)
        assertEquals(listOf("close warning"), secondRead.terminalFailures)
    }

    @Test
    fun onlyTerminalReportsArePersistedAsJsonAndEveryTerminalStatusRestores() {
        val storage = RecordingRunStateStorage()
        val repository = RunStateRepository(storage)
        repository.publish(
            RunState.Running(
                snapshot = ProgressSnapshot(phase = "analyzing", potentialSavingsBytes = 100),
                dryRun = true
            )
        )
        assertTrue(storage.writes.isEmpty())

        RunStatus.entries.filter { it != RunStatus.RUNNING }.forEach { status ->
            val report = OptimizationReport(
                scanned = 5,
                optimized = 2,
                skipped = 2,
                errors = 1,
                savedBytes = 2_048,
                candidates = 3,
                potentialSavingsBytes = 3_072,
                bytesRead = 8_192,
                bytesWritten = 6_144,
                status = status,
                skipsByReason = mapOf(SkipReason.NO_GAIN to 2),
                terminalError = if (status == RunStatus.FAILED) "failed" else null,
                terminalFailures = listOf("receipt warning"),
                rollbackFailure = if (status == RunStatus.FAILED) "rollback failed" else null
            )

            repository.publish(RunState.Terminal(report))

            val restored = mutableListOf<RunState>()
            RunStateRepository(storage).observe(restored::add).close()
            val restoredReport = (restored.single() as RunState.Terminal).report
            assertEquals(status, restoredReport.status)
            assertEquals(report.scanned, restoredReport.scanned)
            assertEquals(report.optimized, restoredReport.optimized)
            assertEquals(report.skipped, restoredReport.skipped)
            assertEquals(report.errors, restoredReport.errors)
            assertEquals(report.savedBytes, restoredReport.savedBytes)
            assertEquals(report.candidates, restoredReport.candidates)
            assertEquals(report.potentialSavingsBytes, restoredReport.potentialSavingsBytes)
            assertEquals(report.bytesRead, restoredReport.bytesRead)
            assertEquals(report.bytesWritten, restoredReport.bytesWritten)
            assertEquals(report.skipsByReason, restoredReport.skipsByReason)
            assertEquals(report.terminalError, restoredReport.terminalError)
            assertEquals(report.terminalFailures, restoredReport.terminalFailures)
            assertEquals(report.rollbackFailure, restoredReport.rollbackFailure)
        }
        assertEquals(RunStatus.entries.count { it != RunStatus.RUNNING }, storage.writes.size)
    }

    @Test
    fun missingInvalidOrNonTerminalStoredJsonFallsBackToIdle() {
        listOf<String?>(
            null,
            "",
            "not-json",
            "{}",
            """{"status":"RUNNING"}"""
        ).forEach { stored ->
            val observed = mutableListOf<RunState>()

            RunStateRepository(RecordingRunStateStorage(stored)).observe(observed::add).close()

            assertEquals("stored=$stored", listOf(RunState.Idle), observed)
        }
    }

    @Test
    fun terminalStateRejectsRunningReport() {
        assertThrows(IllegalArgumentException::class.java) {
            RunState.Terminal(OptimizationReport(status = RunStatus.RUNNING))
        }
    }

    private class RecordingRunStateStorage(initialValue: String? = null) : RunStateStorage {
        private var value: String? = initialValue
        val writes = mutableListOf<String>()

        override fun read(): String? = value

        override fun write(json: String) {
            writes += json
            value = json
        }
    }
}
