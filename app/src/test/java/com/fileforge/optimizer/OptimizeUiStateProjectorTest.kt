package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeUiStateProjectorTest {
    @Test
    fun dryRunRequiresOnlyReadableDirectoryButNormalRunAlsoRequiresWriteAccess() {
        val readableDirectory = SelectedTreeCapabilities(
            exists = true,
            isDirectory = true,
            canRead = true,
            canWrite = false
        )

        val dryRun = OptimizeUiStateProjector.project(
            RunState.Idle,
            readableDirectory,
            dryRun = true
        )
        val normalRun = OptimizeUiStateProjector.project(
            RunState.Idle,
            readableDirectory,
            dryRun = false
        )

        assertTrue(dryRun.startEnabled)
        assertFalse(normalRun.startEnabled)
    }

    @Test
    fun runningProjectionDisablesStartAndReplaysLivePathCountersAndProgress() {
        val state = RunState.Running(
            ProgressSnapshot(
                phase = "optimizing",
                currentRelativePath = "Photos/holiday.jpg",
                filesDiscovered = 12,
                filesProcessed = 5,
                candidates = 4,
                optimized = 3,
                errors = 1,
                savedBytes = 8_192,
                totalWork = 12
            ),
            dryRun = false
        )

        val projection = OptimizeUiStateProjector.project(
            state,
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            dryRun = false
        )

        assertFalse(projection.startEnabled)
        assertTrue(projection.cancelEnabled)
        assertEquals("optimizing", projection.phase)
        assertEquals("Photos/holiday.jpg", projection.currentPath)
        assertEquals(5, projection.filesProcessed)
        assertEquals(12, projection.filesDiscovered)
        assertEquals(3, projection.optimized)
        assertEquals(1, projection.errors)
        assertFalse(projection.progressIndeterminate)
        assertEquals(12, projection.progressMax)
        assertEquals(5, projection.progressCurrent)
    }

    @Test
    fun unknownTotalIsIndeterminateAndDryRunShowsPotentialSavings() {
        val projection = OptimizeUiStateProjector.project(
            RunState.Running(
                ProgressSnapshot(
                    phase = "analyzing",
                    filesProcessed = 7,
                    potentialSavingsBytes = 4_096
                ),
                dryRun = true
            ),
            SelectedTreeCapabilities.READ_ONLY_DIRECTORY,
            dryRun = true
        )

        assertTrue(projection.progressIndeterminate)
        assertNull(projection.progressMax)
        assertNull(projection.progressCurrent)
        assertEquals(4_096, projection.potentialSavingsBytes)
    }

    @Test
    fun failedTerminalProjectionKeepsErrorAndPerFileFailuresVisible() {
        val projection = OptimizeUiStateProjector.project(
            RunState.Terminal(
                OptimizationReport(
                    scanned = 9,
                    optimized = 2,
                    errors = 2,
                    status = RunStatus.FAILED,
                    terminalError = "Provider disconnected",
                    terminalFailures = listOf("a.zip: verification failed")
                ),
                dryRun = false
            ),
            SelectedTreeCapabilities.READ_WRITE_DIRECTORY,
            dryRun = false
        )

        assertEquals(RunStatus.FAILED, projection.terminalStatus)
        assertEquals("Provider disconnected", projection.terminalError)
        assertEquals(listOf("a.zip: verification failed"), projection.terminalFailures)
        assertFalse(projection.cancelEnabled)
        assertTrue(projection.startEnabled)
    }
}
