package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Test

class RunModelsTest {
    @Test
    fun dryRunIntentNeverRequestsWrites() {
        val intent = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = false)
        assertFalse(intent.allowsSelectedTreeWrites)
    }
}
