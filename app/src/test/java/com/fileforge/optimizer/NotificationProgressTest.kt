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
        assertTrue(spec.hasCancelAction)
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
        assertTrue(spec.hasCancelAction)
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
    fun terminalNotificationNeverOffersCancelOrRunningProgress() {
        RunStatus.entries.filter { it != RunStatus.RUNNING }.forEach { status ->
            val spec = OptimizationNotification.render(
                RunState.Terminal(
                    OptimizationReport(
                        scanned = 10,
                        optimized = 4,
                        savedBytes = 8_192,
                        status = status
                    )
                )
            )

            assertFalse("status=$status", spec.hasCancelAction)
            assertFalse("status=$status", spec.isIndeterminate)
            assertNull(spec.progressMax)
            assertNull(spec.progressCurrent)
        }
    }

    @Test
    fun notificationUsesStableChannelAndNotificationIdentifiers() {
        val running = OptimizationNotification.render(running(totalWork = null, filesProcessed = 0))
        val terminal = OptimizationNotification.render(
            RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED))
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
    fun runningUpdatesAreLimitedToFourPerSecondWhileTerminalAlwaysDelivers() {
        val clock = RecordingMonotonicClock(nowMillis = 1_000)
        val throttle = NotificationUpdateThrottle(clock)
        val running = running(totalWork = null, filesProcessed = 0)

        assertTrue(throttle.shouldDeliver(running))
        clock.advanceBy(249)
        assertFalse(throttle.shouldDeliver(running))
        clock.advanceBy(1)
        assertTrue(throttle.shouldDeliver(running))
        assertFalse(throttle.shouldDeliver(running))

        val terminal = RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED))
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
