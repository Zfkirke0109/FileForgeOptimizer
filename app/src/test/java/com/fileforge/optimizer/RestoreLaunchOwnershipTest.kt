package com.fileforge.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreLaunchOwnershipTest {
    private fun confirmedEntries(vararg paths: String) = RestoreSelection.Entries(
        relativePaths = paths.toCollection(linkedSetOf()),
        undoDocumentId = "undo-node",
        entryCount = paths.size,
        undoSha256 = "a".repeat(64)
    )

    @Test
    fun serviceRestoreRejectsTheUnconfirmedAllSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            RestoreSelection.All.requireServiceSnapshot()
        }

        confirmedEntries("docs/report.pdf").requireServiceSnapshot()
    }

    @Test
    fun activityRecreationSharesTheInProcessClaimAndStillBlocksPendingOptimize() {
        val request = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_saved.jsonl", RestoreSelection.All)
        val processOwnership = RestoreLaunchOwnership()
        val activityRecreation = processOwnership

        assertTrue(processOwnership.tryClaim(request, optimizePending = false) != null)

        assertTrue(activityRecreation.current() != null)
        assertFalse(activityRecreation.tryClaim(request, optimizePending = true) != null)
        assertFalse(activityRecreation.tryClaim(request, optimizePending = false) != null)
    }

    @Test
    fun pendingOptimizeBlocksAnOtherwiseUnclaimedRestoreLaunch() {
        val ownership = RestoreLaunchOwnership()
        val request = ServiceRunRequest.Restore(
            "content://tree/root",
            "FileForge_Undo_v2_pending-optimize.jsonl",
            RestoreSelection.All
        )

        assertTrue(ownership.current() == null)
        assertFalse(ownership.tryClaim(request, optimizePending = true) != null)
        assertTrue(ownership.current() == null)
    }

    @Test
    fun processResetDoesNotRecoverAnOrphanedRestoreLaunchClaim() {
        val request = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_saved.jsonl", RestoreSelection.All)
        val terminatedProcess = RestoreLaunchOwnership()
        assertTrue(terminatedProcess.tryClaim(request) != null)

        val restartedProcess = RestoreLaunchOwnership()

        assertTrue(restartedProcess.current() == null)
        assertTrue(restartedProcess.tryClaim(request, optimizePending = false) != null)
    }

    @Test
    fun exactRestoreClaimSurvivesRecreationReplayAndOnlyItsAcknowledgementReleasesIt() {
        val ownership = RestoreLaunchOwnership()
        val first = ServiceRunRequest.Restore(
            "content://tree/root", "FileForge_Undo_v2_first.jsonl", confirmedEntries("a.txt")
        )
        val second = ServiceRunRequest.Restore(
            "content://tree/root", "FileForge_Undo_v2_second.jsonl", confirmedEntries("b.txt")
        )

        val firstClaim = checkNotNull(ownership.tryClaim(first))
        assertFalse(ownership.tryClaim(second) != null)
        ownership.onObserved(RunState.Terminal(OptimizationReport(status = RunStatus.COMPLETED), false, RunOperationKind.OPTIMIZE))
        assertTrue(ownership.current() === firstClaim)
        val serviceClaim = checkNotNull(ownership.captureForService(first))
        assertTrue(serviceClaim === firstClaim)
        ownership.onServiceCompleted(serviceClaim)
        assertTrue(ownership.current() == null)
        assertTrue(ownership.tryClaim(second) != null)
    }

    @Test
    fun staleCompletionCannotClearANewerClaimForTheIdenticalRestoreRequest() {
        val ownership = RestoreLaunchOwnership()
        val request = ServiceRunRequest.Restore("content://tree/root", "FileForge_Undo_v2_same.jsonl", RestoreSelection.All)

        val firstClaim = checkNotNull(ownership.tryClaim(request))
        ownership.onDispatchFailed(firstClaim)
        val secondClaim = checkNotNull(ownership.tryClaim(request))
        ownership.onServiceCompleted(firstClaim)

        assertTrue(ownership.current() === secondClaim)
        ownership.onDispatchFailed(secondClaim)
        assertTrue(ownership.current() == null)
    }
}
