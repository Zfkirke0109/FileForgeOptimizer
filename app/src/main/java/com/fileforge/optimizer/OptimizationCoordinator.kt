package com.fileforge.optimizer

import java.io.InputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

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
        DocumentPathPolicy.requireSafeSegment(runId)
    }
}

class UndoDurabilityException(
    message: String,
    cause: Throwable,
    val rollback: RollbackResult,
    val fatalPrimary: Throwable? = null
) : java.io.IOException(message, cause)

class EmergencyRollbackException(
    val originalFailure: Throwable,
    val rollbackFailure: Throwable
) : RunInvariantException(
    "Emergency rollback failed after the original document was mutated: " +
        (rollbackFailure.message ?: rollbackFailure.javaClass.name),
    rollbackFailure
) {
    init {
        if (originalFailure !== rollbackFailure) addSuppressed(originalFailure)
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
    private var nativeToolExecutor: NativeToolExecutor? = null

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
        runId: String,
        nativeToolExecutor: NativeToolExecutor?
    ) : this(documentGateway, candidateStore, runId) {
        this.nativeToolExecutor = nativeToolExecutor
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
        nativeToolExecutor: NativeToolExecutor
    ) : this(documentGateway, candidateStore, zipCandidateProcessor) {
        this.nativeToolExecutor = nativeToolExecutor
    }

    internal constructor(
        documentGateway: DocumentGateway,
        candidateStore: CandidateStore,
        zipCandidateProcessor: ZipCandidateProcessor,
        commitContext: CommitContext
    ) : this(documentGateway, candidateStore, zipCandidateProcessor) {
        this.commitContext = commitContext
    }

    internal constructor(
        documentGateway: DocumentGateway,
        candidateStore: CandidateStore,
        zipCandidateProcessor: ZipCandidateProcessor,
        commitContext: CommitContext,
        nativeToolExecutor: NativeToolExecutor?
    ) : this(documentGateway, candidateStore, zipCandidateProcessor, commitContext) {
        this.nativeToolExecutor = nativeToolExecutor
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
        } catch (poisoned: UndoDurabilityException) {
            throw poisoned
        } catch (invariant: RunInvariantException) {
            throw invariant
        } catch (unsupported: UnsupportedZipFeatureException) {
            FileOutcome.Skipped(
                relativePath,
                SkipReason.UNSUPPORTED,
                unsupported.message ?: "Unsupported ZIP feature"
            )
        } catch (limited: ArchiveResourceLimitException) {
            FileOutcome.Skipped(
                relativePath,
                SkipReason.ARCHIVE_LIMIT,
                limited.message ?: "Archive resource limit exceeded"
            )
        } catch (limited: CandidateSizeLimitExceededException) {
            FileOutcome.Skipped(
                relativePath,
                SkipReason.STORAGE_LIMIT,
                limited.message ?: "Candidate cache storage limit exceeded"
            )
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
        val candidate = createCandidate()
        var alignedCandidate: CandidateFile? = null
        var outcome: FileOutcome? = null
        var processingFailure: Throwable? = null
        try {
            outcome = processZipCandidate(
                node,
                relativePath,
                kind,
                runIntent,
                cancellation,
                candidate,
                createAlignedCandidate = {
                    try {
                        createCandidate(APK_ALIGNED_CANDIDATE_SUFFIX).also { alignedCandidate = it }
                    } catch (_: Exception) {
                        null
                    }
                }
            )
        } catch (failure: Throwable) {
            processingFailure = failure
        }
        listOfNotNull(alignedCandidate, candidate).forEach { ownedCandidate ->
            try {
                ownedCandidate.close()
            } catch (cleanup: CandidateCleanupException) {
                val primary = processingFailure
                if (primary != null) primary.addSuppressed(cleanup)
                else if (outcome is FileOutcome.Optimized) {
                    val committed = outcome as FileOutcome.Optimized
                    outcome = committed.copy(note = "${committed.note} Candidate cleanup warning: ${cleanup.message}")
                } else {
                    processingFailure = cleanup
                }
            }
        }
        processingFailure?.let { throw it }
        return checkNotNull(outcome)
    }

    private fun processZipCandidate(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        runIntent: RunIntent,
        cancellation: CancellationToken,
        candidate: CandidateFile,
        createAlignedCandidate: () -> CandidateFile?
    ): FileOutcome {
        val sourceIntegrity = documentGateway.openRead(node).use { source ->
            val tracked = IntegrityTrackingInputStream(source)
            candidate.openOutputStream().use { output ->
                zipCandidateProcessor.optimize(tracked, output, runIntent.mode, cancellation)
            }
            tracked.finish()
        }

        cancellation.throwIfCancelled()
        candidate.openInputStream().use { optimized ->
            zipCandidateProcessor.verify(optimized, cancellation)
        }
        cancellation.throwIfCancelled()
        var acceptedCandidate = candidate
        var toolId = STREAMING_ZIP_TOOL
        var note = "$STREAMING_ZIP_TOOL: candidate verified."
        val executor = nativeToolExecutor
        val alignedCandidate = if (kind == FileKind.APK && executor != null) createAlignedCandidate() else null
        if (kind == FileKind.APK && executor != null && alignedCandidate != null) {
            val aligned = tryNativeZipalign(
                executor,
                candidate,
                alignedCandidate,
                sourceIntegrity.bytes,
                runIntent.mode,
                cancellation
            )
            if (aligned) {
                acceptedCandidate = alignedCandidate
                toolId = "$STREAMING_ZIP_TOOL+zipalign"
                note = "$STREAMING_ZIP_TOOL candidate and native zipalign output verified."
            }
        }
        val newBytes = acceptedCandidate.length
        if (newBytes >= sourceIntegrity.bytes) {
            return FileOutcome.Skipped(relativePath, SkipReason.NO_GAIN)
        }

        return if (runIntent.dryRun) {
            FileOutcome.WouldOptimize(relativePath, sourceIntegrity.bytes, newBytes, toolId, note)
        } else {
            cancellation.throwIfCancelled()
            commitContext?.let { context ->
                commitCandidate(
                    node,
                    relativePath,
                    kind,
                    sourceIntegrity,
                    acceptedCandidate,
                    context,
                    toolId,
                    note,
                    cancellation
                )
            } ?: FileOutcome.Failed(relativePath, "Replacement is unavailable until the backup transaction is installed.")
        }
    }

    private fun tryNativeZipalign(
        executor: NativeToolExecutor,
        input: CandidateFile,
        output: CandidateFile,
        originalBytes: Long,
        mode: OptimizeMode,
        cancellation: CancellationToken
    ): Boolean {
        val execution = try {
            executor.run(
                NativeToolId.ZIPALIGN,
                input.file,
                output.file,
                mode,
                output.guardExternalWrite(cancellation)
            )
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
            return false
        }
        return when (execution) {
            is NativeExecution.Cancelled -> throw OptimizationCancelledException(execution.message)
            is NativeExecution.Success -> try {
                output.validateExternalWrite()
                execution.output.canonicalFile == output.file.canonicalFile &&
                    output.length in 1 until originalBytes &&
                    output.openInputStream().use { zipCandidateProcessor.verify(it, cancellation) }.entries > 0 &&
                    ZipAlignmentVerifier.verify(output.file, cancellation)
            } catch (cancelled: OptimizationCancelledException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
                false
            }
            is NativeExecution.Unavailable,
            is NativeExecution.TimedOut,
            is NativeExecution.Failed -> false
        }
    }

    private fun commitCandidate(
        original: DocumentNode,
        relativePath: String,
        kind: FileKind,
        candidateSource: StreamIntegrity,
        candidate: CandidateFile,
        context: CommitContext,
        toolId: String,
        note: String,
        cancellation: CancellationToken
    ): FileOutcome {
        val backupTree = BackupTree(documentGateway)
        var backup: BackupArtifact? = null
        var originalIntegrity: StreamIntegrity? = null
        var originalMutationStarted = false
        var transactionDurable = false
        var undoAppendStarted = false
        return try {
            val backupPath = backupPath(context.runId, relativePath)
            val backupArtifact = backupTree.create(context.selectedRoot, backupPath)
            backup = backupArtifact
            val backupNode = backupArtifact.file
            val originalSnapshot = documentGateway.openRead(original).use { source ->
                documentGateway.openWrite(backupNode).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination, cancellation) }
            }
            originalIntegrity = originalSnapshot
            val verifiedBackup = documentGateway.openRead(backupNode).use { StreamIntegrityChecker.hash(it, cancellation) }
            check(verifiedBackup == originalSnapshot) { "Backup verification failed" }
            check(originalSnapshot == candidateSource) { "Original changed after candidate generation" }

            cancellation.throwIfCancelled()
            originalMutationStarted = true
            val writtenCandidate = candidate.openInputStream().use { source ->
                documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination, cancellation) }
            }
            val verifiedOriginal = documentGateway.openRead(original).use { StreamIntegrityChecker.hash(it, cancellation) }
            check(verifiedOriginal == writtenCandidate) { "Optimized document verification failed" }

            undoAppendStarted = true
            context.undoEntrySink.appendAndFlush(
                UndoEntry(
                    relativePath = relativePath,
                    originalBytes = originalSnapshot.bytes,
                    optimizedBytes = writtenCandidate.bytes,
                    backupPath = backupPath,
                    originalSha256 = originalSnapshot.sha256,
                    optimizedSha256 = writtenCandidate.sha256,
                    note = note,
                    fileKind = kind,
                    toolId = toolId,
                    completedAt = context.completedAt(),
                    originalDocumentId = original.id
                )
            )
            transactionDurable = true
            FileOutcome.Optimized(relativePath, originalSnapshot.bytes, writtenCandidate.bytes, toolId, note)
        } catch (failure: Throwable) {
            val rollbackBackup = backup?.file
            val rollbackIntegrity = originalIntegrity
            if (!originalMutationStarted) {
                backup?.let { backupTree.cleanupBeforeOriginalMutation(it, failure) }
            }
            val rollback = if (originalMutationStarted && !transactionDurable && rollbackBackup != null && rollbackIntegrity != null) {
                restoreBackup(original, rollbackBackup, rollbackIntegrity)
            } else {
                RollbackResult.NotNeeded
            }
            if (undoAppendStarted && !transactionDurable) {
                throw poisonedUndoFailure(failure, rollback)
            }
            val fatal = fatalPrimary(failure, rollback)
            if (fatal != null) {
                attachSecondaryFailure(fatal, failure, rollback)
                throw fatal
            }
            if (rollback is RollbackResult.Failed) {
                throw EmergencyRollbackException(failure, rollback.cause)
            }
            if (failure is OptimizationCancelledException) {
                throw failure
            }
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure, rollback)
        }
    }

    private fun restoreBackup(original: DocumentNode, backup: DocumentNode, expected: StreamIntegrity): RollbackResult = try {
        val restored = documentGateway.openRead(backup).use { source ->
            documentGateway.openWrite(original).use { destination -> StreamIntegrityChecker.copyAndHash(source, destination, NeverCancelled) }
        }
        val verified = documentGateway.openRead(original).use { StreamIntegrityChecker.hash(it, NeverCancelled) }
        check(restored == expected && verified == expected) { "Rollback verification failed" }
        RollbackResult.Restored
    } catch (failure: Throwable) {
        RollbackResult.Failed(failure)
    }

    private fun poisonedUndoFailure(failure: Throwable, rollback: RollbackResult): UndoDurabilityException {
        val fatal = fatalPrimary(failure, rollback)
        if (fatal != null) attachSecondaryFailure(fatal, failure, rollback)
        return UndoDurabilityException(
            "Undo append/flush failed; the run log is poisoned",
            failure,
            rollback,
            fatal
        ).also { poisoned ->
            if (fatal == null && rollback is RollbackResult.Failed) poisoned.addSuppressed(rollback.cause)
        }
    }

    private fun fatalPrimary(failure: Throwable, rollback: RollbackResult): Throwable? = when {
        failure.isVmFatal() -> failure
        rollback is RollbackResult.Failed && rollback.cause.isVmFatal() -> rollback.cause
        else -> null
    }

    private fun attachSecondaryFailure(primary: Throwable, failure: Throwable, rollback: RollbackResult) {
        if (failure !== primary) primary.addSuppressed(failure)
        if (rollback is RollbackResult.Failed && rollback.cause !== primary) primary.addSuppressed(rollback.cause)
    }

    private fun backupPath(runId: String, relativePath: String): String {
        DocumentPathPolicy.requireSafeSegment(runId)
        DocumentPathPolicy.requireSafeRelative(relativePath)
        return "$BACKUP_PREFIX$runId/$relativePath"
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

    private fun createCandidate(suffix: String = ZIP_CANDIDATE_SUFFIX): CandidateFile =
        runId?.let { candidateStore.create(it, suffix) }
            ?: candidateStore.create(suffix)

    private companion object {
        const val HEADER_BYTES = 8 * 1024
        const val ZIP_CANDIDATE_SUFFIX = ".zip"
        const val APK_ALIGNED_CANDIDATE_SUFFIX = ".aligned.apk"
        const val STREAMING_ZIP_TOOL = "StreamingZipOptimizer"
        const val BACKUP_PREFIX = "FileForge_Backups_"
    }
}

