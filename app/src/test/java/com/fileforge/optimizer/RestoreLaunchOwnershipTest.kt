package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreLaunchOwnershipTest {
    @Test
    fun exactRestoreClaimSurvivesRecreationReplayAndOnlyItsAcknowledgementReleasesIt() {
        val ownership = RestoreLaunchOwnership()
        val first = ServiceRunRequest.Restore(
            "content://tree/root", "FileForge_Undo_v2_first.jsonl", RestoreSelection.Entries(setOf("a.txt"))
        )
        val second = ServiceRunRequest.Restore(
            "content://tree/root", "FileForge_Undo_v2_second.jsonl", RestoreSelection.Entries(setOf("b.txt"))
        )

        val firstClaim = checkNotNull(ownership.tryClaim(first))
        assertFalse(ownership.tryClaim(second) != null)
        ownership.onObserved(RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), false, RunOperationKind.OPTIMIZE))
        assertTrue(ownership.current() === firstClaim)
        ownership.onObserved(RunState.Running(ProgressSnapshot("restoring"), false, RunOperationKind.RESTORE))
        assertTrue(ownership.current() == null)
        assertTrue(ownership.tryClaim(second) != null)
    }

    @Test
    fun synchronousDispatchFailureAndStaleCompletionCannotClearANewerClaim() {
        val ownership = RestoreLaunchOwnership()
        val first = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_first.jsonl", RestoreSelection.All)
        val second = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_second.jsonl", RestoreSelection.All)

        val firstClaim = checkNotNull(ownership.tryClaim(first))
        ownership.onDispatchFailed(firstClaim)
        val secondClaim = checkNotNull(ownership.tryClaim(second))
        ownership.onServiceCompleted(firstClaim)

        assertTrue(ownership.current() === secondClaim)
        ownership.onDispatchFailed(secondClaim)
        assertTrue(ownership.current() == null)
    }
}
