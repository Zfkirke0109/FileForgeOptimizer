package com.fileforge.optimizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RunState {
    object Idle : RunState

    class Running(snapshot: ProgressSnapshot, val dryRun: Boolean) : RunState {
        val snapshot: ProgressSnapshot = snapshot.defensiveCopy()
    }

    class Terminal(report: OptimizationReport, val dryRun: Boolean) : RunState {
        private val snapshot: OptimizationReport

        init {
            require(report.status != RunStatus.RUNNING) {
                "Terminal state requires a terminal report"
            }
            snapshot = report.defensiveCopy()
        }

        val report: OptimizationReport
            get() = snapshot.defensiveCopy()
    }
}

interface RunStateStorage {
    fun read(): String?
    fun write(json: String)
}

class RunStateRepository(private val storage: RunStateStorage) {
    private val lock = Any()
    private val subscriptions = LinkedHashSet<Subscription>()
    private var current: RunState = restoreTerminal(storage) ?: RunState.Idle

    /**
     * Immediately replays on the caller thread. Later states are delivered synchronously on the
     * publishing thread. Callbacks never run while the repository lock is held.
     */
    fun observe(listener: (RunState) -> Unit): AutoCloseable {
        val subscription = Subscription(listener)
        val replay = synchronized(lock) {
            subscriptions += subscription
            current
        }
        subscription.replay(replay)
        return subscription
    }

    fun publish(state: RunState) {
        val ownedState = state.defensiveCopy()
        val targets = synchronized(lock) {
            if (ownedState is RunState.Terminal) {
                storage.write(RunStateJsonCodec.encode(ownedState))
            }
            current = ownedState
            subscriptions.toList()
        }
        targets.forEach { subscription -> subscription.deliver(ownedState) }
    }

