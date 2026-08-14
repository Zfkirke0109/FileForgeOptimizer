package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreLaunchOwnershipTest {
    @Test
    fun persistedExactClaimRecoversConservativelyAfterProcessDeathAndBlocksPendingOptimize() {
        val store = RecordingRestoreLaunchClaimStore()
        val request = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_saved.jsonl", RestoreSelection.All)
        val firstProcess = RestoreLaunchOwnership(store)
        assertTrue(firstProcess.tryClaim(request, optimizePending = false) != null)

        val recoveredProcess = RestoreLaunchOwnership(store)
        assertTrue(recoveredProcess.current() != null)
        assertFalse(recoveredProcess.tryClaim(request, optimizePending = true) != null)
        assertFalse(recoveredProcess.tryClaim(request, optimizePending = false) != null)
        recoveredProcess.onServiceRejected(request)
        assertTrue(recoveredProcess.tryClaim(request, optimizePending = false) != null)
    }

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
        ownership.onServiceAccepted(first)
        ownership.onServiceCompleted(first)
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

private class RecordingRestoreLaunchClaimStore : RestoreLaunchClaimStore {
    var value: RestoreLaunchClaimRecord? = null
    override fun read(): RestoreLaunchClaimRecord? = value
    override fun write(record: RestoreLaunchClaimRecord) { value = record }
    override fun clear() { value = null }
}
