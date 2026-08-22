package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/** Exercises destination visibility scheduling without an Activity or SAF framework dependency. */
class RestoreDiscoverySessionTest {
    @Test
    fun hidingBeforeAQueuedTaskRunsCancelsThatGenerationBeforeProviderWorkAndRejectsItsResult() {
        val queued = QueuedTasks()
        var creations = 0
        val gateway = RecordingDocumentGateway().apply {
            put("FileForge_Undo_v2_queued.jsonl", v2Log("queued", "docs/queued.txt", 5, 2))
            events.clear()
        }
        val delivered = mutableListOf<RestoreDiscoveryResult>()
        val session = RestoreDiscoverySession(
            schedule = queued::add,
            createDiscovery = {
                creations += 1
                RestoreLogDiscovery(gateway, gateway.root)
            },
            onResult = delivered::add
        )

        session.onVisible()
        session.onHidden()
        queued.runNext()

        assertEquals(0, creations)
        assertFalse(gateway.events.any { it.startsWith("read:") })
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun aStaleQueuedGenerationDoesNotCreateProviderWorkOrDuplicateTheCurrentResult() {
        val queued = QueuedTasks()
        val gateway = RecordingDocumentGateway().apply {
            put("FileForge_Undo_v2_second.jsonl", v2Log("second", "docs/second.txt", 7, 3))
            events.clear()
        }
        var creations = 0
        val delivered = mutableListOf<RestoreDiscoveryResult>()
        val session = RestoreDiscoverySession(
            schedule = queued::add,
            createDiscovery = {
                creations += 1
                RestoreLogDiscovery(gateway, gateway.root)
            },
            onResult = delivered::add
        )

        session.onVisible()
        session.onVisible()
        queued.runNext()
        queued.runNext()

        assertEquals(1, creations)
        assertEquals(listOf("FileForge_Undo_v2_second.jsonl"), delivered.single().runs.map { it.undoLogId })
        assertEquals(1, gateway.events.count { it.startsWith("read:") })
    }

    private class QueuedTasks {
        private val tasks = ArrayDeque<() -> Unit>()
        fun add(task: () -> Unit) { tasks += task }
        fun runNext() = tasks.removeFirst().invoke()
    }
}
