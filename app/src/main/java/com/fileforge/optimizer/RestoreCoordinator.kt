package com.fileforge.optimizer

import java.io.IOException
import java.io.OutputStreamWriter
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
    RESTORED, PATH_REJECTED, BACKUP_MISSING, BACKUP_SIZE_MISMATCH, BACKUP_HASH_MISMATCH,
    ORIGINAL_MISSING, DIRECTORY_REJECTED, WRITE_FAILED, RESTORED_VERIFICATION_FAILED, RECEIPT_FAILED
}

data class RestoreEntryResult(
    val relativePath: String,
    val status: RestoreEntryStatus,
    val verification: UndoVerificationLevel,
    val message: String = "",
    val repair: RestoreRepairResult = RestoreRepairResult.NotNeeded
)

sealed class RestoreRepairResult {
    data object NotNeeded : RestoreRepairResult()
    data object Restored : RestoreRepairResult()
    data class Failed(val cause: Throwable) : RestoreRepairResult()
}

data class RestoreReport(
    val run: UndoRun,
    val entries: List<RestoreEntryResult>,
    val status: RunStatus,
    val restoredCount: Int,
    val receiptError: String? = null,
    /** Exact receipt identity returned by exclusive receipt creation, if a mutation was attempted. */
    val receiptName: String? = null
)

data class RestoreProgressSnapshot(
    val totalEntries: Int,
    val processedEntries: Int,
    val restoredEntries: Int,
    val lastResult: RestoreEntryResult? = null
) {
    init {
        require(totalEntries >= 0)
        require(processedEntries in 0..totalEntries)
        require(restoredEntries in 0..processedEntries)
    }
}

data class RestoreReceipt(val name: String, val writer: Writer)
class ReceiptAlreadyExistsException(message: String, cause: Throwable? = null) : IOException(message, cause)

fun interface RestoreReceiptWriter {
    fun open(name: String): Writer
}

/** A legacy writer is source-compatible but cannot authorize mutations without exclusive creation. */
interface ExclusiveRestoreReceiptWriter : RestoreReceiptWriter {
    fun openExclusive(name: String): RestoreReceipt
    override fun open(name: String): Writer = openExclusive(name).writer
}

class DocumentGatewayRestoreReceiptWriter(
    private val documentGateway: DocumentGateway,
    private val selectedRoot: DocumentNode
) : ExclusiveRestoreReceiptWriter {
    override fun openExclusive(name: String): RestoreReceipt {
        val node = try {
            documentGateway.createFileExact(selectedRoot, "application/x-ndjson", name)
        } catch (collision: DocumentAlreadyExistsException) {
            throw ReceiptAlreadyExistsException(collision.message ?: "Receipt already exists: $name", collision)
        }
        return RestoreReceipt(name, OutputStreamWriter(documentGateway.openWrite(node), Charsets.UTF_8))
    }
}