internal object ZipAlignmentVerifier {
    fun verify(file: File, cancellation: CancellationToken): Boolean = try {
        RandomAccessFile(file, "r").use { archive ->
            val eocd = findEndOfCentralDirectory(archive, cancellation) ?: return false
            if (eocd.diskNumber != 0 || eocd.centralDirectoryDisk != 0 || eocd.entriesOnDisk != eocd.entries) {
                return false
            }
            if (eocd.entries <= 0 || eocd.centralDirectoryOffset + eocd.centralDirectorySize > eocd.offset) {
                return false
            }
            var centralPosition = eocd.centralDirectoryOffset
            repeat(eocd.entries) {
                cancellation.throwIfCancelled()
                archive.seek(centralPosition)
                val centralHeader = ByteArray(CENTRAL_HEADER_BYTES)
                archive.readFully(centralHeader)
                if (u32(centralHeader, 0) != CENTRAL_SIGNATURE) return false
                val flags = u16(centralHeader, 8)
                val method = u16(centralHeader, 10)
                val nameLength = u16(centralHeader, 28)
                val extraLength = u16(centralHeader, 30)
                val commentLength = u16(centralHeader, 32)
                val localOffset = u32(centralHeader, 42)
                val nameBytes = ByteArray(nameLength)
                archive.readFully(nameBytes)
                val name = nameBytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else CP437)
                centralPosition += CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength

                archive.seek(localOffset)
                val localHeader = ByteArray(LOCAL_HEADER_BYTES)
                archive.readFully(localHeader)
                if (u32(localHeader, 0) != LOCAL_SIGNATURE) return false
                if (u16(localHeader, 6) != flags || u16(localHeader, 8) != method) return false
                val localNameLength = u16(localHeader, 26)
                val localExtraLength = u16(localHeader, 28)
                if (localNameLength != nameLength) return false
                val localNameBytes = ByteArray(localNameLength)
                archive.readFully(localNameBytes)
                if (!localNameBytes.contentEquals(nameBytes)) return false
                val dataOffset = localOffset + LOCAL_HEADER_BYTES + localNameLength + localExtraLength
                if (dataOffset < 0 || dataOffset > file.length()) return false
                if (method == STORED_METHOD) {
                    val alignment = if (name.endsWith(".so", ignoreCase = true)) PAGE_ALIGNMENT else WORD_ALIGNMENT
                    if (dataOffset % alignment != 0L) return false
                }
            }
            centralPosition <= eocd.centralDirectoryOffset + eocd.centralDirectorySize
        }
    } catch (cancelled: OptimizationCancelledException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun findEndOfCentralDirectory(
        archive: RandomAccessFile,
        cancellation: CancellationToken
    ): EndOfCentralDirectory? {
        val fileLength = archive.length()
        if (fileLength < MIN_EOCD_BYTES) return null
        val tailLength = minOf(fileLength, MAX_EOCD_SEARCH_BYTES).toInt()
        val tail = ByteArray(tailLength)
        val tailStart = fileLength - tailLength
        archive.seek(tailStart)
        archive.readFully(tail)
        for (index in tail.size - MIN_EOCD_BYTES downTo 0) {
            cancellation.throwIfCancelled()
            if (u32(tail, index) != EOCD_SIGNATURE) continue
            val commentLength = u16(tail, index + 20)
            if (index + MIN_EOCD_BYTES + commentLength != tail.size) continue
            val entries = u16(tail, index + 10)
            val centralSize = u32(tail, index + 12)
            val centralOffset = u32(tail, index + 16)
            if (entries == ZIP64_U16 || centralSize == ZIP64_U32 || centralOffset == ZIP64_U32) return null
            return EndOfCentralDirectory(
                offset = tailStart + index,
                diskNumber = u16(tail, index + 4),
                centralDirectoryDisk = u16(tail, index + 6),
                entriesOnDisk = u16(tail, index + 8),
                entries = entries,
                centralDirectorySize = centralSize,
                centralDirectoryOffset = centralOffset
            )
        }
        return null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private data class EndOfCentralDirectory(
        val offset: Long,
        val diskNumber: Int,
        val centralDirectoryDisk: Int,
        val entriesOnDisk: Int,
        val entries: Int,
        val centralDirectorySize: Long,
        val centralDirectoryOffset: Long
    )

    private val CP437: Charset = Charset.forName("Cp437")
    private const val MIN_EOCD_BYTES = 22
    private const val MAX_EOCD_SEARCH_BYTES = MIN_EOCD_BYTES + 0xffffL
    private const val CENTRAL_HEADER_BYTES = 46
    private const val LOCAL_HEADER_BYTES = 30
    private const val UTF8_FLAG = 1 shl 11
    private const val STORED_METHOD = 0
    private const val WORD_ALIGNMENT = 4L
    private const val PAGE_ALIGNMENT = 16L * 1024L
    private const val ZIP64_U16 = 0xffff
    private const val ZIP64_U32 = 0xffff_ffffL
    private const val LOCAL_SIGNATURE = 0x0403_4b50L
    private const val CENTRAL_SIGNATURE = 0x0201_4b50L
    private const val EOCD_SIGNATURE = 0x0605_4b50L
}
