package com.fileforge.optimizer

import java.io.InputStreamReader
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DiscoveredUndoLog(
    val undoLogId: String,
    val run: UndoRun
)

data class RestoreDiscoveryFailure(
    val undoLogId: String,
    val message: String
)

data class RestoreDiscoveryResult(
    val runs: List<DiscoveredUndoLog>,
    val failures: List<RestoreDiscoveryFailure>
)

class RestoreDiscoveryVisibilityGate {
    class Generation internal constructor(val id: Long) {
        private val cancelled = AtomicBoolean(false)
        fun cancel() { cancelled.set(true) }
        fun isCancelled(): Boolean = cancelled.get()
        fun cancellationToken(): CancellationToken = CancellationToken {
            if (isCancelled()) throw OptimizationCancelledException("Restore discovery is no longer visible")
        }
    }

    private var nextId = 0L
    private var current: Generation? = null
    var serviceWorkWasCancelled: Boolean = false
        private set

    @Synchronized
    fun enterRestore(): Generation {
        current?.cancel()
        return Generation(++nextId).also { current = it }
    }
    @Synchronized
    fun hideRestore() { current?.cancel(); current = null }
    @Synchronized
    fun currentGeneration(): Generation? = current
    @Synchronized
    fun acceptCompletion(generation: Generation): Boolean =
        current === generation && !generation.isCancelled()
}

/**
 * Associates each visible destination generation with exactly the discovery it created. Queued
 * stale generations are cancelled before constructing provider-facing objects, and hiding closes
 * only the active discovery for that generation.
 */
class RestoreDiscoverySession(
    private val schedule: (() -> Unit) -> Unit,
    private val createDiscovery: () -> RestoreLogDiscovery,
    private val onResult: (RestoreDiscoveryResult) -> Unit,
    private val deliver: ((() -> Unit) -> Unit) = { it() },
    private val onFailure: (Throwable) -> RestoreDiscoveryResult = { failure ->
        RestoreDiscoveryResult(
            emptyList(),
            listOf(
                RestoreDiscoveryFailure(
                    "selected folder",
                    failure.message ?: "Discovery failed"
                )
            )
        )
    }
) : AutoCloseable {
    private data class Active(
        val generation: RestoreDiscoveryVisibilityGate.Generation,
        val discovery: RestoreLogDiscovery
    )

    private val lock = Any()
    private val visibility = RestoreDiscoveryVisibilityGate()
    private var active: Active? = null
    private var closed = false

    fun onVisible() {
        val (generation, previous) = synchronized(lock) {
            if (closed) return
            val previous = active?.discovery
            active = null
            visibility.enterRestore() to previous
        }
        previous?.closeSafely()
        try {
            schedule { runGeneration(generation) }
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
            deliverResult(generation, onFailure(failure))
        }
    }

    fun onHidden() {
        val toClose = synchronized(lock) {
            visibility.hideRestore()
            active?.discovery.also { active = null }
        }
        toClose?.closeSafely()
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            visibility.hideRestore()
            active?.discovery.also { active = null }
        }
        toClose?.closeSafely()
    }

    private fun runGeneration(generation: RestoreDiscoveryVisibilityGate.Generation) {
        val cancellation = generation.cancellationToken()
        if (generation.isCancelled()) return
        val candidate = try {
            createDiscovery()
        } catch (failure: Throwable) {
            if (failure is OptimizationCancelledException) return
            if (failure.isVmFatal()) throw failure
            deliverResult(generation, onFailure(failure))
            return
        }
        val installed = synchronized(lock) {
            if (closed || generation.isCancelled()) false
            else {
                active = Active(generation, candidate)
                true
            }
        }
        if (!installed) {
            candidate.closeSafely()
            return
        }
        val result = try {
            candidate.discover(cancellation)
        } catch (_: OptimizationCancelledException) {
            return
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
            onFailure(failure)
        } finally {
            synchronized(lock) {
                if (active?.generation === generation) active = null
            }
            candidate.closeSafely()
        }
        deliverResult(generation, result)
    }

    private fun deliverResult(
        generation: RestoreDiscoveryVisibilityGate.Generation,
        result: RestoreDiscoveryResult
    ) {
        deliver {
            val accepted = synchronized(lock) {
                !closed && visibility.acceptCompletion(generation)
            }
            if (accepted) onResult(result)
        }
    }

    private fun RestoreLogDiscovery.closeSafely() {
        try {
            close()
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
        }
    }
}

/**
 * Read-only selected-root discovery. It deliberately lists only the direct children supplied by
 * the SAF gateway; it never resolves a name, follows a path, or creates a document.
 */
