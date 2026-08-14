package com.fileforge.optimizer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OptimizationServiceContract {
    const val ACTION_START = "com.fileforge.optimizer.action.START"
    const val ACTION_RESTORE = "com.fileforge.optimizer.action.RESTORE"
    const val ACTION_CANCEL = OptimizationServiceActions.ACTION_CANCEL

    const val EXTRA_TREE_URI = "com.fileforge.optimizer.extra.TREE_URI"
    const val EXTRA_RUN_INTENT = "com.fileforge.optimizer.extra.RUN_INTENT"
    const val EXTRA_UNDO_LOG_ID = "com.fileforge.optimizer.extra.UNDO_LOG_ID"
    const val EXTRA_RESTORE_SELECTION = "com.fileforge.optimizer.extra.RESTORE_SELECTION"
}

sealed class ServiceRunRequest {
    abstract val treeUri: String

    data class Optimize(
        override val treeUri: String,
        val runIntent: RunIntent
    ) : ServiceRunRequest()

    data class Restore(
        override val treeUri: String,
        val undoLogId: String,
        val selection: RestoreSelection
    ) : ServiceRunRequest()
}

data class TreeAccessCapabilities(
    val exists: Boolean,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean
)

object OptimizationServiceTreeAccessPolicy {
    fun allows(request: ServiceRunRequest, capabilities: TreeAccessCapabilities): Boolean {
        if (!capabilities.exists || !capabilities.isDirectory || !capabilities.canRead) return false
        val readOnlyOperation =
            request is ServiceRunRequest.Optimize && request.runIntent.dryRun
        return readOnlyOperation || capabilities.canWrite
    }
}

class EncodedServiceRequest(
    val action: String,
    extras: Map<String, Any?>
) {
    val extras: Map<String, Any?> =
        Collections.unmodifiableMap(LinkedHashMap(extras))
}

/** Strict Android-free serialization boundary used by the service Intent adapter. */
object OptimizationServiceRequestCodec {
    fun encode(request: ServiceRunRequest): EncodedServiceRequest = when (request) {
        is ServiceRunRequest.Optimize -> EncodedServiceRequest(
            OptimizationServiceContract.ACTION_START,
            mapOf(
                OptimizationServiceContract.EXTRA_TREE_URI to request.treeUri,
                OptimizationServiceContract.EXTRA_RUN_INTENT to encodeRunIntent(request.runIntent)
            )
        )
        is ServiceRunRequest.Restore -> EncodedServiceRequest(
            OptimizationServiceContract.ACTION_RESTORE,
            mapOf(
                OptimizationServiceContract.EXTRA_TREE_URI to request.treeUri,
                OptimizationServiceContract.EXTRA_UNDO_LOG_ID to request.undoLogId,
                OptimizationServiceContract.EXTRA_RESTORE_SELECTION to encodeSelection(request.selection)
            )
        )
    }

    fun decode(action: String?, extras: Map<String, Any?>): ServiceRunRequest? = try {
        when (action) {
            OptimizationServiceContract.ACTION_START -> decodeOptimize(extras)
            OptimizationServiceContract.ACTION_RESTORE -> decodeRestore(extras)
            else -> null
        }
    } catch (failure: Throwable) {
        if (failure.isVmFatal()) throw failure
        null
    }

    private fun decodeOptimize(extras: Map<String, Any?>): ServiceRunRequest.Optimize? {
        if (extras.keys != OPTIMIZE_EXTRA_KEYS) return null
        val treeUri = extras[OptimizationServiceContract.EXTRA_TREE_URI] as? String
            ?: return null
        if (treeUri.isBlank()) return null
        val serialized = extras[OptimizationServiceContract.EXTRA_RUN_INTENT] as? String
            ?: return null
        val value = parseObject(serialized) ?: return null
        if (value.strictKeySet() != RUN_INTENT_KEYS) return null
        val modeName = value.strictString("mode") ?: return null
        val mode = OptimizeMode.entries.firstOrNull { it.name == modeName } ?: return null
        return ServiceRunRequest.Optimize(
            treeUri,
            RunIntent(
                mode = mode,
                dryRun = value.strictBoolean("dryRun") ?: return null,
                apkLabMode = value.strictBoolean("apkLabMode") ?: return null,
                textMinify = value.strictBoolean("textMinify") ?: return null
            )
        )
    }

