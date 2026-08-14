package com.fileforge.optimizer

import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

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

    fun discover(cancellation: CancellationToken = NeverCancelled): RestoreDiscoveryResult {
        checkOpen(cancellation)
        val runs = mutableListOf<DiscoveredUndoLog>()
        val failures = mutableListOf<RestoreDiscoveryFailure>()
        documentGateway.list(selectedRoot).forEach { child ->
            checkOpen(cancellation)
            if (child.isDirectory || !isRecognizedUndoLogName(child.name)) return@forEach
            try {
                val run = documentGateway.openRead(child).use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { reader -> undoLogs.read(reader) }
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
