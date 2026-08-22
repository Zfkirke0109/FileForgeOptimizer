package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreDiscoveryVisibilityTest {
    @Test
    fun enteringRestoreStartsAFreshGenerationAndHidingCancelsOnlyItsDiscovery() {
        val gate = RestoreDiscoveryVisibilityGate()

        val first = gate.enterRestore()
        gate.hideRestore()
        val second = gate.enterRestore()

        assertTrue(first.isCancelled())
        assertTrue(!second.isCancelled())
        assertEquals(second.id, gate.currentGeneration()?.id)
        assertTrue(!gate.serviceWorkWasCancelled)
    }

    @Test
    fun staleCompletionCannotReplaceTheNewerDestinationGeneration() {
        val gate = RestoreDiscoveryVisibilityGate()
        val stale = gate.enterRestore()
        val current = gate.enterRestore()

        assertTrue(!gate.acceptCompletion(stale))
        assertTrue(gate.acceptCompletion(current))
    }

    @Test
    fun hiddenGenerationCancelsQueuedDiscoveryBeforeItOpensAnyProviderDocument() {
        val gate = RestoreDiscoveryVisibilityGate()
        val generation = gate.enterRestore()
        val gateway = RecordingDocumentGateway().apply {
            put("FileForge_Undo_v2_queued.jsonl", v2Log("queued", "docs/queued.txt", 5, 2))
            events.clear()
        }
        gate.hideRestore()

        assertThrows(OptimizationCancelledException::class.java) {
            RestoreLogDiscovery(gateway, gateway.root).discover(generation.cancellationToken())
        }
        assertFalse(gateway.events.any { it.startsWith("read:") })
    }
}
