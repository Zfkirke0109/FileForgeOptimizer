package com.fileforge.optimizer

import java.util.UUID

data class RestoreLaunchClaim internal constructor(val id: String, val requestKey: String)

/**
 * Exact restore-dispatch gate. The owner is intentionally process-memory-only: Activities in the
 * same process share [ProcessRestoreLaunchOwnership.instance], while a dead process cannot leave a
 * disk marker that blocks the next launch.
 */
class RestoreLaunchOwnership {
    private var claim: RestoreLaunchClaim? = null

    @Synchronized
    fun current(): RestoreLaunchClaim? = claim

    @Synchronized
    fun tryClaim(
        request: ServiceRunRequest.Restore,
        optimizePending: Boolean = false
    ): RestoreLaunchClaim? {
        if (optimizePending || claim != null) return null
        return RestoreLaunchClaim(UUID.randomUUID().toString(), request.key()).also { claim = it }
    }

    /** Returns the already-owned request claim so its opaque identity can be serialized. */
    @Synchronized
    fun captureForService(request: ServiceRunRequest.Restore): RestoreLaunchClaim? =
        claim?.takeIf { it.requestKey == request.key() }

    /** Captures only the exact opaque claim presented at service entry. */
    @Synchronized
    fun captureForService(
        request: ServiceRunRequest.Restore,
        expectedId: String
    ): RestoreLaunchClaim? = claim?.takeIf {
        it.requestKey == request.key() && it.id == expectedId
    }

    /** Used to release a malformed/rejected command that still carries an exact dispatch token. */
    @Synchronized
    fun captureForService(expectedId: String): RestoreLaunchClaim? = claim?.takeIf { it.id == expectedId }

    @Synchronized
    fun onDispatchFailed(expected: RestoreLaunchClaim) = release(expected)

    @Synchronized
    fun onServiceCompleted(expected: RestoreLaunchClaim) = release(expected)

    @Synchronized
    fun onObserved(state: RunState) {
        // Generic run state deliberately has no restore-request identity and cannot release a claim.
    }

    private fun release(expected: RestoreLaunchClaim) {
        if (claim === expected) claim = null
    }
}

internal object ProcessRestoreLaunchOwnership {
    val instance = RestoreLaunchOwnership()
}

private fun ServiceRunRequest.Restore.key(): String = buildString {
    append(treeUri.length).append(':').append(treeUri)
    append(undoLogId.length).append(':').append(undoLogId)
    when (selection) {
        RestoreSelection.All -> append("all")
        is RestoreSelection.Entries -> selection.relativePaths.sorted().forEach { append(it.length).append(':').append(it) }
    }
}
