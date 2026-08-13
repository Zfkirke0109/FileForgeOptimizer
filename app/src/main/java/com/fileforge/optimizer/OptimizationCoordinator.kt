package com.fileforge.optimizer

import java.io.InputStream

sealed class RollbackResult {
    data object NotNeeded : RollbackResult()
    data object Restored : RollbackResult()
    data class Failed(val cause: Throwable) : RollbackResult()
}

fun interface UndoEntrySink {
    /** The implementation must append one durable record and flush it before returning. */
    fun appendAndFlush(entry: UndoEntry)
}

data class CommitContext(
    val selectedRoot: DocumentNode,
    val runId: String,
    val undoEntrySink: UndoEntrySink,
    val completedAt: () -> String
) {
    init {
        DocumentPathPolicy.requireSafeRelative(runId)
    }
}

internal interface ZipCandidateProcessor {
    fun optimize(
        input: InputStream,
        output: java.io.OutputStream,
        mode: OptimizeMode,
        cancellation: CancellationToken
    ): ZipOptimizationSummary

    fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification
}

internal object StrictStreamingZipCandidateProcessor : ZipCandidateProcessor {
    private val optimizer = StreamingZipOptimizer()

    override fun optimize(
        input: InputStream,
        output: java.io.OutputStream,
        mode: OptimizeMode,
        cancellation: CancellationToken
    ): ZipOptimizationSummary = optimizer.optimize(input, output, mode, cancellation) {}

    override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
        optimizer.verify(input, cancellation)
}

sealed class FileOutcome {
    abstract val relativePath: String

    data class WouldOptimize(
        override val relativePath: String,
        val oldBytes: Long,
        val newBytes: Long,
        val tool: String,
        val note: String
    ) : FileOutcome() {
        val potentialSavingsBytes: Long get() = oldBytes - newBytes
    }

    data class Optimized(
        override val relativePath: String,
        val oldBytes: Long,
        val newBytes: Long,
        val tool: String,
        val note: String
    ) : FileOutcome() {
        val savedBytes: Long get() = oldBytes - newBytes
    }

    data class Skipped(
        override val relativePath: String,
        val reason: SkipReason,
        val note: String = ""
    ) : FileOutcome()

    data class Failed(
        override val relativePath: String,
        val note: String,
        val cause: Throwable? = null,
        val rollback: RollbackResult = RollbackResult.NotNeeded
    ) : FileOutcome()
}