class RestoreLogDiscovery(
    private val documentGateway: DocumentGateway,
    private val selectedRoot: DocumentNode,
    private val undoLogs: UndoLogRepository = UndoLogRepository()
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activeInput = AtomicReference<InputStream?>(null)
    private val activeInputLock = Any()

    fun discover(cancellation: CancellationToken = NeverCancelled): RestoreDiscoveryResult {
        checkOpen(cancellation)
        val runs = mutableListOf<DiscoveredUndoLog>()
        val failures = mutableListOf<RestoreDiscoveryFailure>()
        documentGateway.list(selectedRoot).forEach { child ->
            checkOpen(cancellation)
            if (child.isDirectory || !isRecognizedUndoLogName(child.name)) return@forEach
            try {
                val input = documentGateway.openRead(child)
                try {
                    synchronized(activeInputLock) {
                        checkOpen(cancellation)
                        activeInput.set(input)
                    }
                } catch (failure: Throwable) {
                    try {
                        input.close()
                    } catch (closeFailure: Throwable) {
                        if (closeFailure.isVmFatal()) throw closeFailure
                    }
                    throw failure
                }
                var reader: InputStreamReader? = null
                val run = try {
                    reader = InputStreamReader(input, Charsets.UTF_8)
                    undoLogs.readStreamingForRestore(
                        reader,
                        CancellationToken { checkOpen(cancellation) }
                    )
                } finally {
                    if (activeInput.compareAndSet(input, null)) {
                        (reader ?: input).close()
                    }
                }
                validate(run)
                runs += DiscoveredUndoLog(child.name, run)
            } catch (cancelled: OptimizationCancelledException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
                failures += RestoreDiscoveryFailure(
                    child.name,
                    failure.message ?: "Undo log could not be parsed"
                )
            }
        }
        return RestoreDiscoveryResult(runs, failures)
    }

    override fun close() {
        closed.set(true)
        val input = synchronized(activeInputLock) { activeInput.getAndSet(null) }
            ?: return
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val fatalHandedToCloser = AtomicBoolean(false)
        val closer = Thread(
            {
                var closeFailure: Throwable? = null
                try {
                    input.close()
                } catch (caught: Throwable) {
                    closeFailure = caught
                    failure.set(caught)
                } finally {
                    completed.countDown()
                }
                closeFailure?.let { caught ->
                    if (caught.isVmFatal() && fatalHandedToCloser.get()) throw caught
                }
            },
            "FileForge-Restore-Discovery-Close"
        ).apply { isDaemon = true }
        try {
            closer.start()
        } catch (startFailure: Throwable) {
            if (startFailure.isVmFatal()) throw startFailure
            return
        }
        val finished = try {
            completed.await(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) fatalHandedToCloser.set(true)
        failure.get()?.let { closeFailure ->
            if (closeFailure.isVmFatal()) throw closeFailure
        }
    }

    private fun checkOpen(cancellation: CancellationToken) {
        cancellation.throwIfCancelled()
        if (closed.get()) throw OptimizationCancelledException("Restore log discovery closed")
    }

    private fun validate(run: UndoRun) {
        DocumentPathPolicy.requireSafeSegment(run.header.runId)
        require(run.entries.isNotEmpty()) { "Undo log has no restorable entries" }
    }

    private fun isRecognizedUndoLogName(name: String): Boolean =
        V2_NAME.matches(name) || LEGACY_NAME.matches(name)

    private companion object {
        const val CLOSE_WAIT_MILLIS = 250L
        val V2_NAME = Regex("FileForge_Undo_v2_[^/\\\\]+\\.jsonl")
        val LEGACY_NAME = Regex("FileForge_Undo_(?!v2_)[^/\\\\]+\\.txt")
    }
}

data class RestoreRunEntryCard(
    val relativePath: String,
    val originalBytes: Long,
    val optimizedBytes: Long,
    val backupPath: String,
    val verificationLabel: String
)

data class RestoreRunCard(
    val undoLogId: String,
    val runId: String,
    val runDate: String,
    val status: RunStatus,
    val entryCount: Int,
    val recoverableBytes: Long,
    val verificationLabel: String,
    val entries: List<RestoreRunEntryCard>
) {
    companion object {
        fun from(undoLogId: String, run: UndoRun): RestoreRunCard {
            val entries = run.entries.map { entry ->
                RestoreRunEntryCard(
                    entry.relativePath,
                    entry.originalBytes,
                    entry.optimizedBytes,
                    entry.backupPath,
                    verificationLabel(entry.verificationLevel)
                )
            }
            return RestoreRunCard(
                undoLogId = undoLogId,
                runId = run.header.runId,
                runDate = run.header.startedAt,
                status = run.status,
                entryCount = entries.size,
                recoverableBytes = run.entries.fold(0L) { total, entry ->
                    if (Long.MAX_VALUE - total < entry.originalBytes) Long.MAX_VALUE
                    else total + entry.originalBytes
                },
                verificationLabel = when (run.entries.map { it.verificationLevel }.toSet()) {
                    setOf(UndoVerificationLevel.SHA_256) -> verificationLabel(UndoVerificationLevel.SHA_256)
                    setOf(UndoVerificationLevel.LEGACY_SIZE_ONLY) -> verificationLabel(UndoVerificationLevel.LEGACY_SIZE_ONLY)
                    else -> "Mixed verification"
                },
                entries = entries
            )
        }

        fun verificationLabel(level: UndoVerificationLevel): String = when (level) {
            UndoVerificationLevel.SHA_256 -> "SHA-256 verified"
            UndoVerificationLevel.LEGACY_SIZE_ONLY -> "Legacy size-only verification"
        }
    }
}
