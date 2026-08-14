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

class OptimizeStartDispatchGate {
    var isReplayReady: Boolean = false
        private set
    var isPending: Boolean = false
        private set
    private var releaseAfterSequence: Long = 0
    private var lastObservedKey: Any? = null
    private var suppressEquivalentReplay = false

    fun beginDispatch(observationWatermark: Long): Boolean {
        if (!isReplayReady || isPending) return false
        isPending = true
        releaseAfterSequence = observationWatermark
        return true
    }

    fun awaitReplay() {
        isReplayReady = false
        suppressEquivalentReplay = isPending
    }

    fun onObservedState(state: RunState, sequence: Long) {
        val stateKey = state.semanticKey()
        val unchangedPendingReplay = !isReplayReady && suppressEquivalentReplay &&
            stateKey == lastObservedKey
        if (!isReplayReady) {
            isReplayReady = true
        }
        suppressEquivalentReplay = false
        lastObservedKey = stateKey
        if (unchangedPendingReplay) {
            releaseAfterSequence = maxOf(releaseAfterSequence, sequence)
            return
        }
        if (isPending && sequence > releaseAfterSequence &&
            (state is RunState.Running || state is RunState.Terminal)
        ) {
            isPending = false
        }
    }

    fun onDispatchFailed() {
        isPending = false
    }

    fun allowsStart(baseStartEnabled: Boolean): Boolean =
        baseStartEnabled && isReplayReady && !isPending
}

private fun RunState.semanticKey(): Any = when (this) {
    RunState.Idle -> IdleRunStateKey
    is RunState.Running -> RunningRunStateKey(
        dryRun = dryRun,
        operationKind = operationKind,
        snapshot = snapshot.toSemanticKey()
    )
    is RunState.Terminal -> TerminalRunStateKey(
        dryRun = dryRun,
        operationKind = operationKind,
        report = report.toSemanticKey()
    )
}

private object IdleRunStateKey

private data class RunningRunStateKey(
    val dryRun: Boolean,
    val operationKind: RunOperationKind,
    val snapshot: ProgressSnapshotKey
)

private data class TerminalRunStateKey(
    val dryRun: Boolean,
    val operationKind: RunOperationKind,
    val report: OptimizationReportKey
)

private data class ProgressSnapshotKey(
    val phase: String,
    val currentRelativePath: String?,
    val filesDiscovered: Int,
    val filesProcessed: Int,
    val candidates: Int,
    val optimized: Int,
    val skipsByReason: Map<SkipReason, Int>,
    val errors: Int,
    val bytesRead: Long,
    val bytesWritten: Long,
    val savedBytes: Long,
    val potentialSavingsBytes: Long,
    val totalWork: Int?
)

private data class OptimizationReportKey(
    val scanned: Int,
    val optimized: Int,
    val skipped: Int,
    val errors: Int,
    val savedBytes: Long,
    val candidates: Int,
    val potentialSavingsBytes: Long,
    val bytesRead: Long,
    val bytesWritten: Long,
    val status: RunStatus,
    val skipsByReason: Map<SkipReason, Int>,
    val terminalError: String?,
    val terminalFailures: List<String>,
    val rollbackFailure: String?
)

private fun ProgressSnapshot.toSemanticKey() = ProgressSnapshotKey(
    phase = phase,
    currentRelativePath = currentRelativePath,
    filesDiscovered = filesDiscovered,
    filesProcessed = filesProcessed,
    candidates = candidates,
    optimized = optimized,
    skipsByReason = skipsByReason.toMap(),
    errors = errors,
    bytesRead = bytesRead,
    bytesWritten = bytesWritten,
    savedBytes = savedBytes,
    potentialSavingsBytes = potentialSavingsBytes,
    totalWork = totalWork
)

private fun OptimizationReport.toSemanticKey() = OptimizationReportKey(
    scanned = scanned,
    optimized = optimized,
    skipped = skipped,
    errors = errors,
    savedBytes = savedBytes,
    candidates = candidates,
    potentialSavingsBytes = potentialSavingsBytes,
    bytesRead = bytesRead,
    bytesWritten = bytesWritten,
    status = status,
    skipsByReason = skipsByReason.toMap(),
    terminalError = terminalError,
    terminalFailures = terminalFailures.toList(),
    rollbackFailure = rollbackFailure
)

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