class RestoreCoordinator(
    private val documentGateway: DocumentGateway,
    private val selectedRoot: DocumentNode,
    private val receiptWriter: RestoreReceiptWriter,
    private val onProgress: (RestoreProgressSnapshot) -> Unit = {},
    private val clock: () -> String
) {
    fun restore(run: UndoRun, selection: RestoreSelection, cancellation: CancellationToken): RestoreReport {
        val results = mutableListOf<RestoreEntryResult>()
        val selected = run.entries.filter { selection.includes(it.relativePath) }
        publishProgress(selected.size, results, null)
        val safeRunId = try { DocumentPathPolicy.requireSafeSegment(run.header.runId) } catch (_: IllegalArgumentException) {
            return rejectedRunReport(run, selected)
        }
        val timestamp = try { DocumentPathPolicy.requireSafeSegment(clock()) } catch (_: IllegalArgumentException) {
            return rejectedRunReport(run, selected)
        }
        var receipt: RestoreReceipt? = null
        var receiptError: String? = null
        var status = RunStatus.COMPLETED
        var stoppedForAudit = false
        try {
            for (entry in selected) {
                if (stoppedForAudit) break
                try {
                    cancellation.throwIfCancelled()
                    val attempt = restoreEntry(entry, safeRunId, cancellation) {
                        receipt ?: openReceipt(safeRunId, timestamp).also { receipt = it }
                    }
                    recordResult(selected.size, results, attempt.result)
                    if (receipt != null && attempt.attemptedWrite) {
                        try {
                            writeReceipt(receipt!!.writer, attempt.result)
                        } catch (failure: Exception) {
                            receiptError = failure.message ?: failure.javaClass.name
                            stoppedForAudit = true
                        }
                    }
                    if (attempt.cancelled) {
                        status = RunStatus.CANCELLED
                        break
                    }
                } catch (cancelled: OptimizationCancelledException) {
                    status = RunStatus.CANCELLED
                    break
                } catch (failure: ReceiptOpenException) {
                    recordResult(
                        selected.size,
                        results,
                        result(
                            entry,
                            RestoreEntryStatus.RECEIPT_FAILED,
                            failure.message ?: "Receipt could not be opened"
                        )
                    )
                    receiptError = failure.message ?: failure.javaClass.name
                    stoppedForAudit = true
                }
            }
        } finally {
            receipt?.let {
                try { it.writer.close() } catch (failure: Exception) {
                    receiptError = receiptError ?: (failure.message ?: failure.javaClass.name)
                }
            }
        }
        if (status == RunStatus.COMPLETED && (receiptError != null || results.any { it.status != RestoreEntryStatus.RESTORED })) {
            status = RunStatus.COMPLETED_WITH_ERRORS
        }
        return RestoreReport(
            run = run,
            entries = results,
            status = status,
            restoredCount = results.count { it.status == RestoreEntryStatus.RESTORED },
            receiptError = receiptError,
            receiptName = receipt?.name
        )
    }

    private fun rejectedRunReport(run: UndoRun, selected: List<UndoEntry>): RestoreReport {
        val results = mutableListOf<RestoreEntryResult>()
        selected.forEach { entry ->
            recordResult(
                selected.size,
                results,
                result(entry, RestoreEntryStatus.PATH_REJECTED, "Unsafe run ID or receipt timestamp")
            )
        }
        return RestoreReport(run, results, RunStatus.COMPLETED_WITH_ERRORS, 0)
    }

    private fun recordResult(
        totalEntries: Int,
        results: MutableList<RestoreEntryResult>,
        result: RestoreEntryResult
    ) {
        results += result
        publishProgress(totalEntries, results, result)
    }

    private fun publishProgress(
        totalEntries: Int,
        results: List<RestoreEntryResult>,
        lastResult: RestoreEntryResult?
    ) {
        val snapshot = RestoreProgressSnapshot(
            totalEntries = totalEntries,
            processedEntries = results.size,
            restoredEntries = results.count { it.status == RestoreEntryStatus.RESTORED },
            lastResult = lastResult
        )
        try {
            onProgress(snapshot)
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
        }
    }

    private fun restoreEntry(
        entry: UndoEntry,
        runId: String,
        cancellation: CancellationToken,
        receiptForAttempt: () -> RestoreReceipt
    ): RestoreAttempt {
        val expectedBackupPath = "FileForge_Backups_$runId/${entry.relativePath}"
        val original: DocumentNode
        val backup: DocumentNode
        try {
            DocumentPathPolicy.requireSafeRelative(entry.relativePath)
            DocumentPathPolicy.requireSafeRelative(entry.backupPath)
            if (entry.backupPath != expectedBackupPath) return RestoreAttempt(result(entry, RestoreEntryStatus.PATH_REJECTED, "Backup is not bound to this run and entry"))
            original = DocumentPathPolicy.resolve(selectedRoot, entry.relativePath, documentGateway)
                ?: return RestoreAttempt(result(entry, RestoreEntryStatus.ORIGINAL_MISSING, "Original is missing"))
            if (original.isDirectory) return RestoreAttempt(result(entry, RestoreEntryStatus.DIRECTORY_REJECTED, "Restore documents must be files"))
            backup = DocumentPathPolicy.resolve(selectedRoot, expectedBackupPath, documentGateway)
                ?: return RestoreAttempt(result(entry, RestoreEntryStatus.BACKUP_MISSING, "Backup is missing"))
        } catch (_: IllegalArgumentException) {
            return RestoreAttempt(result(entry, RestoreEntryStatus.PATH_REJECTED, "Restore paths must stay within the selected root"))
        }
        if (backup.isDirectory) return RestoreAttempt(result(entry, RestoreEntryStatus.DIRECTORY_REJECTED, "Restore documents must be files"))

        val backupIntegrity = try {
            documentGateway.openRead(backup).use { StreamIntegrityChecker.hash(it, cancellation) }
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (failure: Exception) {
            return RestoreAttempt(result(entry, RestoreEntryStatus.BACKUP_MISSING, failure.message ?: "Backup cannot be read"))
        }
        if (backupIntegrity.bytes != entry.originalBytes) return RestoreAttempt(result(entry, RestoreEntryStatus.BACKUP_SIZE_MISMATCH, "Backup size does not match undo record"))
        if (entry.verificationLevel == UndoVerificationLevel.SHA_256 &&
            !backupIntegrity.sha256.equals(entry.originalSha256, ignoreCase = true)
        ) {
            return RestoreAttempt(result(entry, RestoreEntryStatus.BACKUP_HASH_MISMATCH, "Backup SHA-256 does not match undo record"))
        }
        try { receiptForAttempt() } catch (failure: Exception) { throw ReceiptOpenException(failure) }
        var attemptedWrite = false
        return try {
            val restored = documentGateway.openRead(backup).use { source ->
                attemptedWrite = true
                documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination, cancellation) }
            }
            val verified = documentGateway.openRead(original).use { StreamIntegrityChecker.hash(it, cancellation) }
            val primary = verifiedResult(entry, restored, verified)
            if (primary.status == RestoreEntryStatus.RESTORED) RestoreAttempt(primary, attemptedWrite = attemptedWrite) else
                RestoreAttempt(primary.copy(repair = repairFromBackup(original, backup, entry)), attemptedWrite = attemptedWrite)
        } catch (_: OptimizationCancelledException) {
            val repair = repairFromBackup(original, backup, entry)
            val status = if (repair == RestoreRepairResult.Restored) RestoreEntryStatus.RESTORED else RestoreEntryStatus.WRITE_FAILED
            RestoreAttempt(result(entry, status, "Cancellation repair completed", repair), cancelled = true, attemptedWrite = attemptedWrite)
        } catch (failure: Exception) {
            val repair = if (attemptedWrite) repairFromBackup(original, backup, entry) else RestoreRepairResult.NotNeeded
            RestoreAttempt(result(entry, RestoreEntryStatus.WRITE_FAILED, failure.message ?: "Restore write failed", repair), attemptedWrite = attemptedWrite)
        }
    }

    private fun repairFromBackup(original: DocumentNode, backup: DocumentNode, entry: UndoEntry): RestoreRepairResult = try {
        val restored = documentGateway.openRead(backup).use { source ->
            documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination, NeverCancelled) }
        }
        val verified = documentGateway.openRead(original).use { StreamIntegrityChecker.hash(it, NeverCancelled) }
        check(verifiedResult(entry, restored, verified).status == RestoreEntryStatus.RESTORED) { "Repair verification failed" }
        RestoreRepairResult.Restored
    } catch (failure: Exception) {
        RestoreRepairResult.Failed(failure)
    }

    private fun verifiedResult(entry: UndoEntry, restored: StreamIntegrity, verified: StreamIntegrity): RestoreEntryResult {
        val valid = restored.bytes == entry.originalBytes && verified.bytes == entry.originalBytes &&
            (entry.verificationLevel == UndoVerificationLevel.LEGACY_SIZE_ONLY ||
                (restored.sha256.equals(entry.originalSha256, ignoreCase = true) &&
                    verified.sha256.equals(entry.originalSha256, ignoreCase = true)))
        return if (valid) result(entry, RestoreEntryStatus.RESTORED) else
            result(entry, RestoreEntryStatus.RESTORED_VERIFICATION_FAILED, "Restored document does not match undo record")
    }

    private fun openReceipt(runId: String, timestamp: String): RestoreReceipt {
        val exclusive = receiptWriter as? ExclusiveRestoreReceiptWriter
            ?: throw IOException("Restore receipts require exclusive creation")
        val base = "FileForge_Restore_${runId}_${timestamp}"
        repeat(MAX_RECEIPT_COLLISIONS) { attempt ->
            val suffix = if (attempt == 0) "" else "-$attempt"
            try {
                return exclusive.openExclusive("$base$suffix.jsonl")
            } catch (_: ReceiptAlreadyExistsException) { }
        }
        throw IOException("Could not create a unique restore receipt")
    }

    private fun result(entry: UndoEntry, status: RestoreEntryStatus, message: String = "", repair: RestoreRepairResult = RestoreRepairResult.NotNeeded) =
        RestoreEntryResult(entry.relativePath, status, entry.verificationLevel, message, repair)

    private fun writeReceipt(writer: Writer, result: RestoreEntryResult) {
        writer.write("{\"relativePath\":\"${escapeJson(result.relativePath)}\",\"status\":\"${result.status.name}\",\"verification\":\"${result.verification.name}\",\"message\":\"${escapeJson(result.message)}\"}\n")
        writer.flush()
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        requireWellFormedUtf16(value)
        value.forEach { character -> when (character) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        } }
    }

    private fun requireWellFormedUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "Unpaired surrogate in receipt" }
                index += 2
            } else {
                require(!c.isLowSurrogate()) { "Unpaired surrogate in receipt" }
                index++
            }
        }
    }

    private data class RestoreAttempt(
        val result: RestoreEntryResult,
        val cancelled: Boolean = false,
        val attemptedWrite: Boolean = false
    )
    private class ReceiptOpenException(cause: Throwable) : IOException(cause.message, cause)
    private companion object { const val MAX_RECEIPT_COLLISIONS = 100 }
}
