package com.fileforge.optimizer

import java.io.Writer

sealed class RestoreSelection {
    data object All : RestoreSelection()
    data class Entries(val relativePaths: Set<String>) : RestoreSelection()

    fun includes(relativePath: String): Boolean = when (this) {
        All -> true
        is Entries -> relativePath in relativePaths
    }
}

enum class RestoreEntryStatus {
    RESTORED,
    PATH_REJECTED,
    BACKUP_MISSING,
    BACKUP_SIZE_MISMATCH,
    BACKUP_HASH_MISMATCH,
    ORIGINAL_MISSING,
    WRITE_FAILED,
    RESTORED_VERIFICATION_FAILED
}

data class RestoreEntryResult(
    val relativePath: String,
    val status: RestoreEntryStatus,
    val verification: UndoVerificationLevel,
    val message: String = ""
)

data class RestoreReport(
    val run: UndoRun,
    val entries: List<RestoreEntryResult>,
    val status: RunStatus,
    val restoredCount: Int
)

fun interface RestoreReceiptWriter {
    fun open(name: String): Writer
}

class RestoreCoordinator(
    private val documentGateway: DocumentGateway,
    private val selectedRoot: DocumentNode,
    private val receiptWriter: RestoreReceiptWriter,
    private val clock: () -> String
) {
    fun restore(run: UndoRun, selection: RestoreSelection, cancellation: CancellationToken): RestoreReport {
        val results = mutableListOf<RestoreEntryResult>()
        var status = RunStatus.COMPLETED
        var receipt: Writer? = null
        try {
            for (entry in run.entries) {
                if (!selection.includes(entry.relativePath)) continue
                try {
                    cancellation.throwIfCancelled()
                } catch (_: OptimizationCancelledException) {
                    status = RunStatus.CANCELLED
                    break
                }
                val result = restoreEntry(entry) {
                    if (receipt == null) receipt = receiptWriter.open(receiptName(run.header.runId))
                    receipt!!
                }
                results += result
                if (receipt != null && (result.status == RestoreEntryStatus.RESTORED || result.status == RestoreEntryStatus.WRITE_FAILED || result.status == RestoreEntryStatus.RESTORED_VERIFICATION_FAILED)) {
                    writeReceipt(receipt!!, result)
                }
            }
        } finally {
            receipt?.close()
        }
        if (status == RunStatus.COMPLETED && results.any { it.status != RestoreEntryStatus.RESTORED }) status = RunStatus.COMPLETED_WITH_ERRORS
        return RestoreReport(run, results, status, results.count { it.status == RestoreEntryStatus.RESTORED })
    }

    private fun restoreEntry(entry: UndoEntry, receiptForAttempt: () -> Writer): RestoreEntryResult {
        val original: DocumentNode
        val backup: DocumentNode
        try {
            DocumentPathPolicy.requireSafeRelative(entry.relativePath)
            DocumentPathPolicy.requireSafeRelative(entry.backupPath)
            original = DocumentPathPolicy.resolve(selectedRoot, entry.relativePath, documentGateway)
                ?: return result(entry, RestoreEntryStatus.ORIGINAL_MISSING, "Original is missing")
            backup = DocumentPathPolicy.resolve(selectedRoot, entry.backupPath, documentGateway)
                ?: return result(entry, RestoreEntryStatus.BACKUP_MISSING, "Backup is missing")
        } catch (_: IllegalArgumentException) {
            return result(entry, RestoreEntryStatus.PATH_REJECTED, "Restore paths must stay within the selected root")
        }

        val backupIntegrity = try {
            documentGateway.openRead(backup).use(StreamIntegrityChecker::hash)
        } catch (failure: Exception) {
            return result(entry, RestoreEntryStatus.BACKUP_MISSING, failure.message ?: "Backup cannot be read")
        }
        if (backupIntegrity.bytes != entry.originalBytes) {
            return result(entry, RestoreEntryStatus.BACKUP_SIZE_MISMATCH, "Backup size does not match undo record")
        }
        if (entry.verificationLevel == UndoVerificationLevel.SHA_256 && backupIntegrity.sha256 != entry.originalSha256) {
            return result(entry, RestoreEntryStatus.BACKUP_HASH_MISMATCH, "Backup SHA-256 does not match undo record")
        }

        receiptForAttempt()
        val restored = try {
            documentGateway.openRead(backup).use { source ->
                documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination) }
            }
        } catch (failure: Exception) {
            return result(entry, RestoreEntryStatus.WRITE_FAILED, failure.message ?: "Restore write failed")
        }
        val verified = try {
            documentGateway.openRead(original).use(StreamIntegrityChecker::hash)
        } catch (failure: Exception) {
            return result(entry, RestoreEntryStatus.RESTORED_VERIFICATION_FAILED, failure.message ?: "Restore verification failed")
        }
        val valid = restored.bytes == entry.originalBytes && verified.bytes == entry.originalBytes &&
            (entry.verificationLevel == UndoVerificationLevel.LEGACY_SIZE_ONLY ||
                (restored.sha256 == entry.originalSha256 && verified.sha256 == entry.originalSha256))
        return if (valid) result(entry, RestoreEntryStatus.RESTORED) else
            result(entry, RestoreEntryStatus.RESTORED_VERIFICATION_FAILED, "Restored document does not match undo record")
    }

    private fun result(entry: UndoEntry, status: RestoreEntryStatus, message: String = "") =
        RestoreEntryResult(entry.relativePath, status, entry.verificationLevel, message)

    private fun receiptName(runId: String): String = "FileForge_Restore_${runId}_${clock()}.jsonl"

    private fun writeReceipt(writer: Writer, result: RestoreEntryResult) {
        writer.write("{\"relativePath\":\"${escapeJson(result.relativePath)}\",\"status\":\"${result.status.name}\",\"verification\":\"${result.verification.name}\",\"message\":\"${escapeJson(result.message)}\"}\n")
        writer.flush()
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}
