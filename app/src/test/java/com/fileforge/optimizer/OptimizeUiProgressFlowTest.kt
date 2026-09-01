package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeUiProgressFlowTest {
    @Test
    fun visibleProgressIndicatorHidesBeforeChangingModeAndShowsAfterward() {
        val indicator = RecordingProgressIndicator(indeterminate = false)

        OptimizeProgressIndicatorRenderer.render(
            indicator,
            indeterminate = true,
            maximum = null,
            current = null
        )

        assertEquals(listOf("hide", "indeterminate:true", "show"), indicator.events)
        assertTrue(indicator.indeterminate)
    }

    @Test
    fun progressIndicatorDoesNotHideWhenModeIsUnchanged() {
        val indicator = RecordingProgressIndicator(indeterminate = false)

        OptimizeProgressIndicatorRenderer.render(
            indicator,
            indeterminate = false,
            maximum = 12,
            current = 5
        )

        assertEquals(listOf("maximum:12", "progress:5:true"), indicator.events)
    }

    @Test
    fun newestStateCoalescesIntoTheOnlyPendingUiDelivery() {
        val scheduled = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        val dispatcher = LatestValueDispatcher<String>(
            schedule = { task -> scheduled.addLast(task) },
            deliver = delivered::add
        )

        dispatcher.submit("discovering")
        dispatcher.submit("optimizing/a.zip")
        dispatcher.submit("optimizing/b.zip")

        assertEquals(1, scheduled.size)
        assertTrue(delivered.isEmpty())
        scheduled.removeFirst().invoke()
        assertEquals(listOf("optimizing/b.zip"), delivered)
    }

    @Test
    fun clearingPendingDeliveryPreventsStoppedActivityUpdate() {
        val scheduled = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        val dispatcher = LatestValueDispatcher<String>(
            schedule = { task -> scheduled.addLast(task) },
            deliver = delivered::add
        )

        dispatcher.submit("running")
        dispatcher.clear()
        scheduled.removeFirst().invoke()

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun capabilityCacheReadsProviderOnlyWhenExplicitlyRefreshed() {
        var reads = 0
        val cache = SelectedTreeCapabilitiesCache {
            reads += 1
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY
        }

        repeat(100) {
            assertEquals(SelectedTreeCapabilities.NONE, cache.current)
        }
        assertEquals(0, reads)

        assertEquals(SelectedTreeCapabilities.READ_WRITE_DIRECTORY, cache.refresh())
        repeat(100) {
            assertEquals(SelectedTreeCapabilities.READ_WRITE_DIRECTORY, cache.current)
        }
        assertEquals(1, reads)
    }

    @Test
    fun initialReplayAndSequenceWatermarkGateStartAgainstQueuedPredispatchState() {
        val gate = OptimizeStartDispatchGate()

        assertFalse(gate.isReplayReady)
        assertFalse(gate.allowsStart(baseStartEnabled = true))
        assertFalse(gate.beginDispatch(observationWatermark = 0))

        val previous = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        gate.onObservedState(previous, sequence = 1)

        assertTrue(gate.isReplayReady)
        assertTrue(gate.allowsStart(baseStartEnabled = true))
        assertTrue(gate.beginDispatch(observationWatermark = 2))

        assertTrue(gate.isPending)
        assertFalse(gate.allowsStart(baseStartEnabled = true))
        gate.onObservedState(previous, sequence = 2)
        assertTrue(gate.isPending)
        gate.onObservedState(
            RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false),
            sequence = 3
        )
        assertFalse(gate.isPending)
    }

    @Test
    fun aNewTerminalCanReleasePendingStartWhenServiceSetupFailsBeforeRunningReplay() {
        val gate = OptimizeStartDispatchGate()
        gate.onObservedState(
            RunState.Terminal(
                OptimizationReport(status = RunStatus.COMPLETED),
                dryRun = false
            ),
            sequence = 1
        )
        assertTrue(gate.beginDispatch(observationWatermark = 1))

        gate.onObservedState(
            RunState.Terminal(
                OptimizationReport(
                    status = RunStatus.FAILED,
                    terminalError = "foreground entry failed"
                ),
                dryRun = false
            ),
            sequence = 2
        )

        assertFalse(gate.isPending)
    }

    @Test
    fun rebindReplayOfSameStaleTerminalDoesNotReleasePendingDispatch() {
        val gate = OptimizeStartDispatchGate()
        val staleTerminal = RunState.Terminal(
            OptimizationReport(
                scanned = 4,
                status = RunStatus.COMPLETED
            ),
            dryRun = false
        )
        gate.onObservedState(staleTerminal, sequence = 1)
        assertTrue(gate.beginDispatch(observationWatermark = 1))

        gate.awaitReplay()
        gate.onObservedState(
            RunState.Terminal(staleTerminal.report, dryRun = false),
            sequence = 2
        )

        assertTrue(gate.isReplayReady)
        assertTrue(gate.isPending)
        gate.onObservedState(
            RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false),
            sequence = 3
        )
        assertFalse(gate.isPending)
    }

    @Test
    fun changedSetupFailureTerminalAfterStaleRebindReplayReleasesDispatch() {
        val gate = OptimizeStartDispatchGate()
        val staleTerminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        gate.onObservedState(staleTerminal, sequence = 1)
        assertTrue(gate.beginDispatch(observationWatermark = 1))
        gate.awaitReplay()
        gate.onObservedState(
            RunState.Terminal(staleTerminal.report, dryRun = false),
            sequence = 2
        )

        gate.onObservedState(
            RunState.Terminal(
                OptimizationReport(
                    status = RunStatus.FAILED,
                    terminalError = "foreground entry failed"
                ),
                dryRun = false
            ),
            sequence = 3
        )

        assertFalse(gate.isPending)
    }

    @Test
    fun recreatedGateKeepsProcessOwnedDispatchPendingForFirstReplayThenClearsIdenticalTerminal() {
        val ownership = OptimizeDispatchOwnership()
        val staleTerminal = RunState.Terminal(
            OptimizationReport(
                scanned = 4,
                status = RunStatus.COMPLETED
            ),
            dryRun = false
        )
        val original = OptimizeStartDispatchGate(ownership)
        original.onObservedState(staleTerminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))

        val recreated = OptimizeStartDispatchGate(ownership)
        recreated.awaitReplay()
        recreated.onObservedState(
            RunState.Terminal(staleTerminal.report, dryRun = false),
            sequence = 1
        )

        assertTrue(recreated.isReplayReady)
        assertTrue(recreated.isPending)
        assertFalse(recreated.allowsStart(baseStartEnabled = true))
        recreated.onObservedState(
            RunState.Terminal(staleTerminal.report, dryRun = false),
            sequence = 2
        )
        assertFalse(recreated.isPending)
    }

    @Test
    fun recreatedGateClearsProcessOwnedDispatchForChangedSetupFailureTerminal() {
        val ownership = OptimizeDispatchOwnership()
        val staleTerminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = true
        )
        val original = OptimizeStartDispatchGate(ownership)
        original.onObservedState(staleTerminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))

        val recreated = OptimizeStartDispatchGate(ownership)
        recreated.awaitReplay()
        recreated.onObservedState(
            RunState.Terminal(
                OptimizationReport(
                    status = RunStatus.FAILED,
                    terminalError = "foreground entry failed"
                ),
                dryRun = true
            ),
            sequence = 1
        )

        assertFalse(recreated.isPending)
    }

    @Test
    fun recreatedGateClearsProcessOwnedDispatchForRunningObservation() {
        val ownership = OptimizeDispatchOwnership()
        val original = OptimizeStartDispatchGate(ownership)
        val staleTerminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        original.onObservedState(staleTerminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))

        val recreated = OptimizeStartDispatchGate(ownership)
        recreated.awaitReplay()
        recreated.onObservedState(
            RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false),
            sequence = 1
        )

        assertFalse(recreated.isPending)
    }

    @Test
    fun simulatedProcessResetHasNoOrphanedPendingDispatch() {
        val firstProcessOwnership = OptimizeDispatchOwnership()
        val original = OptimizeStartDispatchGate(firstProcessOwnership)
        val staleTerminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        original.onObservedState(staleTerminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))

        val restartedProcess = OptimizeStartDispatchGate(OptimizeDispatchOwnership())
        restartedProcess.awaitReplay()
        restartedProcess.onObservedState(staleTerminal, sequence = 1)

        assertFalse(restartedProcess.isPending)
        assertTrue(restartedProcess.allowsStart(baseStartEnabled = true))
    }

    @Test
    fun identicalTerminalReplayClearsWhenServiceFinishedWhileUiWasDetached() {
        val ownership = OptimizeDispatchOwnership()
        val original = OptimizeStartDispatchGate(ownership)
        val terminal = RunState.Terminal(
            OptimizationReport(
                status = RunStatus.FAILED,
                terminalError = "foreground entry failed"
            ),
            dryRun = false
        )
        original.onObservedState(terminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))
        ownership.onServiceFinished(requireNotNull(ownership.current()))

        val recreated = OptimizeStartDispatchGate(ownership)
        recreated.awaitReplay()
        recreated.onObservedState(terminal, sequence = 1)

        assertFalse(recreated.isPending)
        assertTrue(recreated.allowsStart(baseStartEnabled = true))
    }

    @Test
    fun serviceFinishAfterEquivalentReplayReleasesOwnershipAndNotifiesVisibleUi() {
        val ownership = OptimizeDispatchOwnership()
        var completionNotifications = 0
        val subscription = ownership.observeServiceFinished {
            completionNotifications += 1
        }
        val terminal = RunState.Terminal(
            OptimizationReport(
                status = RunStatus.FAILED,
                terminalError = "foreground entry failed"
            ),
            dryRun = false
        )
        val original = OptimizeStartDispatchGate(ownership)
        original.onObservedState(terminal, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))
        val serviceClaim = requireNotNull(ownership.current())

        val recreated = OptimizeStartDispatchGate(ownership)
        recreated.awaitReplay()
        recreated.onObservedState(terminal, sequence = 2)
        assertTrue(recreated.isPending)

        ownership.onServiceFinished(serviceClaim)

        assertFalse(recreated.isPending)
        assertTrue(recreated.allowsStart(baseStartEnabled = true))
        assertEquals(1, completionNotifications)
        subscription.close()
    }

    @Test
    fun lateFinishFromPreviousServiceCannotReleaseNewerDispatchClaim() {
        val ownership = OptimizeDispatchOwnership()
        val gate = OptimizeStartDispatchGate(ownership)
        val terminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        gate.onObservedState(terminal, sequence = 1)
        assertTrue(gate.beginDispatch(observationWatermark = 1))
        val previousServiceClaim = requireNotNull(ownership.current())
        gate.onObservedState(
            RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false),
            sequence = 2
        )
        gate.onObservedState(terminal, sequence = 3)
        assertTrue(gate.beginDispatch(observationWatermark = 3))
        val newerClaim = requireNotNull(ownership.current())

        ownership.onServiceFinished(previousServiceClaim)

        assertEquals(newerClaim, ownership.current())
        assertTrue(gate.isPending)
    }

    @Test
    fun synchronousDispatchFailureClearsProcessOwnershipForNextController() {
        val ownership = OptimizeDispatchOwnership()
        val original = OptimizeStartDispatchGate(ownership)
        original.onObservedState(RunState.Idle, sequence = 1)
        assertTrue(original.beginDispatch(observationWatermark = 1))

        original.onDispatchFailed()

        val recreated = OptimizeStartDispatchGate(ownership)
        assertFalse(recreated.isPending)
    }

    @Test
    fun runStateObservationSequencerNumbersSubmissionOrderAndExposesWatermark() {
        val sequencer = RunStateObservationSequencer()

        val first = sequencer.next(RunState.Idle)
        val second = sequencer.next(
            RunState.Running(ProgressSnapshot(phase = "discovering"), dryRun = true)
        )

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(2L, sequencer.watermark)
    }

    private class RecordingProgressIndicator(
        override var indeterminate: Boolean
    ) : OptimizeProgressIndicator {
        val events = mutableListOf<String>()

        override fun hide() {
            events += "hide"
        }

        override fun show() {
            events += "show"
        }

        override fun setIndeterminateMode(value: Boolean) {
            indeterminate = value
            events += "indeterminate:$value"
        }

        override fun setMaximum(value: Int) {
            events += "maximum:$value"
        }

        override fun setProgress(value: Int, animated: Boolean) {
            events += "progress:$value:$animated"
        }
    }
}
