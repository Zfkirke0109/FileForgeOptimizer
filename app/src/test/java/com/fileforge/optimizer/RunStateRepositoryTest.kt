package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        repository.publish(RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), dryRun = false))

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
        val terminal = RunState.Terminal(sourceReport, dryRun = false)
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

            val dryRun = status == RunStatus.CANCELLED
            repository.publish(RunState.Terminal(report, dryRun = dryRun))

            val restored = mutableListOf<RunState>()
            RunStateRepository(storage).observe(restored::add).close()
            val restoredTerminal = restored.single() as RunState.Terminal
            val restoredReport = restoredTerminal.report
            assertEquals(dryRun, restoredTerminal.dryRun)
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
    fun structurallyValidJsonWithUnknownOrWrongTypedReportDataIsRejectedWhole() {
        val validStorage = RecordingRunStateStorage()
        RunStateRepository(validStorage).publish(
            RunState.Terminal(
                OptimizationReport(
                    scanned = 5,
                    status = RunStatus.COMPLETED,
                    skipsByReason = mapOf(SkipReason.NO_GAIN to 1)
                ),
                dryRun = false
            )
        )
        val valid = checkNotNull(validStorage.value)
        val invalidDocuments = listOf(
            valid.replaceFirst(Regex("""(\"status\"\s*:\s*\")[^\"]*(\")""")) { match ->
                "${match.groupValues[1]}FUTURE_STATUS${match.groupValues[2]}"
            },
            valid.replace(Regex("""(\"scanned\"\s*:\s*)5""")) { match ->
                "${match.groupValues[1]}\"five\""
            },
            valid.replace(Regex("""(\"skipsByReason\"\s*:\s*)\{[^{}]*}""")) { match ->
                "${match.groupValues[1]}[]"
            },
            valid.replaceFirst(
                Regex("""(\"skipsByReason\"\s*:\s*\{\s*\")[^\"]*(\")""")
            ) { match ->
                "${match.groupValues[1]}FUTURE_SKIP_REASON${match.groupValues[2]}"
            }
        )
        invalidDocuments.forEach { invalid ->
            assertNotEquals(valid, invalid)
            val observed = mutableListOf<RunState>()

            RunStateRepository(RecordingRunStateStorage(invalid)).observe(observed::add).close()

            assertEquals(listOf(RunState.Idle), observed)
        }
    }

    @Test
    fun independentSubscriptionsAreThreadSafeIdempotentAndStopBeforeLaterPublications() {
        val repository = RunStateRepository(RecordingRunStateStorage())
        val firstStates = Collections.synchronizedList(mutableListOf<RunState>())
        val secondStates = Collections.synchronizedList(mutableListOf<RunState>())
        val firstSubscription = AtomicReference<AutoCloseable>()
        val replayThread = AtomicReference<Thread>()
        val observed = CountDownLatch(1)
        val observerThread = Thread {
            firstSubscription.set(repository.observe { state ->
                firstStates += state
                if (state == RunState.Idle) replayThread.set(Thread.currentThread())
            })
            observed.countDown()
        }
        observerThread.start()
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        observerThread.join(2_000)
        assertEquals(observerThread, replayThread.get())
        val secondSubscription = repository.observe(secondStates::add)

        val publisherThread = Thread {
            repository.publish(
                RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false)
            )
        }
        publisherThread.start()
        publisherThread.join(2_000)
        assertFalse(publisherThread.isAlive)

        val closed = CountDownLatch(1)
        val closeThread = Thread {
            firstSubscription.get().close()
            firstSubscription.get().close()
            closed.countDown()
        }
        closeThread.start()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        closeThread.join(2_000)
        repository.publish(
            RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), dryRun = false)
        )
        secondSubscription.close()

        assertEquals(listOf(RunState.Idle::class, RunState.Running::class), firstStates.map { it::class })
        assertEquals(
            listOf(RunState.Idle::class, RunState.Running::class, RunState.Terminal::class),
            secondStates.map { it::class }
        )
    }

    @Test
    fun listenersRunSynchronouslyOutsideLocksCanCloseThemselvesAndCannotBlockPeersWithFailures() {
        val repository = RunStateRepository(RecordingRunStateStorage())
        val subscription = AtomicReference<AutoCloseable>()
        val closeCompletedInsideCallback = AtomicBoolean(false)
        val selfClosedListenerSawTerminal = AtomicBoolean(false)
        val closeThread = AtomicReference<Thread>()
        val callbackThread = AtomicReference<Thread>()
        subscription.set(repository.observe { state ->
            if (state is RunState.Running) {
                callbackThread.set(Thread.currentThread())
                val closed = CountDownLatch(1)
                closeThread.set(Thread {
                    repository.observe { }.close()
                    closed.countDown()
                }.apply { start() })
                closeCompletedInsideCallback.set(closed.await(2, TimeUnit.SECONDS))
                subscription.get().close()
            }
            if (state is RunState.Terminal) selfClosedListenerSawTerminal.set(true)
        })
        repository.observe { state ->
            if (state is RunState.Terminal) throw IllegalStateException("listener failure")
        }
        val healthyTerminal = CountDownLatch(1)
        repository.observe { state -> if (state is RunState.Terminal) healthyTerminal.countDown() }
        val publisherFailure = AtomicReference<Throwable?>()
        val publishingThread = Thread {
            try {
                repository.publish(
                    RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false)
                )
                repository.publish(
                    RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), dryRun = false)
                )
            } catch (failure: Throwable) {
                publisherFailure.set(failure)
            }
        }

        publishingThread.start()
        publishingThread.join(3_000)
        closeThread.get()?.join(2_000)

        assertFalse(publishingThread.isAlive)
        assertTrue(closeCompletedInsideCallback.get())
        assertFalse(selfClosedListenerSawTerminal.get())
        assertEquals(publishingThread, callbackThread.get())
        assertTrue(healthyTerminal.await(2, TimeUnit.SECONDS))
        assertNull(publisherFailure.get())
    }

    @Test
    fun terminalStateRejectsRunningReport() {
        assertThrows(IllegalArgumentException::class.java) {
            RunState.Terminal(OptimizationReport(status = RunStatus.RUNNING), dryRun = false)
        }
    }

    private class RecordingRunStateStorage(initialValue: String? = null) : RunStateStorage {
        var value: String? = initialValue
            private set
        val writes = mutableListOf<String>()

        override fun read(): String? = value

        override fun write(json: String) {
            writes += json
            value = json
        }
    }
}
