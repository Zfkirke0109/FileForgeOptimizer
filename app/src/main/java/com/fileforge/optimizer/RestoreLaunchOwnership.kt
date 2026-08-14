package com.fileforge.optimizer

import android.content.Context

data class RestoreLaunchClaimRecord(val id: Long, val requestKey: String)
data class RestoreLaunchClaim internal constructor(val id: Long, val requestKey: String)

interface RestoreLaunchClaimStore {
    fun read(): RestoreLaunchClaimRecord?
    fun write(record: RestoreLaunchClaimRecord)
    fun clear()
}

private object InMemoryRestoreLaunchClaimStore : RestoreLaunchClaimStore {
    private var value: RestoreLaunchClaimRecord? = null
    override fun read(): RestoreLaunchClaimRecord? = value
    override fun write(record: RestoreLaunchClaimRecord) { value = record }
    override fun clear() { value = null }
}

/** Exact-request gate shared by restore presentations and the foreground service. */
class RestoreLaunchOwnership(private val store: RestoreLaunchClaimStore = InMemoryRestoreLaunchClaimStore) {
    private var claim: RestoreLaunchClaim? = store.read()?.let { RestoreLaunchClaim(it.id, it.requestKey) }
    private var nextId = claim?.id ?: 0L

    @Synchronized fun current(): RestoreLaunchClaim? = claim

    @Synchronized fun tryClaim(request: ServiceRunRequest.Restore, optimizePending: Boolean = false): RestoreLaunchClaim? {
        if (optimizePending || claim != null) return null
        return RestoreLaunchClaim(++nextId, request.key()).also {
            claim = it
            store.write(RestoreLaunchClaimRecord(it.id, it.requestKey))
        }
    }

    @Synchronized fun onDispatchFailed(expected: RestoreLaunchClaim) = release(expected)
    @Synchronized fun onServiceRejected(request: ServiceRunRequest.Restore) { claim?.takeIf { it.requestKey == request.key() }?.let(::release) }
    @Synchronized fun onServiceAccepted(request: ServiceRunRequest.Restore) { /* exact bridge retains claim until completion */ }
    @Synchronized fun onServiceCompleted(request: ServiceRunRequest.Restore) { claim?.takeIf { it.requestKey == request.key() }?.let(::release) }
    @Synchronized fun onServiceCompleted(expected: RestoreLaunchClaim) = release(expected)
    @Synchronized fun onObserved(state: RunState) { /* generic state has no exact request identity */ }

    private fun release(expected: RestoreLaunchClaim) {
        if (claim?.id == expected.id) { claim = null; store.clear() }
    }
}

internal object ProcessRestoreLaunchOwnership {
    @Volatile private var initialized = false
    @Volatile private var owner = RestoreLaunchOwnership()
    val instance: RestoreLaunchOwnership get() = owner

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                owner = RestoreLaunchOwnership(SharedPreferencesRestoreLaunchClaimStore(context.applicationContext))
                initialized = true
            }
        }
    }
}

private class SharedPreferencesRestoreLaunchClaimStore(context: Context) : RestoreLaunchClaimStore {
    private val preferences = context.getSharedPreferences("fileforge_restore_launch", Context.MODE_PRIVATE)
    override fun read(): RestoreLaunchClaimRecord? {
        val id = preferences.getLong("id", -1)
        val key = preferences.getString("key", null)
        return if (id >= 0 && key != null) RestoreLaunchClaimRecord(id, key) else null
    }
    override fun write(record: RestoreLaunchClaimRecord) {
        preferences.edit().putLong("id", record.id).putString("key", record.requestKey).commit()
    }
    override fun clear() { preferences.edit().clear().commit() }
}

private fun ServiceRunRequest.Restore.key(): String = buildString {
    append(treeUri.length).append(':').append(treeUri)
    append(undoLogId.length).append(':').append(undoLogId)
    when (selection) {
        RestoreSelection.All -> append("all")
        is RestoreSelection.Entries -> selection.relativePaths.sorted().forEach { append(it.length).append(':').append(it) }
    }
}
