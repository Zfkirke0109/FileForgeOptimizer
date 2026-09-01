package com.fileforge.optimizer

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutUpdateSessionTest {
    @Test
    fun manualCheckRunsThroughTheWorkerAndMainBoundaries() {
        val worker = QueuedExecutor()
        val main = QueuedExecutor()
        val delivered = mutableListOf<UpdateCheckResult>()
        val session = AboutUpdateSession(
            checker = LatestReleaseChecker { UpdateCheckResult.Current(SemanticVersion(1, 0, 0)) },
            worker = worker,
            deliverOnMain = main,
            deliver = delivered::add
        )

        session.checkNow()
        assertEquals(1, worker.size)
        assertEquals(emptyList<UpdateCheckResult>(), delivered)

        worker.runNext()
        assertEquals(1, main.size)
        assertEquals(emptyList<UpdateCheckResult>(), delivered)

        main.runNext()
        assertEquals(listOf(UpdateCheckResult.Current(SemanticVersion(1, 0, 0))), delivered)
    }

    @Test
    fun closeCancelsQueuedAndCompletedWorkWithoutRetainingOrDeliveringTheScreen() {
        val worker = QueuedExecutor()
        val main = QueuedExecutor()
        var checks = 0
        val delivered = mutableListOf<UpdateCheckResult>()
        val session = AboutUpdateSession(
            checker = LatestReleaseChecker {
                checks++
                UpdateCheckResult.NoRelease
            },
            worker = worker,
            deliverOnMain = main,
            deliver = delivered::add
        )

        session.checkNow()
        session.close()
        worker.runNext()
        main.runAll()

        assertEquals(0, checks)
        assertEquals(emptyList<UpdateCheckResult>(), delivered)

        val second = AboutUpdateSession(
            checker = LatestReleaseChecker { UpdateCheckResult.NoRelease },
            worker = worker,
            deliverOnMain = main,
            deliver = delivered::add
        )
        second.checkNow()
        worker.runNext()
        second.close()
        main.runNext()
        assertEquals(emptyList<UpdateCheckResult>(), delivered)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