    private inner class Subscription(
        private val listener: (RunState) -> Unit
    ) : AutoCloseable {
        private val active = AtomicBoolean(true)
        private val replayFinished = CountDownLatch(1)

        fun replay(state: RunState) {
            try {
                deliverNow(state)
            } finally {
                replayFinished.countDown()
            }
        }

        fun deliver(state: RunState) {
            awaitReplay()
            deliverNow(state)
        }

        private fun deliverNow(state: RunState) {
            if (!active.get()) return
            try {
                listener(state)
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
            }
        }

        private fun awaitReplay() {
            var interrupted = false
            while (true) {
                try {
                    replayFinished.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        override fun close() {
            if (!active.compareAndSet(true, false)) return
            synchronized(lock) {
                subscriptions.remove(this)
            }
        }
    }

    companion object {
        @Volatile
        private var androidInstance: RunStateRepository? = null

        fun forAndroid(context: Context): RunStateRepository {
            androidInstance?.let { return it }
            return synchronized(this) {
                androidInstance ?: RunStateRepository(
                    SharedPreferencesRunStateStorage(context.applicationContext)
                ).also { androidInstance = it }
            }
        }

        private fun restoreTerminal(storage: RunStateStorage): RunState.Terminal? =
            try {
                storage.read()?.let(RunStateJsonCodec::decode)
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
                null
            }
    }
}

private class SharedPreferencesRunStateStorage(context: Context) : RunStateStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(TERMINAL_STATE_KEY, null)

    override fun write(json: String) {
        preferences.edit().putString(TERMINAL_STATE_KEY, json).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "fileforge_run_state"
        const val TERMINAL_STATE_KEY = "terminal_state_json"
    }
}

private object RunStateJsonCodec {
    private const val VERSION = 1

    fun encode(state: RunState.Terminal): String {
        val report = state.report
        val skips = JSONObject()
        report.skipsByReason.forEach { (reason, count) -> skips.put(reason.name, count) }
        val terminalFailures = JSONArray()
        report.terminalFailures.forEach { failure -> terminalFailures.put(failure) }
        val encodedReport = JSONObject()
            .put("scanned", report.scanned)
            .put("optimized", report.optimized)
            .put("skipped", report.skipped)
            .put("errors", report.errors)
            .put("savedBytes", report.savedBytes)
            .put("candidates", report.candidates)
            .put("potentialSavingsBytes", report.potentialSavingsBytes)
            .put("bytesRead", report.bytesRead)
            .put("bytesWritten", report.bytesWritten)
            .put("status", report.status.name)
            .put("skipsByReason", skips)
            .put("terminalError", report.terminalError ?: JSONObject.NULL)
            .put("terminalFailures", terminalFailures)
            .put("rollbackFailure", report.rollbackFailure ?: JSONObject.NULL)
        return JSONObject()
            .put("version", VERSION)
            .put("dryRun", state.dryRun)
            .put("report", encodedReport)
            .toString()
    }

    fun decode(json: String): RunState.Terminal? {
        return try {
            val tokenizer = JSONTokener(json)
            val root = tokenizer.nextValue() as? JSONObject ?: return null
            if (tokenizer.nextClean().code != 0) return null
            if (root.requiredInt("version") != VERSION) return null
            val dryRun = root.requiredBoolean("dryRun") ?: return null
            val reportObject = root.requiredObject("report") ?: return null
            val statusName = reportObject.requiredString("status") ?: return null
            val status = RunStatus.entries.firstOrNull { it.name == statusName } ?: return null
            if (status == RunStatus.RUNNING) return null
            val terminalError = reportObject.requiredNullableString("terminalError") ?: return null
            val rollbackFailure = reportObject.requiredNullableString("rollbackFailure") ?: return null
            val report = OptimizationReport(
                scanned = reportObject.requiredNonNegativeInt("scanned") ?: return null,
                optimized = reportObject.requiredNonNegativeInt("optimized") ?: return null,
                skipped = reportObject.requiredNonNegativeInt("skipped") ?: return null,
                errors = reportObject.requiredNonNegativeInt("errors") ?: return null,
                savedBytes = reportObject.requiredNonNegativeLong("savedBytes") ?: return null,
                candidates = reportObject.requiredNonNegativeInt("candidates") ?: return null,
                potentialSavingsBytes =
                    reportObject.requiredNonNegativeLong("potentialSavingsBytes") ?: return null,
                bytesRead = reportObject.requiredNonNegativeLong("bytesRead") ?: return null,
                bytesWritten = reportObject.requiredNonNegativeLong("bytesWritten") ?: return null,
                status = status,
                skipsByReason = reportObject.requiredSkips("skipsByReason") ?: return null,
                terminalError = terminalError.value,
                terminalFailures = reportObject.requiredStringList("terminalFailures") ?: return null,
                rollbackFailure = rollbackFailure.value
            )
            RunState.Terminal(report, dryRun)
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
            null
        }
    }

    private fun JSONObject.requiredObject(name: String): JSONObject? =
        takeIf { has(name) }?.opt(name) as? JSONObject

    private fun JSONObject.requiredString(name: String): String? =
        takeIf { has(name) }?.opt(name) as? String

    private fun JSONObject.requiredBoolean(name: String): Boolean? =
        takeIf { has(name) }?.opt(name) as? Boolean

    private fun JSONObject.requiredInt(name: String): Int? {
        val value = integralValue(name) ?: return null
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun JSONObject.requiredNonNegativeInt(name: String): Int? =
        requiredInt(name)?.takeIf { it >= 0 }

    private fun JSONObject.requiredNonNegativeLong(name: String): Long? =
        integralValue(name)?.takeIf { it >= 0 }

    private fun JSONObject.integralValue(name: String): Long? {
        if (!has(name)) return null
        return when (val value = opt(name)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    private fun JSONObject.requiredNullableString(name: String): NullableField<String>? {
        if (!has(name)) return null
        val value = opt(name)
        return when {
            value == null || value === JSONObject.NULL -> NullableField(null)
            value is String -> NullableField(value)
            else -> null
        }
    }

    private fun JSONObject.requiredStringList(name: String): List<String>? {
        if (!has(name)) return null
        val array = opt(name) as? JSONArray ?: return null
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String ?: return null
            values += value
        }
        return Collections.unmodifiableList(values)
    }

    private fun JSONObject.requiredSkips(name: String): Map<SkipReason, Int>? {
        if (!has(name)) return null
        val encoded = opt(name) as? JSONObject ?: return null
        val skips = LinkedHashMap<SkipReason, Int>()
        val keys = encoded.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val reason = SkipReason.entries.firstOrNull { it.name == key } ?: return null
            val count = encoded.requiredNonNegativeInt(key) ?: return null
            skips[reason] = count
        }
        return Collections.unmodifiableMap(skips)
    }

    private data class NullableField<T>(val value: T?)
}

private fun RunState.defensiveCopy(): RunState = when (this) {
    RunState.Idle -> RunState.Idle
    is RunState.Running -> RunState.Running(snapshot, dryRun)
    is RunState.Terminal -> RunState.Terminal(report, dryRun)
}

private fun ProgressSnapshot.defensiveCopy(): ProgressSnapshot = ProgressSnapshot(
    phase = phase,
    currentRelativePath = currentRelativePath,
    filesDiscovered = filesDiscovered,
    filesProcessed = filesProcessed,
    candidates = candidates,
    optimized = optimized,
    skipsByReason = skipsByReason,
    errors = errors,
    bytesRead = bytesRead,
    bytesWritten = bytesWritten,
    savedBytes = savedBytes,
    potentialSavingsBytes = potentialSavingsBytes,
    totalWork = totalWork
)

private fun OptimizationReport.defensiveCopy(): OptimizationReport = copy(
    skipsByReason = Collections.unmodifiableMap(LinkedHashMap(skipsByReason)),
    terminalFailures = Collections.unmodifiableList(ArrayList(terminalFailures))
)