    private fun decodeRestore(extras: Map<String, Any?>): ServiceRunRequest.Restore? {
        if (extras.keys != RESTORE_EXTRA_KEYS) return null
        val treeUri = extras[OptimizationServiceContract.EXTRA_TREE_URI] as? String
            ?: return null
        val undoLogId = extras[OptimizationServiceContract.EXTRA_UNDO_LOG_ID] as? String
            ?: return null
        val serialized = extras[OptimizationServiceContract.EXTRA_RESTORE_SELECTION] as? String
            ?: return null
        if (treeUri.isBlank() || undoLogId.isBlank()) return null
        val value = parseObject(serialized) ?: return null
        val selection = when (value.strictString("kind")) {
            SELECTION_ALL -> {
                if (value.strictKeySet() != ALL_SELECTION_KEYS) return null
                RestoreSelection.All
            }
            SELECTION_ENTRIES -> {
                if (value.strictKeySet() != ENTRY_SELECTION_KEYS) return null
                val encodedPaths = value.opt("relativePaths") as? JSONArray ?: return null
                val paths = linkedSetOf<String>()
                for (index in 0 until encodedPaths.length()) {
                    val path = encodedPaths.opt(index) as? String ?: return null
                    if (!paths.add(path)) return null
                }
                RestoreSelection.Entries(paths)
            }
            else -> return null
        }
        return ServiceRunRequest.Restore(treeUri, undoLogId, selection)
    }

    private fun encodeRunIntent(intent: RunIntent): String = JSONObject()
        .put("mode", intent.mode.name)
        .put("dryRun", intent.dryRun)
        .put("apkLabMode", intent.apkLabMode)
        .put("textMinify", intent.textMinify)
        .toString()

    private fun encodeSelection(selection: RestoreSelection): String = when (selection) {
        RestoreSelection.All -> JSONObject().put("kind", SELECTION_ALL).toString()
        is RestoreSelection.Entries -> JSONObject()
            .put("kind", SELECTION_ENTRIES)
            .put("relativePaths", JSONArray(selection.relativePaths.sorted()))
            .toString()
    }

    private fun parseObject(serialized: String): JSONObject? {
        if (!StrictServiceJsonSyntax.isObject(serialized)) return null
        val tokenizer = JSONTokener(serialized)
        val value = tokenizer.nextValue() as? JSONObject ?: return null
        return value.takeIf { tokenizer.nextClean().code == 0 }
    }

    private fun JSONObject.strictString(name: String): String? = opt(name) as? String
    private fun JSONObject.strictBoolean(name: String): Boolean? = opt(name) as? Boolean
    private fun JSONObject.strictKeySet(): Set<String> = buildSet {
        val iterator = keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private val OPTIMIZE_EXTRA_KEYS = setOf(
        OptimizationServiceContract.EXTRA_TREE_URI,
        OptimizationServiceContract.EXTRA_RUN_INTENT
    )
    private val RESTORE_EXTRA_KEYS = setOf(
        OptimizationServiceContract.EXTRA_TREE_URI,
        OptimizationServiceContract.EXTRA_UNDO_LOG_ID,
        OptimizationServiceContract.EXTRA_RESTORE_SELECTION
    )
    private val RUN_INTENT_KEYS = setOf("mode", "dryRun", "apkLabMode", "textMinify")
    private val ALL_SELECTION_KEYS = setOf("kind")
    private val ENTRY_SELECTION_KEYS = setOf("kind", "relativePaths")
    private const val SELECTION_ALL = "ALL"
    private const val SELECTION_ENTRIES = "ENTRIES"
}

/** Strict RFC-8259 syntax gate independent of the platform's lenient JSONTokener. */
internal object StrictServiceJsonSyntax {
    fun isObject(serialized: String): Boolean = try {
        Parser(serialized).parseRootObject()
    } catch (_: IllegalArgumentException) {
        false
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseRootObject(): Boolean {
            skipWhitespace()
            if (peek() != '{') return false
            parseObject()
            skipWhitespace()
            return index == input.length
        }

        private fun parseValue() {
            skipWhitespace()
            when (val character = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                else -> if (character == '-' || isDigit(character)) parseNumber() else fail()
            }
        }

        private fun parseObject() {
            expect('{')
            skipWhitespace()
            val keys = hashSetOf<String>()
            if (consume('}')) return
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail()
                val key = parseString()
                if (!keys.add(key)) fail()
                skipWhitespace()
                expect(':')
                parseValue()
                skipWhitespace()
                if (consume('}')) return
                expect(',')
                skipWhitespace()
                if (peek() == '}') fail()
            }
        }

