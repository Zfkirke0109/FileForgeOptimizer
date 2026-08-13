package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class RunModelsTest {
    @Test
    fun dryRunIntentNeverRequestsWrites() {
        val intent = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = false)
        assertFalse(intent.allowsSelectedTreeWrites)
    }

    @Test
    fun progressSnapshotOwnsSkipReasonsAtConstruction() {
        val suppliedSkips = mutableMapOf(SkipReason.NO_GAIN to 1)
        val snapshot = ProgressSnapshot(phase = "analyzing", skipsByReason = suppliedSkips)

        suppliedSkips[SkipReason.NO_GAIN] = 2
        suppliedSkips[SkipReason.UNSUPPORTED] = 1

        assertEquals(mapOf(SkipReason.NO_GAIN to 1), snapshot.skipsByReason)
    }
}
