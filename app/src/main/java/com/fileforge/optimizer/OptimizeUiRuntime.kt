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

    fun beginDispatch(observationWatermark: Long): Boolean {
        if (!isReplayReady || isPending) return false
        isPending = true
        releaseAfterSequence = observationWatermark
        return true
    }

    fun awaitReplay() {
        isReplayReady = false
    }

    fun onObservedState(state: RunState, sequence: Long) {
        if (!isReplayReady) {
            isReplayReady = true
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
