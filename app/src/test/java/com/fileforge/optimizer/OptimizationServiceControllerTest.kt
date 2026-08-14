package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OptimizationServiceControllerTest {
    @Test
    fun contractUsesExactStableActionsAndSerializedExtraKeys() {
        assertEquals("com.fileforge.optimizer.action.START", OptimizationServiceContract.ACTION_START)
        assertEquals("com.fileforge.optimizer.action.RESTORE", OptimizationServiceContract.ACTION_RESTORE)
        assertEquals("com.fileforge.optimizer.action.CANCEL", OptimizationServiceContract.ACTION_CANCEL)
        assertEquals(OptimizationServiceActions.ACTION_CANCEL, OptimizationServiceContract.ACTION_CANCEL)
        assertEquals("com.fileforge.optimizer.extra.TREE_URI", OptimizationServiceContract.EXTRA_TREE_URI)
        assertEquals("com.fileforge.optimizer.extra.RUN_INTENT", OptimizationServiceContract.EXTRA_RUN_INTENT)
        assertEquals("com.fileforge.optimizer.extra.UNDO_LOG_ID", OptimizationServiceContract.EXTRA_UNDO_LOG_ID)
        assertEquals(
            "com.fileforge.optimizer.extra.RESTORE_SELECTION",
            OptimizationServiceContract.EXTRA_RESTORE_SELECTION
        )
    }

    @Test
    fun acceptedStartEntersForegroundBeforeSchedulingOrExecutingAndIsNotSticky() {
        val fixture = Fixture()

        val result = fixture.controller.onStartCommand(optimizeRequest())

        assertTrue(result.accepted)
        assertEquals(ServiceRestartPolicy.NOT_STICKY, result.restartPolicy)
        assertEquals(listOf("foreground", "scheduled"), fixture.events)
        assertEquals(1, fixture.runtime.pendingTaskCount)
        assertTrue(fixture.runtime.requests.isEmpty())
        assertEquals(false, fixture.runtime.foregroundStates.single().dryRun)
    }

    @Test
    fun acceptedStartImmediatelyPublishesRunningBeforeQueuedWorkerCanRun() {
        val fixture = Fixture()

        val result = fixture.controller.onStartCommand(optimizeRequest(dryRun = true))

        assertTrue(result.accepted)
        val current = fixture.controller.binding.currentState as RunState.Running
        assertTrue(current.dryRun)
        assertEquals(0, current.snapshot.filesProcessed)
        assertEquals(1, fixture.runtime.pendingTaskCount)
        assertTrue(fixture.runtime.requests.isEmpty())
    }

    @Test
    fun runtimeProgressPublishesCopySafeRunningStateWithRequestDryRunIdentity() {
        val fixture = Fixture()
        val sourceSkips = linkedMapOf(SkipReason.NO_GAIN to 1)
        val progress = ProgressSnapshot(
            phase = "analyzing",
            filesDiscovered = 3,
            filesProcessed = 2,
            candidates = 1,
            skipsByReason = sourceSkips,
            potentialSavingsBytes = 17
        )
        fixture.runtime.progressToEmit += progress
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe(observed::add)
        fixture.controller.onStartCommand(optimizeRequest(dryRun = true))

        fixture.runtime.runNext()
        subscription.close()
        sourceSkips[SkipReason.UNSUPPORTED] = 99

        val running = observed.filterIsInstance<RunState.Running>()
            .single { it.snapshot.filesProcessed == 2 }
        assertTrue(running.dryRun)
        assertNotSame(progress, running.snapshot)
        assertEquals(mapOf(SkipReason.NO_GAIN to 1), running.snapshot.skipsByReason)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (running.snapshot.skipsByReason as MutableMap<SkipReason, Int>)[SkipReason.NO_GAIN] = 2
        }
    }

    @Test
    fun optimizationAndRestoreRequestsReachTheSingleWorkerWithoutLosingFields() {
        val requests = listOf(
            optimizeRequest(),
            ServiceRunRequest.Restore(
                treeUri = "content://tree/restore",
                undoLogId = "FileForge_Undo_run-17.jsonl",
                selection = RestoreSelection.Entries(setOf("docs/a.txt", "images/b.png"))
            )
        )

        requests.forEach { request ->
            val fixture = Fixture()

            val result = fixture.controller.onStartCommand(request)
            fixture.runtime.runNext()

            assertTrue(result.accepted)
            assertEquals(ServiceRestartPolicy.NOT_STICKY, result.restartPolicy)
            assertEquals(listOf(request), fixture.runtime.requests)
        }
    }

    @Test
    fun secondRunIsRejectedUntilTheFirstRunBecomesTerminal() {
        val fixture = Fixture()

        val first = fixture.controller.onStartCommand(optimizeRequest())
        val second = fixture.controller.onStartCommand(
            ServiceRunRequest.Restore(
                treeUri = "content://tree/restore",
                undoLogId = "undo-2",
                selection = RestoreSelection.All
            )
        )

        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertEquals(ServiceRestartPolicy.NOT_STICKY, second.restartPolicy)
        assertEquals(1, fixture.runtime.pendingTaskCount)
        assertEquals(1, fixture.runtime.foregroundStates.size)
        fixture.runtime.runNext()
        assertEquals(listOf(optimizeRequest()), fixture.runtime.requests)
    }

    @Test
    fun terminalCompletionReleasesClaimSoLaterRunIsAccepted() {
        val fixture = Fixture()
        assertTrue(fixture.controller.onStartCommand(optimizeRequest()).accepted)

        fixture.runtime.runNext()
        val later = fixture.controller.onStartCommand(
            ServiceRunRequest.Restore(
                treeUri = "content://tree/restore",
                undoLogId = "undo-later",
                selection = RestoreSelection.All
            )
        )

        assertTrue(later.accepted)
        assertEquals(ServiceRestartPolicy.NOT_STICKY, later.restartPolicy)
        assertEquals(1, fixture.runtime.pendingTaskCount)
        assertEquals(2, fixture.runtime.foregroundStates.size)
    }

    @Test
    fun ordinaryWorkerFailurePublishesFailedTerminalStopsAndReleasesClaim() {
        val fixture = Fixture()
        fixture.runtime.runFailure = IllegalStateException("synthetic worker failure")
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe { state ->
            observed += state
            if (state is RunState.Terminal) fixture.events += "terminal"
        }
        fixture.controller.onStartCommand(optimizeRequest())

        fixture.runtime.runNext()

        val terminal = observed.filterIsInstance<RunState.Terminal>().single()
        assertEquals(RunStatus.FAILED, terminal.report.status)
        assertTrue(fixture.events.indexOf("terminal") < fixture.events.indexOf("stop"))
        assertFalse(fixture.controller.cancelActive())
        fixture.runtime.runFailure = null
        assertTrue(fixture.controller.onStartCommand(optimizeRequest()).accepted)
        subscription.close()
    }

    @Test
    fun executorRejectionAfterForegroundPublishesFailedTerminalStopsAndReleasesClaim() {
        val fixture = Fixture()
        fixture.runtime.executeFailure = IllegalStateException("synthetic executor rejection")
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe { state ->
            observed += state
            if (state is RunState.Terminal) fixture.events += "terminal"
        }

        val rejected = fixture.controller.onStartCommand(optimizeRequest())

        assertFalse(rejected.accepted)
        assertEquals(ServiceRestartPolicy.NOT_STICKY, rejected.restartPolicy)
        assertEquals(RunStatus.FAILED, observed.filterIsInstance<RunState.Terminal>().single().report.status)
        assertTrue(fixture.events.indexOf("foreground") < fixture.events.indexOf("terminal"))
        assertTrue(fixture.events.indexOf("terminal") < fixture.events.indexOf("stop"))
        assertEquals(0, fixture.runtime.pendingTaskCount)
        fixture.runtime.executeFailure = null
        assertTrue(fixture.controller.onStartCommand(optimizeRequest()).accepted)
        subscription.close()
    }

    @Test
    fun cancellationTransitionsOnlyOnceAndWorkerPublishesCancelledTerminal() {
        val fixture = Fixture()
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe(observed::add)
        fixture.controller.onStartCommand(optimizeRequest())

        assertTrue(fixture.controller.cancelActive())
        assertFalse(fixture.controller.cancelActive())
        fixture.runtime.runNext()
        subscription.close()

        val terminal = observed.filterIsInstance<RunState.Terminal>().single()
        assertEquals(RunStatus.CANCELLED, terminal.report.status)
        assertEquals(1, fixture.events.count { it == "stop" })
    }

    @Test
    fun atomicCancellationSourceHasExactlyOneWinnerAcrossThreads() {
        val source = AtomicCancellationSource()
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val winners = AtomicInteger()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = List(4) {
            Thread {
                try {
                    start.await()
                    if (source.cancel()) winners.incrementAndGet()
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }
        }

        workers.forEach(Thread::start)
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        workers.forEach { it.join(250) }

        assertTrue(failures.isEmpty())
        assertEquals(1, winners.get())
        assertThrows(OptimizationCancelledException::class.java) {
            source.throwIfCancelled()
        }
    }

    @Test
    fun bindingCanRemoveAndReaddSameListenerAndImmediatelyReplaysCurrentState() {
        val fixture = Fixture()
        val binding = fixture.controller.binding
        val observed = mutableListOf<String>()
        val listener: (RunState) -> Unit = { state ->
            when (state) {
                RunState.Idle -> observed += "idle"
                is RunState.Running -> observed += "running:${state.snapshot.filesProcessed}"
                is RunState.Terminal -> observed += "terminal:${state.report.status}"
            }
        }

        binding.addListener(listener)
        fixture.repository.publish(runningState(sequence = 1))
        binding.removeListener(listener)
        fixture.repository.publish(runningState(sequence = 2))
        binding.addListener(listener)
        binding.removeListener(listener)

        assertEquals(listOf("idle", "running:1", "running:2"), observed)
        val current = binding.currentState as RunState.Running
        assertEquals(2, current.snapshot.filesProcessed)
    }

    @Test
    fun terminalRepositoryPublicationPrecedesForegroundAndServiceStop() {
        val fixture = Fixture()
        val subscription = fixture.repository.observe { state ->
            if (state is RunState.Terminal) fixture.events += "terminal"
        }
        fixture.controller.onStartCommand(optimizeRequest())

        fixture.runtime.runNext()
        subscription.close()

        assertEquals(
            listOf("foreground", "scheduled", "work", "terminal", "stop"),
            fixture.events
        )
        assertTrue(fixture.storage.writes.isNotEmpty())
    }

    @Test
    fun deniedNotificationPermissionDoesNotBlockForegroundOrWorkSemantics() {
        val fixture = Fixture(notificationPermissionGranted = false)

        val result = fixture.controller.onStartCommand(optimizeRequest())
        fixture.runtime.runNext()

        assertTrue(result.accepted)
        assertEquals(listOf(false), fixture.runtime.detailedNotificationPermissions)
        assertEquals(1, fixture.runtime.foregroundStates.size)
        assertEquals(1, fixture.runtime.requests.size)
        assertTrue(fixture.events.contains("stop"))
    }

    @Test
    fun api35TimeoutCancelsPublishesActionableTerminalThenStopsAndInvalidatesQueuedWork() {
        val fixture = Fixture()
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe { state ->
            observed += state
            if (state is RunState.Terminal) fixture.events += "terminal"
        }
        fixture.controller.onStartCommand(optimizeRequest())

        fixture.controller.onTimeout()

        val terminal = observed.filterIsInstance<RunState.Terminal>().single()
        assertActionableTimeout(terminal)
        assertTrue(fixture.events.indexOf("terminal") < fixture.events.indexOf("stop"))
        assertFalse(fixture.controller.cancelActive())
        fixture.runtime.runNext()
        subscription.close()

        assertTrue(fixture.runtime.requests.isEmpty())
        assertEquals(1, observed.count { it is RunState.Terminal })
        assertEquals(1, fixture.events.count { it == "stop" })
    }

    @Test
    fun api35TimeoutFinalizesPromptlyWhileActiveWorkerLaterCancelsReturnsOrFails() {
        LateWorkerOutcome.entries.forEach { lateOutcome ->
            val fixture = Fixture()
            fixture.runtime.lateWorkerOutcome = lateOutcome
            val observed = Collections.synchronizedList(mutableListOf<RunState>())
            val subscription = fixture.repository.observe { state ->
                observed += state
                if (state is RunState.Terminal) fixture.events += "terminal"
            }
            fixture.controller.onStartCommand(optimizeRequest())
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    fixture.runtime.runNext()
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                }
            }.apply { isDaemon = true }
            worker.start()
            assertTrue("worker did not enter for $lateOutcome", fixture.runtime.runEntered.await(2, TimeUnit.SECONDS))
            val timeoutReturned = CountDownLatch(1)
            val timeoutFailure = AtomicReference<Throwable?>()
            Thread {
                try {
                    fixture.controller.onTimeout()
                } catch (failure: Throwable) {
                    timeoutFailure.set(failure)
                } finally {
                    timeoutReturned.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            assertTrue("timeout waited for active worker for $lateOutcome", timeoutReturned.await(1, TimeUnit.SECONDS))
            assertNull(timeoutFailure.get())
            assertTrue("worker unexpectedly returned before release for $lateOutcome", worker.isAlive)
            val timeoutTerminal = observed.filterIsInstance<RunState.Terminal>().single()
            assertActionableTimeout(timeoutTerminal)
            assertEquals(1, fixture.events.count { it == "stop" })
            assertTrue(fixture.events.indexOf("terminal") < fixture.events.indexOf("stop"))

            fixture.runtime.releaseRun.countDown()
            worker.join(2_000)
            subscription.close()

            assertFalse("worker did not finish for $lateOutcome", worker.isAlive)
            assertNull(workerFailure.get())
            assertEquals(1, observed.count { it is RunState.Terminal })
            assertEquals(1, fixture.events.count { it == "terminal" })
            assertEquals(1, fixture.events.count { it == "stop" })
        }
    }

    @Test
    fun api35TimeoutWithoutActiveRunIsNoOp() {
        val fixture = Fixture()
        val observed = mutableListOf<RunState>()
        val subscription = fixture.repository.observe(observed::add)

        fixture.controller.onTimeout()
        subscription.close()

        assertEquals(listOf(RunState.Idle), observed)
        assertTrue(fixture.controller.binding.currentState === RunState.Idle)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.storage.writes.isEmpty())
    }

    private class Fixture(notificationPermissionGranted: Boolean = true) {
        val events = mutableListOf<String>()
        val storage = RecordingRunStateStorage()
        val repository = RunStateRepository(storage)
        val runtime = RecordingServiceRuntime(events)
        val controller = OptimizationServiceController(
            repository = repository,
            runtime = runtime,
            notificationPermissionGranted = { notificationPermissionGranted }
        )
    }

    private class RecordingServiceRuntime(
        private val events: MutableList<String>
    ) : OptimizationServiceRuntime {
        private val tasks = ArrayDeque<() -> Unit>()
        val requests = mutableListOf<ServiceRunRequest>()
        val foregroundStates = mutableListOf<RunState.Running>()
        val detailedNotificationPermissions = mutableListOf<Boolean>()
        val progressToEmit = mutableListOf<ProgressSnapshot>()
        var executeFailure: Throwable? = null
        var runFailure: Throwable? = null
        val runEntered = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        var lateWorkerOutcome: LateWorkerOutcome? = null
        var result = RunState.Terminal(
            OptimizationReport(scanned = 1, status = RunStatus.COMPLETED),
            dryRun = false
        )

        val pendingTaskCount: Int
            get() = tasks.size

        override fun enterForeground(
            initialState: RunState.Running,
            detailedNotificationsAllowed: Boolean
        ) {
            events += "foreground"
            foregroundStates += initialState
            detailedNotificationPermissions += detailedNotificationsAllowed
        }

        override fun execute(task: () -> Unit) {
            events += "scheduled"
            executeFailure?.let { throw it }
            tasks += task
        }

        override fun run(
            request: ServiceRunRequest,
            cancellation: CancellationToken,
            onProgress: (ProgressSnapshot) -> Unit
        ): RunState.Terminal {
            events += "work"
            requests += request
            cancellation.throwIfCancelled()
            progressToEmit.forEach(onProgress)
            runFailure?.let { throw it }
            lateWorkerOutcome?.let { outcome ->
                runEntered.countDown()
                check(releaseRun.await(2, TimeUnit.SECONDS)) { "Blocked worker was not released" }
                when (outcome) {
                    LateWorkerOutcome.CANCELLED -> cancellation.throwIfCancelled()
                    LateWorkerOutcome.RESULT -> Unit
                    LateWorkerOutcome.FAILURE -> throw IllegalStateException("late worker failure")
                }
            }
            return result
        }

        override fun stopForegroundAndSelf() {
            events += "stop"
        }

        fun runNext() {
            tasks.removeFirst().invoke()
        }
    }

    private class RecordingRunStateStorage : RunStateStorage {
        val writes = mutableListOf<String>()

        override fun read(): String? = null

        override fun write(json: String) {
            writes += json
        }
    }

    private enum class LateWorkerOutcome {
        CANCELLED,
        RESULT,
        FAILURE
    }

    private companion object {
        fun assertActionableTimeout(terminal: RunState.Terminal) {
            val report = terminal.report
            val diagnostic = buildString {
                report.terminalError?.let(::append)
                report.terminalFailures.forEach { append(' ').append(it) }
            }
            assertTrue(report.status == RunStatus.CANCELLED || report.status == RunStatus.FAILED)
            assertTrue(diagnostic.contains("time", ignoreCase = true))
            assertTrue(
                listOf("retry", "reopen", "start again").any {
                    diagnostic.contains(it, ignoreCase = true)
                }
            )
        }

        fun optimizeRequest(dryRun: Boolean = false): ServiceRunRequest.Optimize = ServiceRunRequest.Optimize(
            treeUri = "content://tree/primary",
            runIntent = RunIntent(
                mode = OptimizeMode.SAFE,
                dryRun = dryRun,
                apkLabMode = false,
                textMinify = true
            )
        )

        fun runningState(sequence: Int): RunState.Running = RunState.Running(
            ProgressSnapshot(phase = "optimizing", filesProcessed = sequence),
            dryRun = false
        )
    }
}
