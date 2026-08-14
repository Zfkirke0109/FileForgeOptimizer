package com.fileforge.optimizer

import java.util.concurrent.atomic.AtomicLong

interface OptimizeProgressIndicator {
    val indeterminate: Boolean
    fun hide()
    fun show()
    fun setIndeterminateMode(value: Boolean)
    fun setMaximum(value: Int)
    fun setProgress(value: Int, animated: Boolean)
}

object OptimizeProgressIndicatorRenderer {
    fun render(
        indicator: OptimizeProgressIndicator,
        indeterminate: Boolean,
        maximum: Int?,
        current: Int?
    ) {
        val modeChanged = indicator.indeterminate != indeterminate
        if (modeChanged) {
            indicator.hide()
            indicator.setIndeterminateMode(indeterminate)
        }
        if (!indeterminate) {
            val safeMaximum = maximum?.takeIf { it > 0 } ?: 1
            indicator.setMaximum(safeMaximum)
            indicator.setProgress(current?.coerceIn(0, safeMaximum) ?: 0, animated = maximum != null)
        }
        if (modeChanged) indicator.show()
    }
}

class LatestValueDispatcher<T : Any>(
    private val schedule: (() -> Unit) -> Unit,
    private val deliver: (T) -> Unit
) {
    private val lock = Any()
    private var latest: T? = null
    private var runnablePending = false
    private var accepting = true

    fun submit(value: T) {
        val shouldSchedule = synchronized(lock) {
            if (!accepting) return
            latest = value
            if (runnablePending) false else {
                runnablePending = true
                true
            }
        }
        if (!shouldSchedule) return
        try {
            schedule(::drain)
        } catch (failure: Throwable) {
            synchronized(lock) {
                runnablePending = false
                latest = null
            }
            throw failure
        }
    }

    fun clear() {
        synchronized(lock) {
            accepting = false
            // Keep runnablePending true until the already-queued runnable drains. A later submit
            // reuses that one queue slot instead of creating a second pending Activity update.
            latest = null
        }
    }

    fun resume() {
        synchronized(lock) { accepting = true }
    }

    private fun drain() {
        val value = synchronized(lock) {
            runnablePending = false
            latest.also { latest = null }
        }
        if (value != null) deliver(value)
    }
}

class SelectedTreeCapabilitiesCache(
    private val reader: () -> SelectedTreeCapabilities
) {
    @Volatile
    var current: SelectedTreeCapabilities = SelectedTreeCapabilities.NONE
        private set

    fun refresh(): SelectedTreeCapabilities = reader().also { current = it }
}

data class SequencedRunState(
    val sequence: Long,
    val state: RunState
)

class RunStateObservationSequencer {
    private val sequence = AtomicLong(0)

    val watermark: Long
        get() = sequence.get()

    fun next(state: RunState): SequencedRunState =
        SequencedRunState(sequence.incrementAndGet(), state)
}

data class PersistedOptimizeDispatch(
    val baselineStateKey: String
)

interface OptimizeDispatchStateStorage {
    fun read(): PersistedOptimizeDispatch?
    fun write(value: PersistedOptimizeDispatch)
    fun clear()
}

private class MemoryOptimizeDispatchStateStorage : OptimizeDispatchStateStorage {
    private var value: PersistedOptimizeDispatch? = null

    override fun read(): PersistedOptimizeDispatch? = value

    override fun write(value: PersistedOptimizeDispatch) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}

class OptimizeStartDispatchGate(
    private val storage: OptimizeDispatchStateStorage = MemoryOptimizeDispatchStateStorage()
) {
    var isReplayReady: Boolean = false
        private set
    private var pendingDispatch: PersistedOptimizeDispatch? = storage.read()
    var isPending: Boolean = pendingDispatch != null
        private set
    private var releaseAfterSequence: Long = 0
    private var lastObservedKey: String? = pendingDispatch?.baselineStateKey

    fun beginDispatch(observationWatermark: Long): Boolean {
        if (!isReplayReady || isPending) return false
        val baselineStateKey = lastObservedKey ?: return false
        val persisted = PersistedOptimizeDispatch(baselineStateKey)
        storage.write(persisted)
        pendingDispatch = persisted
        isPending = true
        releaseAfterSequence = observationWatermark
        return true
    }

    fun awaitReplay() {
        isReplayReady = false
    }

    fun onObservedState(state: RunState, sequence: Long) {
        val stateKey = state.semanticKey()
        if (!isReplayReady) {
            isReplayReady = true
        }
        lastObservedKey = stateKey
        val baselineStateKey = pendingDispatch?.baselineStateKey
        val acknowledged = when (state) {
            is RunState.Running -> true
            is RunState.Terminal -> stateKey != baselineStateKey
            RunState.Idle -> false
        }
        if (isPending && sequence > releaseAfterSequence && acknowledged) {
            clearPendingDispatch()
        }
    }

    fun onDispatchFailed() {
        clearPendingDispatch()
    }

    fun allowsStart(baseStartEnabled: Boolean): Boolean =
        baseStartEnabled && isReplayReady && !isPending

    private fun clearPendingDispatch() {
        storage.clear()
        pendingDispatch = null
        isPending = false
    }
}