class OptimizationCoordinator(
    private val documentGateway: DocumentGateway,
    private val candidateStore: CandidateStore
) {
    private var runId: String? = null
    private var zipCandidateProcessor: ZipCandidateProcessor = StrictStreamingZipCandidateProcessor
    private var commitContext: CommitContext? = null

    constructor(
        documentGateway: DocumentGateway,
        candidateStore: CandidateStore,
        runId: String
    ) : this(documentGateway, candidateStore) {
        this.runId = runId
    }

    internal constructor(
        documentGateway: DocumentGateway,
        candidateStore: CandidateStore,
        zipCandidateProcessor: ZipCandidateProcessor
    ) : this(documentGateway, candidateStore) {
        this.zipCandidateProcessor = zipCandidateProcessor
    }

    internal constructor(
        documentGateway: DocumentGateway,
        candidateStore: CandidateStore,
        zipCandidateProcessor: ZipCandidateProcessor,
        commitContext: CommitContext
    ) : this(documentGateway, candidateStore, zipCandidateProcessor) {
        this.commitContext = commitContext
    }

    fun process(
        node: DocumentNode,
        relativePath: String,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        return try {
            cancellation.throwIfCancelled()
            val kind = FileTypeDetector.detect(node.name, readHeader(node, cancellation))
            when {
                kind == FileKind.UNSUPPORTED -> FileOutcome.Skipped(relativePath, SkipReason.UNSUPPORTED)
                kind == FileKind.APK && !runIntent.apkLabMode ->
                    FileOutcome.Skipped(relativePath, SkipReason.APK_GUARD)
                kind == FileKind.ZIP_LIKE || kind == FileKind.APK ->
                    processZip(node, relativePath, kind, runIntent, cancellation)
                else -> FileOutcome.Skipped(
                    relativePath,
                    SkipReason.NO_CHANGE,
                    "No bounded-memory optimizer is available for $kind."
                )
            }
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (failure: Exception) {
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure)
        }
    }

    private fun processZip(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        val oldBytes = documentGateway.length(node)
        createCandidate().use { candidate ->
            documentGateway.openRead(node).use { source ->
                candidate.openOutputStream().use { output ->
                    zipCandidateProcessor.optimize(source, output, runIntent.mode, cancellation)
                }
            }

            cancellation.throwIfCancelled()
            candidate.openInputStream().use { optimized ->
                zipCandidateProcessor.verify(optimized, cancellation)
            }
            cancellation.throwIfCancelled()
            val newBytes = candidate.length
            if (newBytes >= oldBytes) {
                return FileOutcome.Skipped(relativePath, SkipReason.NO_GAIN)
            }

            val note = "${STREAMING_ZIP_TOOL}: candidate verified."
            return if (runIntent.dryRun) {
                FileOutcome.WouldOptimize(relativePath, oldBytes, newBytes, STREAMING_ZIP_TOOL, note)
            } else {
                cancellation.throwIfCancelled()
                commitContext?.let { context ->
                    commitCandidate(node, relativePath, kind, oldBytes, candidate, context)
                } ?: FileOutcome.Failed(relativePath, "Replacement is unavailable until the backup transaction is installed.")
            }
        }
    }

    private fun commitCandidate(
        original: DocumentNode,
        relativePath: String,
        kind: FileKind,
        oldBytes: Long,
        candidate: CandidateFile,
        context: CommitContext
    ): FileOutcome {
        var backup: DocumentNode? = null
        var originalIntegrity: StreamIntegrity? = null
        var originalMutationStarted = false
        var originalVerified = false
        return try {
            val backupPath = backupPath(context.runId, relativePath)
            val backupNode = createBackup(context.selectedRoot, backupPath)
            backup = backupNode
            val originalSnapshot = documentGateway.openRead(original).use { source ->
                documentGateway.openWrite(backupNode).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination) }
            }
            originalIntegrity = originalSnapshot
            val verifiedBackup = documentGateway.openRead(backupNode).use(StreamIntegrityChecker::hash)
            check(verifiedBackup == originalSnapshot) { "Backup verification failed" }
            check(originalSnapshot.bytes == oldBytes) { "Original size changed during backup" }

            originalMutationStarted = true
            val writtenCandidate = candidate.openInputStream().use { source ->
                documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination) }
            }
            val verifiedOriginal = documentGateway.openRead(original).use(StreamIntegrityChecker::hash)
            check(verifiedOriginal == writtenCandidate) { "Optimized document verification failed" }
            originalVerified = true

            context.undoEntrySink.appendAndFlush(
                UndoEntry(
                    relativePath = relativePath,
                    originalBytes = originalSnapshot.bytes,
                    optimizedBytes = writtenCandidate.bytes,
                    backupPath = backupPath,
                    originalSha256 = originalSnapshot.sha256,
                    optimizedSha256 = writtenCandidate.sha256,
                    note = "${STREAMING_ZIP_TOOL}: candidate verified.",
                    fileKind = kind,
                    toolId = STREAMING_ZIP_TOOL,
                    completedAt = context.completedAt()
                )
            )
            FileOutcome.Optimized(relativePath, originalSnapshot.bytes, writtenCandidate.bytes, STREAMING_ZIP_TOOL, "${STREAMING_ZIP_TOOL}: candidate verified.")
        } catch (failure: Exception) {
            val rollback = if (originalMutationStarted && !originalVerified && backup != null && originalIntegrity != null) {
                restoreBackup(original, backup, originalIntegrity)
            } else {
                RollbackResult.NotNeeded
            }
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure, rollback)
        }
    }

    private fun restoreBackup(original: DocumentNode, backup: DocumentNode, expected: StreamIntegrity): RollbackResult = try {
        val restored = documentGateway.openRead(backup).use { source ->
            documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination) }
        }
        val verified = documentGateway.openRead(original).use(StreamIntegrityChecker::hash)
        check(restored == expected && verified == expected) { "Rollback verification failed" }
        RollbackResult.Restored
    } catch (failure: Exception) {
        RollbackResult.Failed(failure)
    }

    private fun backupPath(runId: String, relativePath: String): String {
        DocumentPathPolicy.requireSafeRelative(relativePath)
        return "$BACKUP_PREFIX$runId/$relativePath"
    }

    private fun createBackup(root: DocumentNode, backupPath: String): DocumentNode {
        val segments = DocumentPathPolicy.requireSafeRelative(backupPath)
        var parent = root
        segments.dropLast(1).forEach { name ->
            val existing = documentGateway.resolve(parent, name)
            parent = when {
                existing == null -> documentGateway.createDirectory(parent, name)
                existing.isDirectory -> existing
                else -> throw IllegalStateException("Backup path component is not a directory: $name")
            }
        }
        val name = segments.last()
        check(documentGateway.resolve(parent, name) == null) { "Backup already exists: $backupPath" }
        return documentGateway.createFile(parent, "application/octet-stream", name)
    }

    private fun readHeader(node: DocumentNode, cancellation: CancellationToken): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        documentGateway.openRead(node).use { input ->
            while (offset < header.size) {
                cancellation.throwIfCancelled()
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                if (read > 0) offset += read
            }
        }
        return header
    }

    private fun createCandidate(): CandidateFile =
        runId?.let { candidateStore.create(it, ZIP_CANDIDATE_SUFFIX) }
            ?: candidateStore.create(ZIP_CANDIDATE_SUFFIX)

    private companion object {
        const val HEADER_BYTES = 8 * 1024
        const val ZIP_CANDIDATE_SUFFIX = ".zip"
        const val STREAMING_ZIP_TOOL = "StreamingZipOptimizer"
        const val BACKUP_PREFIX = "FileForge_Backups_"
    }
}
