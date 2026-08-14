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
    fun preexistingTerminalDoesNotClearNewStartDispatchUntilNewServiceStateArrives() {
        val gate = OptimizeStartDispatchGate()
        val previous = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        gate.onObservedState(previous)

        gate.beginDispatch()

        assertTrue(gate.isPending)
        assertFalse(gate.allowsStart(baseStartEnabled = true))
        gate.onObservedState(
            RunState.Running(ProgressSnapshot(phase = "optimizing"), dryRun = false)
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
            )
        )
        gate.beginDispatch()

        gate.onObservedState(
            RunState.Terminal(
                OptimizationReport(
                    status = RunStatus.FAILED,
                    terminalError = "foreground entry failed"
                ),
                dryRun = false
            )
        )

        assertFalse(gate.isPending)
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