private fun RunState.semanticKey(): String {
    val state = this
    return SemanticStateKeyWriter().apply {
        when (state) {
            RunState.Idle -> token("idle")
            is RunState.Running -> {
                token("running")
                boolean(state.dryRun)
                token(state.operationKind.name)
                snapshot(state.snapshot)
            }
            is RunState.Terminal -> {
                token("terminal")
                boolean(state.dryRun)
                token(state.operationKind.name)
                report(state.report)
            }
        }
    }.value()
}

/** Length-prefixed state encoding: persisted equality is exact without hash collisions. */
private class SemanticStateKeyWriter {
    private val value = StringBuilder()

    fun value(): String = value.toString()

    fun token(token: String?) {
        if (token == null) {
            value.append("-1:")
        } else {
            value.append(token.length).append(':').append(token)
        }
    }

    fun boolean(value: Boolean) = token(if (value) "1" else "0")
    fun number(value: Number?) = token(value?.toString())

    fun snapshot(snapshot: ProgressSnapshot) {
        token(snapshot.phase)
        token(snapshot.currentRelativePath)
        number(snapshot.filesDiscovered)
        number(snapshot.filesProcessed)
        number(snapshot.candidates)
        number(snapshot.optimized)
        skips(snapshot.skipsByReason)
        number(snapshot.errors)
        number(snapshot.bytesRead)
        number(snapshot.bytesWritten)
        number(snapshot.savedBytes)
        number(snapshot.potentialSavingsBytes)
        number(snapshot.totalWork)
    }

    fun report(report: OptimizationReport) {
        number(report.scanned)
        number(report.optimized)
        number(report.skipped)
        number(report.errors)
        number(report.savedBytes)
        number(report.candidates)
        number(report.potentialSavingsBytes)
        number(report.bytesRead)
        number(report.bytesWritten)
        token(report.status.name)
        skips(report.skipsByReason)
        token(report.terminalError)
        number(report.terminalFailures.size)
        report.terminalFailures.forEach(::token)
        token(report.rollbackFailure)
    }

    private fun skips(skips: Map<SkipReason, Int>) {
        number(skips.size)
        skips.entries.sortedBy { it.key.ordinal }.forEach { (reason, count) ->
            token(reason.name)
            number(count)
        }
    }
}

data class PendingOptimizeLaunch(
    val request: ServiceRunRequest.Optimize,
    val permissionGranted: Boolean?
)

data class ReadyOptimizeLaunch(
    val request: ServiceRunRequest.Optimize,
    val explainReducedVisibility: Boolean
)

interface PendingOptimizeLaunchStorage {
    fun read(): PendingOptimizeLaunch?
    fun write(value: PendingOptimizeLaunch)
    fun clear()
}

interface PendingOptimizeLaunchRecordStore {
    fun read(): Map<String, Any?>
    fun write(record: Map<String, Any?>)
    fun clear()
}

class StrictPendingOptimizeLaunchStorage(
    private val store: PendingOptimizeLaunchRecordStore
) : PendingOptimizeLaunchStorage {
    override fun read(): PendingOptimizeLaunch? = try {
        val record = store.read()
        if (record.isEmpty()) null
        else PendingOptimizeLaunchRecordCodec.decode(record) ?: clearInvalid()
    } catch (failure: Throwable) {
        if (failure.isVmFatal()) throw failure
        clearInvalid()
    }

    override fun write(value: PendingOptimizeLaunch) {
        store.write(PendingOptimizeLaunchRecordCodec.encode(value))
    }

    override fun clear() {
        store.clear()
    }

    private fun clearInvalid(): PendingOptimizeLaunch? {
        try {
            store.clear()
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
        }
        return null
    }
}

