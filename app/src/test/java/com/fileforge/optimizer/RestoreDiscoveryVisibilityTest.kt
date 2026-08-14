package com.fileforge.optimizer

import org.junit.Assert.assertEquals
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
}