        private fun parseArray() {
            expect('[')
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                parseValue()
                skipWhitespace()
                if (consume(']')) return
                expect(',')
                skipWhitespace()
                if (peek() == ']') fail()
            }
        }

        private fun parseString(): String {
            expect('"')
            return buildString {
                while (true) {
                    val character = next()
                    when {
                        character == '"' -> return@buildString
                        character == '\\' -> append(parseEscape())
                        character.code < 0x20 -> fail()
                        else -> append(character)
                    }
                }
            }
        }

        private fun parseEscape(): Char = when (val escaped = next()) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                var value = 0
                repeat(4) {
                    val digit = next().digitToIntOrNull(16) ?: fail()
                    value = value * 16 + digit
                }
                value.toChar()
            }
            else -> fail()
        }

        private fun parseNumber() {
            consume('-')
            when (val first = peek()) {
                '0' -> {
                    index++
                    if (isDigit(peek())) fail()
                }
                else -> if (first != null && first in '1'..'9') {
                    while (isDigit(peek())) index++
                } else {
                    fail()
                }
            }
            if (consume('.')) {
                if (!isDigit(peek())) fail()
                while (isDigit(peek())) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (!isDigit(peek())) fail()
                while (isDigit(peek())) index++
            }
        }

        private fun isDigit(character: Char?): Boolean =
            character != null && character in '0'..'9'

        private fun consumeLiteral(value: String) {
            if (!input.regionMatches(index, value, 0, value.length)) fail()
            index += value.length
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') index++
        }

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            index++
            return true
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail()
        }

        private fun peek(): Char? = input.getOrNull(index)

        private fun next(): Char = input.getOrNull(index++) ?: fail()

        private fun fail(): Nothing = throw IllegalArgumentException("Invalid strict JSON")
    }
}

enum class ServiceRestartPolicy { NOT_STICKY }

data class ServiceStartResult(
    val accepted: Boolean,
    val restartPolicy: ServiceRestartPolicy = ServiceRestartPolicy.NOT_STICKY
)

class AtomicCancellationSource : CancellationToken {
    private val cancelled = AtomicBoolean(false)

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)

    override fun throwIfCancelled() {
        if (cancelled.get()) throw OptimizationCancelledException()
    }
}

interface OptimizationServiceRuntime {
    fun enterForeground(initialState: RunState.Running, detailedNotificationsAllowed: Boolean)
    fun execute(task: () -> Unit)
    fun run(
        request: ServiceRunRequest,
        cancellation: CancellationToken,
        onProgress: (ProgressSnapshot) -> Unit
    ): RunState.Terminal
    fun deliverTimeoutTerminal(
        terminal: RunState.Terminal,
        deliverObservers: () -> Unit
    )
    fun stopForegroundAndSelf()
}

class OptimizationBinding internal constructor(
    private val repository: RunStateRepository
) : AutoCloseable {
    private val subscriptions = IdentityHashMap<(RunState) -> Unit, AutoCloseable>()
    private val subscriptionLock = Any()
    private val closed = AtomicBoolean(false)

    val currentState: RunState
        get() = repository.currentState

    fun addListener(listener: (RunState) -> Unit) {
        synchronized(subscriptionLock) {
            if (closed.get() || subscriptions.containsKey(listener)) return
            val subscription = repository.observe(listener)
            if (closed.get()) subscription.close()
            else subscriptions[listener] = subscription
        }
    }

    fun removeListener(listener: (RunState) -> Unit) {
        val subscription = synchronized(subscriptionLock) { subscriptions.remove(listener) }
        subscription?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = synchronized(subscriptionLock) {
            subscriptions.values.toList().also { subscriptions.clear() }
        }
        owned.forEach { it.close() }
    }
}