internal object PendingOptimizeLaunchRecordCodec {
    const val KEY_PREFIX = "pending_launch_"
    const val KEY_PRESENT = "pending_launch_present"
    const val KEY_TREE_URI = "pending_launch_tree_uri"
    const val KEY_MODE = "pending_launch_mode"
    const val KEY_DRY_RUN = "pending_launch_dry_run"
    const val KEY_APK_LAB = "pending_launch_apk_lab"
    const val KEY_TEXT_MINIFY = "pending_launch_text_minify"
    const val KEY_PERMISSION_RESULT = "pending_launch_permission_result"
    private const val RESULT_PENDING = "pending"
    private const val RESULT_GRANTED = "granted"
    private const val RESULT_DENIED = "denied"

    val keys: Set<String> = setOf(
        KEY_PRESENT,
        KEY_TREE_URI,
        KEY_MODE,
        KEY_DRY_RUN,
        KEY_APK_LAB,
        KEY_TEXT_MINIFY,
        KEY_PERMISSION_RESULT
    )

    fun encode(value: PendingOptimizeLaunch): Map<String, Any?> = linkedMapOf(
        KEY_PRESENT to true,
        KEY_TREE_URI to value.request.treeUri,
        KEY_MODE to value.request.runIntent.mode.name,
        KEY_DRY_RUN to value.request.runIntent.dryRun,
        KEY_APK_LAB to value.request.runIntent.apkLabMode,
        KEY_TEXT_MINIFY to value.request.runIntent.textMinify,
        KEY_PERMISSION_RESULT to when (value.permissionGranted) {
            null -> RESULT_PENDING
            true -> RESULT_GRANTED
            false -> RESULT_DENIED
        }
    )

    fun decode(record: Map<String, Any?>): PendingOptimizeLaunch? {
        if (record.keys != keys || record[KEY_PRESENT] !is Boolean ||
            record[KEY_PRESENT] != true
        ) {
            return null
        }
        val treeUri = record[KEY_TREE_URI] as? String ?: return null
        if (treeUri.isBlank()) return null
        val modeName = record[KEY_MODE] as? String ?: return null
        val mode = OptimizeMode.entries.firstOrNull { it.name == modeName } ?: return null
        val dryRun = record[KEY_DRY_RUN] as? Boolean ?: return null
        val apkLabMode = record[KEY_APK_LAB] as? Boolean ?: return null
        val textMinify = record[KEY_TEXT_MINIFY] as? Boolean ?: return null
        val permissionGranted = when (record[KEY_PERMISSION_RESULT] as? String) {
            RESULT_PENDING -> null
            RESULT_GRANTED -> true
            RESULT_DENIED -> false
            else -> return null
        }
        return PendingOptimizeLaunch(
            request = ServiceRunRequest.Optimize(
                treeUri = treeUri,
                runIntent = RunIntent(
                    mode = mode,
                    dryRun = dryRun,
                    apkLabMode = apkLabMode,
                    textMinify = textMinify
                )
            ),
            permissionGranted = permissionGranted
        )
    }
}

class PendingOptimizeLaunchCoordinator(
    private val storage: PendingOptimizeLaunchStorage
) {
    fun beginPermissionRequest(request: ServiceRunRequest.Optimize) {
        storage.write(PendingOptimizeLaunch(request, permissionGranted = null))
    }

    fun recordPermissionResult(granted: Boolean) {
        val current = storage.read() ?: return
        storage.write(current.copy(permissionGranted = granted))
    }

    fun pending(): PendingOptimizeLaunch? = storage.read()

    fun takeReady(replayReady: Boolean, startAllowed: Boolean): ReadyOptimizeLaunch? {
        val current = storage.read() ?: return null
        val granted = current.permissionGranted ?: return null
        if (!replayReady || !startAllowed) return null
        storage.clear()
        return ReadyOptimizeLaunch(
            request = current.request,
            explainReducedVisibility = !granted
        )
    }
}

interface OptimizeServicePort {
    fun bind(): Boolean
    fun unbind()
    fun start(request: ServiceRunRequest.Optimize)
    fun cancel()
}

class OptimizeServiceSession(private val port: OptimizeServicePort) {
    var isBound: Boolean = false
        private set

    fun onVisible() {
        if (!isBound) isBound = port.bind()
    }

    fun onHidden() {
        if (!isBound) return
        try {
            port.unbind()
        } finally {
            isBound = false
        }
    }

    fun start(request: ServiceRunRequest.Optimize) {
        port.start(request)
    }

    fun cancel() {
        port.cancel()
    }
}
