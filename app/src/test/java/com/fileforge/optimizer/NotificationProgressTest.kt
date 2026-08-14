package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProgressTest {
    @Test
    fun dryRunNotificationSaysAnalyzingShowsPotentialSavingsAndOffersCancel() {
        val spec = OptimizationNotification.render(
            RunState.Running(
                snapshot = ProgressSnapshot(
                    phase = "analyzing",
                    filesProcessed = 3,
                    potentialSavingsBytes = 4_096
                ),
                dryRun = true
            )
        )

        assertEquals("Analyzing files", spec.title)
        assertTrue(spec.text.contains("potential", ignoreCase = true))
        assertEquals(1, spec.actions.size)
    }

    @Test
    fun realRunNotificationSaysOptimizingShowsSavedBytesAndOffersCancel() {
        val spec = OptimizationNotification.render(
            RunState.Running(
                snapshot = ProgressSnapshot(
                    phase = "optimizing",
                    filesProcessed = 2,
                    optimized = 1,
                    savedBytes = 2_048
                ),
                dryRun = false
            )
        )

        assertEquals("Optimizing files", spec.title)
        assertTrue(spec.text.contains("saved", ignoreCase = true))
        assertEquals(1, spec.actions.size)
    }

    @Test
    fun progressIsDeterminateOnlyWhenTotalWorkIsPositive() {
        val determinate = OptimizationNotification.render(
            running(totalWork = 8, filesProcessed = 3)
        )

        assertFalse(determinate.isIndeterminate)
        assertEquals(8, determinate.progressMax)
        assertEquals(3, determinate.progressCurrent)

        listOf<Int?>(null, 0, -1).forEach { totalWork ->
            val indeterminate = OptimizationNotification.render(
                running(totalWork = totalWork, filesProcessed = 3)
            )

            assertTrue("totalWork=$totalWork", indeterminate.isIndeterminate)
            assertNull(indeterminate.progressMax)
            assertNull(indeterminate.progressCurrent)
        }
    }

    @Test
    fun everyTerminalStatusHasMeaningfulSummaryAndNeverOffersCancelOrRunningProgress() {
        val statusTokens = mapOf(
            RunStatus.COMPLETED to "complete",
            RunStatus.COMPLETED_WITH_ERRORS to "error",
            RunStatus.CANCELLED to "cancel",
            RunStatus.FAILED to "fail"
        )
        statusTokens.forEach { (status, statusToken) ->
            val spec = OptimizationNotification.render(
                RunState.Terminal(
                    OptimizationReport(
                        scanned = 10,
                        optimized = 4,
                        savedBytes = 8_192,
                        status = status
                    ),
                    dryRun = false
                )
            )

            assertTrue(
                "status=$status",
                "${spec.title} ${spec.text}".contains(statusToken, ignoreCase = true)
            )
            assertTrue("status=$status", spec.text.contains("10"))
            assertTrue("status=$status", spec.text.contains("saved", ignoreCase = true))
            assertTrue("status=$status", spec.actions.isEmpty())
            assertFalse("status=$status", spec.isIndeterminate)
            assertNull(spec.progressMax)
            assertNull(spec.progressCurrent)
        }
    }

    @Test
    fun dryRunTerminalReportsPotentialSavingsWithoutClaimingSavedWrites() {
        val spec = OptimizationNotification.render(
            RunState.Terminal(
                OptimizationReport(
                    scanned = 6,
                    candidates = 2,
                    potentialSavingsBytes = 4_096,
                    status = RunStatus.COMPLETED
                ),
                dryRun = true
            )
        )

        assertTrue(spec.text.contains("potential", ignoreCase = true))
        assertFalse(spec.text.contains("saved", ignoreCase = true))
    }

    @Test
    fun completedDryRunTitleDescribesAnalysisRatherThanOptimization() {
        val spec = OptimizationNotification.render(
            RunState.Terminal(
                OptimizationReport(status = RunStatus.COMPLETED),
                dryRun = true
            )
        )

        assertTrue(spec.title.contains("analysis", ignoreCase = true))
        assertTrue(spec.title.contains("complete", ignoreCase = true))
        assertFalse(spec.title.contains("optimization", ignoreCase = true))
    }

    @Test
    fun notificationUsesStableChannelAndNotificationIdentifiers() {
        val running = OptimizationNotification.render(running(totalWork = null, filesProcessed = 0))
        val terminal = OptimizationNotification.render(
            RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), dryRun = false)
        )

        assertEquals("fileforge_optimization", OptimizationNotification.CHANNEL_ID)
        assertEquals("Optimization progress", OptimizationNotification.CHANNEL_NAME)
        assertTrue(OptimizationNotification.NOTIFICATION_ID > 0)
        assertEquals(OptimizationNotification.CHANNEL_ID, running.channelId)
        assertEquals(OptimizationNotification.CHANNEL_ID, terminal.channelId)
        assertEquals(OptimizationNotification.NOTIFICATION_ID, running.notificationId)
        assertEquals(running.notificationId, terminal.notificationId)
    }

    @Test
    fun channelIsLowAndSilentAndRunningHasOneSafeServiceCancelAction() {
        val channel = OptimizationNotification.channelSpec
        val running = OptimizationNotification.render(running(totalWork = null, filesProcessed = 0))
        val terminal = OptimizationNotification.render(
            RunState.Terminal(OptimizationReport(status = RunStatus.CANCELLED), dryRun = false)
        )

        assertEquals(OptimizationNotification.CHANNEL_ID, channel.id)
        assertEquals(OptimizationNotification.CHANNEL_NAME, channel.name)
        assertEquals(ChannelImportance.LOW, channel.importance)
        assertFalse(channel.hasSound)
        val cancel = running.actions.single()
        assertEquals("Cancel", cancel.title)
        assertEquals("com.fileforge.optimizer.action.CANCEL", OptimizationServiceActions.ACTION_CANCEL)
        assertEquals(OptimizationServiceActions.ACTION_CANCEL, cancel.serviceAction)
        assertTrue(cancel.isImmutable)
        assertTrue(cancel.updateCurrent)
        assertTrue(terminal.actions.isEmpty())
    }

    @Test
    fun runningUpdatesAreLimitedToFourPerSecondWhileTerminalAlwaysDelivers() {
        val clock = RecordingMonotonicClock(nowMillis = 0)
        val throttle = NotificationUpdateThrottle(clock)
        val running = running(totalWork = null, filesProcessed = 0)

        assertTrue(throttle.shouldDeliver(running))
        clock.advanceBy(249)
        assertFalse(throttle.shouldDeliver(running))
        clock.advanceBy(1)
        assertTrue(throttle.shouldDeliver(running))
        assertFalse(throttle.shouldDeliver(running))

        val terminal = RunState.Terminal(
            OptimizationReport(status = RunStatus.COMPLETED),
            dryRun = false
        )
        assertTrue(throttle.shouldDeliver(terminal))
        assertTrue(throttle.shouldDeliver(terminal))
    }

    private fun running(totalWork: Int?, filesProcessed: Int): RunState.Running =
        RunState.Running(
            snapshot = ProgressSnapshot(
                phase = "optimizing",
                filesProcessed = filesProcessed,
                savedBytes = 1_024,
                totalWork = totalWork
            ),
            dryRun = false
        )

    private class RecordingMonotonicClock(var nowMillis: Long) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis

        fun advanceBy(milliseconds: Long) {
            nowMillis += milliseconds
        }
    }
}