/** Owns exactly one foreground run and is independent of Activity and Android lifecycle classes. */
class OptimizationServiceController(
    private val repository: RunStateRepository,
    private val runtime: OptimizationServiceRuntime,
    private val notificationPermissionGranted: () -> Boolean
) : AutoCloseable {
    val binding = OptimizationBinding(repository)
    private val active = AtomicReference<ActiveRun?>()

    fun onStartCommand(request: ServiceRunRequest): ServiceStartResult {
        val claimed = ActiveRun(
            dryRun = (request as? ServiceRunRequest.Optimize)?.runIntent?.dryRun == true,
            operationKind = when (request) {
                is ServiceRunRequest.Optimize -> RunOperationKind.OPTIMIZE
                is ServiceRunRequest.Restore -> RunOperationKind.RESTORE
            }
        )
        if (!active.compareAndSet(null, claimed)) return ServiceStartResult(accepted = false)
        val initial = RunState.Running(
            ProgressSnapshot(phase = initialPhase(request)),
            dryRun = claimed.dryRun,
            operationKind = claimed.operationKind
        )
        return try {
            runtime.enterForeground(initial, notificationPermissionGranted())
            val initialCommit = synchronized(claimed.finalityLock) {
                if (claimed.finalized.get()) null else repository.commit(initial)
            }
            if (initialCommit == null) return ServiceStartResult(accepted = false)
            initialCommit.deliver()
            if (synchronized(claimed.finalityLock) { claimed.finalized.get() }) {
                return ServiceStartResult(accepted = false)
            }
            try {
                runtime.execute { runClaimed(claimed, request) }
                ServiceStartResult(accepted = true)
            } catch (failure: Throwable) {
                completeAfterSetupFailure(claimed, failure)
                ServiceStartResult(accepted = false)
            }
        } catch (failure: Throwable) {
            completeAfterSetupFailure(claimed, failure)
            ServiceStartResult(accepted = false)
        }
    }

    fun cancelActive(): Boolean {
        val claimed = active.get() ?: return false
        return synchronized(claimed.finalityLock) {
            !claimed.finalized.get() && claimed.cancellation.cancel()
        }
    }

    internal fun hasActiveRun(): Boolean = active.get() != null

    override fun close() {
        binding.close()
    }

    fun onTimeout() {
        val claimed = active.get() ?: return
        claimed.cancellation.cancel()
        finish(
            claimed,
            RunState.Terminal(
                OptimizationReport(
                    status = RunStatus.CANCELLED,
                    terminalError = TIMEOUT_MESSAGE
                ),
                dryRun = claimed.dryRun,
                operationKind = claimed.operationKind
            ),
            timeoutDelivery = true
        )
    }

    private fun runClaimed(claimed: ActiveRun, request: ServiceRunRequest) {
        val terminal = try {
            synchronized(claimed.finalityLock) {
                if (claimed.finalized.get()) return
                claimed.cancellation.throwIfCancelled()
            }
            runtime.run(request, claimed.cancellation) { snapshot ->
                publishProgress(claimed, snapshot)
            }
        } catch (_: OptimizationCancelledException) {
            RunState.Terminal(
                OptimizationReport(status = RunStatus.CANCELLED),
                dryRun = claimed.dryRun,
                operationKind = claimed.operationKind
            )
        } catch (failure: Throwable) {
            val failed = failedTerminal(failure, claimed.dryRun, claimed.operationKind)
            if (failure.isVmFatal()) {
                try {
                    finish(claimed, failed)
                } catch (finalizationFailure: Throwable) {
                    if (finalizationFailure !== failure) failure.addSuppressed(finalizationFailure)
                }
                throw failure
            }
            failed
        }
        finish(claimed, terminal)
    }

    private fun publishProgress(claimed: ActiveRun, snapshot: ProgressSnapshot) {
        val committed = synchronized(claimed.finalityLock) {
            if (claimed.finalized.get()) null
            else repository.commit(
                RunState.Running(snapshot, claimed.dryRun, claimed.operationKind)
            )
        }
        committed?.deliver()
    }

    private fun completeAfterSetupFailure(claimed: ActiveRun, failure: Throwable) {
        if (failure.isVmFatal()) {
            try {
                finish(
                    claimed,
                    failedTerminal(failure, claimed.dryRun, claimed.operationKind)
                )
            } catch (finalizationFailure: Throwable) {
                if (finalizationFailure !== failure) failure.addSuppressed(finalizationFailure)
            }
            throw failure
        }
        finish(claimed, failedTerminal(failure, claimed.dryRun, claimed.operationKind))
    }

    private fun finish(
        claimed: ActiveRun,
        terminal: RunState.Terminal,
        timeoutDelivery: Boolean = false
    ): Boolean {
        var committed: RunStateRepository.RunStateCommit? = null
        var publicationFailure: Throwable? = null
        synchronized(claimed.finalityLock) {
            if (!claimed.finalized.compareAndSet(false, true)) return false
            try {
                committed = repository.commit(
                    RunState.Terminal(
                        terminal.report,
                        claimed.dryRun,
                        claimed.operationKind
                    )
                )
            } catch (failure: Throwable) {
                publicationFailure = failure
            }
        }
        try {
            try {
                committed?.let { receipt ->
                    if (timeoutDelivery) {
                        runtime.deliverTimeoutTerminal(terminal, receipt::deliver)
                    } else {
                        receipt.deliver()
                    }
                }
            } catch (deliveryFailure: Throwable) {
                publicationFailure = deliveryFailure
            }
            try {
                runtime.stopForegroundAndSelf()
            } catch (stopFailure: Throwable) {
                val primary = publicationFailure
                if (primary == null) publicationFailure = stopFailure
                else if (stopFailure !== primary) primary.addSuppressed(stopFailure)
            }
        } finally {
            active.compareAndSet(claimed, null)
        }
        publicationFailure?.let { throw it }
        return true
    }

    private fun failedTerminal(
        failure: Throwable,
        dryRun: Boolean,
        operationKind: RunOperationKind
    ) = RunState.Terminal(
        OptimizationReport(
            errors = 1,
            status = RunStatus.FAILED,
            terminalError = failure.message ?: failure.javaClass.name
        ),
        dryRun,
        operationKind
    )

    private fun initialPhase(request: ServiceRunRequest): String = when (request) {
        is ServiceRunRequest.Optimize -> if (request.runIntent.dryRun) "analyzing" else "optimizing"
        is ServiceRunRequest.Restore -> "restoring"
    }

    private class ActiveRun(
        val dryRun: Boolean,
        val operationKind: RunOperationKind
    ) {
        val cancellation = AtomicCancellationSource()
        val finalized = AtomicBoolean(false)
        val finalityLock = Any()
    }

    private companion object {
        const val TIMEOUT_MESSAGE =
            "Foreground service time limit reached. Reopen FileForge and start again."
    }
}

/** Android-free action adapter. Invalid commands relinquish only an idle service instance. */
class OptimizationServiceCommandRouter(
    private val controller: OptimizationServiceController,
    private val stopIdleService: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun onCommand(action: String?, extras: Map<String, Any?>): ServiceRestartPolicy {
        if (closed.get()) return ServiceRestartPolicy.NOT_STICKY
        when (action) {
            OptimizationServiceContract.ACTION_START,
            OptimizationServiceContract.ACTION_RESTORE -> {
                val request = OptimizationServiceRequestCodec.decode(action, extras)
                if (request != null) controller.onStartCommand(request) else stopOnlyWhenIdle()
            }
            OptimizationServiceContract.ACTION_CANCEL -> {
                if (extras.isNotEmpty()) stopOnlyWhenIdle()
                else if (controller.hasActiveRun()) controller.cancelActive()
                else stopIdleService()
            }
            else -> stopOnlyWhenIdle()
        }
        return ServiceRestartPolicy.NOT_STICKY
    }

    private fun stopOnlyWhenIdle() {
        if (!controller.hasActiveRun()) stopIdleService()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controller.close()
    }
}
