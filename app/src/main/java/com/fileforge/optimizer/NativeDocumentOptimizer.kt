package com.fileforge.optimizer

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.CRC32

class NativeDocumentOptimizer(
    private val documentGateway: DocumentGateway,
    private val candidateStore: CandidateStore,
    executor: NativeToolExecutor,
    private val fallback: ByteArrayOptimizerAdapter,
    private val commitContext: CommitContext? = null
) {
    private val nativePolicy = NativeFallbackOptimizer(executor)
    private val committer = NativeCandidateCommitter(documentGateway)

    fun process(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        val tool = toolFor(kind, runIntent.mode)
            ?: return fallback.process(node, relativePath, kind, runIntent, cancellation)
        return try {
            processNative(node, relativePath, kind, tool, runIntent, cancellation)
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (poisoned: UndoDurabilityException) {
            throw poisoned
        } catch (invariant: RunInvariantException) {
            throw invariant
        } catch (limited: CandidateSizeLimitExceededException) {
            FileOutcome.Skipped(
                relativePath,
                SkipReason.STORAGE_LIMIT,
                limited.message ?: "Native candidate storage limit exceeded"
            )
        } catch (failure: Exception) {
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure)
        }
    }

    private fun processNative(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        tool: NativeToolId,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        val stagedInput = candidateStore.create(INPUT_SUFFIX)
        var candidateOutput: CandidateFile? = null
        var outcome: FileOutcome? = null
        var processingFailure: Throwable? = null
        try {
            val sourceIntegrity = documentGateway.openRead(node).use { source ->
                stagedInput.openOutputStream().use { destination ->
                    StreamIntegrityChecker.copyAndHash(source, destination, cancellation)
                }
            }
            val protectedReason = NativeInputSafety.protectedReason(kind, stagedInput.file, cancellation)
            outcome = if (protectedReason != null) {
                FileOutcome.Skipped(relativePath, SkipReason.NO_CHANGE, protectedReason)
            } else {
                val output = candidateStore.create(OUTPUT_SUFFIX).also { candidateOutput = it }
                nativePolicy.runOrFallback(
                    tool = tool,
                    input = stagedInput.file,
                    output = output.file,
                    mode = runIntent.mode,
                    cancellation = output.guardExternalWrite(cancellation),
                    verify = { candidate ->
                        output.validateExternalWrite()
                        NativeCandidateVerifier.verify(kind, candidate, cancellation)
                    },
                    accept = {
                        acceptCandidate(
                            node,
                            relativePath,
                            kind,
                            tool,
                            sourceIntegrity,
                            output,
                            runIntent,
                            cancellation
                        )
                    },
                    fallback = { fallback.process(node, relativePath, kind, runIntent, cancellation) }
                )
            }
        } catch (failure: Throwable) {
            processingFailure = failure
        }

        listOfNotNull(candidateOutput, stagedInput).forEach { candidate ->
            try {
                candidate.close()
            } catch (cleanup: CandidateCleanupException) {
                val primary = processingFailure
                if (primary != null) {
                    primary.addSuppressed(cleanup)
                } else if (outcome is FileOutcome.Optimized) {
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

    private fun acceptCandidate(
        node: DocumentNode,
        relativePath: String,
        kind: FileKind,
        tool: NativeToolId,
        sourceIntegrity: StreamIntegrity,
        candidate: CandidateFile,
        runIntent: RunIntent,
        cancellation: CancellationToken
    ): FileOutcome {
        cancellation.throwIfCancelled()
        val newBytes = candidate.length
        val toolId = tool.manifestId
        val note = "$toolId native candidate verified."
        if (runIntent.dryRun) {
            return FileOutcome.WouldOptimize(relativePath, sourceIntegrity.bytes, newBytes, toolId, note)
        }
        val context = commitContext
            ?: return FileOutcome.Failed(relativePath, "Replacement is unavailable until the backup transaction is installed.")
        return committer.commit(
            original = node,
            relativePath = relativePath,
            kind = kind,
            expectedOriginal = sourceIntegrity,
            candidate = candidate,
            context = context,
            toolId = toolId,
            note = note,
            cancellation = cancellation
        )
    }

    private fun toolFor(kind: FileKind, mode: OptimizeMode): NativeToolId? = when (kind) {
        FileKind.PDF -> NativeToolId.QPDF
        FileKind.JPEG -> NativeToolId.JPEGTRAN
        FileKind.PNG -> if (mode == OptimizeMode.AGGRESSIVE) NativeToolId.ZOPFLIPNG else NativeToolId.OXIPNG
        else -> null
    }

    private companion object {
        const val INPUT_SUFFIX = ".native-input"
        const val OUTPUT_SUFFIX = ".native-output"
    }
}

private object NativeInputSafety {
    fun protectedReason(kind: FileKind, input: File, cancellation: CancellationToken): String? = when (kind) {
        FileKind.PDF -> if (pdfContainsSignatureName(input, cancellation)) {
            "Signed PDF was preserved without modification."
        } else null
        FileKind.PNG -> if (pngContainsChunk(input, "acTL", cancellation)) {
            "Animated PNG was preserved without modification."
        } else null
        else -> null
    }

    private fun pdfContainsSignatureName(file: File, cancellation: CancellationToken): Boolean {
        PushbackInputStream(BufferedInputStream(FileInputStream(file)), 2).use { input ->
            var inName = false
            var overflow = false
            var processed = 0
            val name = StringBuilder(MAX_PDF_NAME_LENGTH)

            fun finishName(): Boolean {
                if (!inName) return false
                val protected = !overflow && name.toString() in PDF_SIGNATURE_NAMES
                inName = false
                overflow = false
                name.setLength(0)
                return protected
            }

            fun append(value: Int) {
                if (name.length < MAX_PDF_NAME_LENGTH) name.append(value.toChar()) else overflow = true
            }

            while (true) {
                if (processed++ % CANCELLATION_POLL_BYTES == 0) cancellation.throwIfCancelled()
                val value = input.read()
                if (value < 0) return finishName()
                if (!inName) {
                    if (value == '/'.code) inName = true
                    continue
                }
                if (value == '#'.code) {
                    val firstByte = input.read()
                    val secondByte = input.read()
                    val first = firstByte.hexDigit()
                    val second = secondByte.hexDigit()
                    if (first >= 0 && second >= 0) {
                        append((first shl 4) or second)
                    } else {
                        if (secondByte >= 0) input.unread(secondByte)
                        if (firstByte >= 0) input.unread(firstByte)
                        append(value)
                    }
                } else if (value.isPdfDelimiterOrWhitespace()) {
                    if (finishName()) return true
                    if (value == '/'.code) inName = true
                } else {
                    append(value)
                }
            }
        }
    }

    private fun Int.hexDigit(): Int = when (this) {
        in '0'.code..'9'.code -> this - '0'.code
        in 'A'.code..'F'.code -> this - 'A'.code + 10
        in 'a'.code..'f'.code -> this - 'a'.code + 10
        else -> -1
    }

    private fun Int.isPdfDelimiterOrWhitespace(): Boolean =
        this == 0 || this == 9 || this == 10 || this == 12 || this == 13 || this == 32 ||
            this == '('.code || this == ')'.code || this == '<'.code || this == '>'.code ||
            this == '['.code || this == ']'.code || this == '{'.code || this == '}'.code ||
            this == '/'.code || this == '%'.code

    private fun pngContainsChunk(file: File, wanted: String, cancellation: CancellationToken): Boolean {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val signature = ByteArray(8)
                input.readFully(signature)
                while (true) {
                    cancellation.throwIfCancelled()
                    val length = input.readInt()
                    if (length < 0) return false
                    val typeBytes = ByteArray(4)
                    input.readFully(typeBytes)
                    val type = typeBytes.toString(Charsets.US_ASCII)
                    if (type == wanted) return true
                    skipExactly(input, length.toLong() + 4L, cancellation)
                    if (type == "IEND") return false
                }
            }
            false
        } catch (cancelled: OptimizationCancelledException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private val PDF_SIGNATURE_NAMES = setOf("ByteRange", "Sig", "SigRef")
    private const val MAX_PDF_NAME_LENGTH = 64
    private const val CANCELLATION_POLL_BYTES = 32 * 1024
}

private object NativeCandidateVerifier {
    fun verify(kind: FileKind, file: File, cancellation: CancellationToken): Boolean = try {
        when (kind) {
            FileKind.PDF -> verifyPdf(file, cancellation)
            FileKind.PNG -> verifyPng(file, cancellation)
            FileKind.JPEG -> verifyJpeg(file, cancellation)
            else -> false
        }
    } catch (cancelled: OptimizationCancelledException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun verifyPdf(file: File, cancellation: CancellationToken): Boolean {
        if (file.length() <= 10L) return false
        FileInputStream(file).use { input ->
            val header = ByteArray(5)
            if (!readExactly(input, header, cancellation) || header.toString(Charsets.ISO_8859_1) != "%PDF-") {
                return false
            }
        }
        return containsNeedle(file, "%%EOF".toByteArray(Charsets.ISO_8859_1), cancellation)
    }

    private fun verifyJpeg(file: File, cancellation: CancellationToken): Boolean {
        var previous = -1
        var last = -1
        var sawStartOfScan = false
        FileInputStream(file).use { input ->
            val first = input.read()
            val second = input.read()
            if (first != 0xff || second != 0xd8) return false
            previous = first
            last = second
            val buffer = ByteArray(32 * 1024)
            while (true) {
                cancellation.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                for (offset in 0 until read) {
                    val value = buffer[offset].toInt() and 0xff
                    if (last == 0xff && value == 0xda) sawStartOfScan = true
                    previous = last
                    last = value
                }
            }
        }
        return sawStartOfScan && previous == 0xff && last == 0xd9
    }

    private fun verifyPng(file: File, cancellation: CancellationToken): Boolean {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            if (!signature.contentEquals(PNG_SIGNATURE)) return false
            var chunks = 0
            var sawIdat = false
            while (chunks < MAX_PNG_CHUNKS) {
                cancellation.throwIfCancelled()
                val length = input.readInt()
                if (length < 0) return false
                val typeBytes = ByteArray(4)
                input.readFully(typeBytes)
                val type = typeBytes.toString(Charsets.US_ASCII)
                if (chunks == 0 && type != "IHDR") return false
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                val buffer = ByteArray(32 * 1024)
                while (remaining > 0) {
                    cancellation.throwIfCancelled()
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) return false
                    if (read == 0) continue
                    crc.update(buffer, 0, read)
                    remaining -= read
                }
                val recordedCrc = input.readInt().toLong() and 0xffff_ffffL
                if (recordedCrc != crc.value) return false
                chunks++
                if (type == "IDAT") sawIdat = true
                if (type == "IEND") return length == 0 && sawIdat && input.read() == -1
            }
            return false
        }
    }

    private fun containsNeedle(file: File, needle: ByteArray, cancellation: CancellationToken): Boolean {
        var matched = 0
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                cancellation.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) return false
                for (offset in 0 until read) {
                    val value = buffer[offset]
                    matched = when {
                        value == needle[matched] -> matched + 1
                        value == needle[0] -> 1
                        else -> 0
                    }
                    if (matched == needle.size) return true
                }
            }
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    private const val MAX_PNG_CHUNKS = 1_000_000
}

private class NativeCandidateCommitter(private val documentGateway: DocumentGateway) {
    fun commit(
        original: DocumentNode,
        relativePath: String,
        kind: FileKind,
        expectedOriginal: StreamIntegrity,
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
        var mutationCommitted = false
        var undoDurable = false
        var undoAppendStarted = false
        return try {
            val backupPath = backupPath(context.runId, relativePath)
            val backupArtifact = backupTree.create(context.selectedRoot, backupPath)
            backup = backupArtifact
            val backedUp = documentGateway.openRead(original).use { source ->
                documentGateway.openWrite(backupArtifact.file).use { destination ->
                    StreamIntegrityChecker.copyAndHash(source, destination, cancellation)
                }
            }
            originalIntegrity = backedUp
            check(backedUp == expectedOriginal) { "Original changed before backup completed" }
            val verifiedBackup = documentGateway.openRead(backupArtifact.file).use {
                StreamIntegrityChecker.hash(it, cancellation)
            }
            check(verifiedBackup == expectedOriginal) { "Backup verification failed" }

            val expectedCandidate = candidate.openInputStream().use { source ->
                StreamIntegrityChecker.hash(source, cancellation)
            }
            undoAppendStarted = true
            context.undoEntrySink.appendAndFlush(
                UndoEntry(
                    relativePath = relativePath,
                    originalBytes = expectedOriginal.bytes,
                    optimizedBytes = expectedCandidate.bytes,
                    backupPath = backupPath,
                    originalSha256 = expectedOriginal.sha256,
                    optimizedSha256 = expectedCandidate.sha256,
                    note = note,
                    fileKind = kind,
                    toolId = toolId,
                    completedAt = context.completedAt(),
                    originalDocumentId = original.id
                )
            )
            undoDurable = true

            cancellation.throwIfCancelled()
            val liveOriginal = documentGateway.openRead(original).use { source ->
                StreamIntegrityChecker.hash(source, cancellation)
            }
            check(liveOriginal == expectedOriginal) { "Original changed immediately before replacement" }
            cancellation.throwIfCancelled()
            originalMutationStarted = true
            val writtenCandidate = candidate.openInputStream().use { source ->
                documentGateway.openWrite(original).use { destination ->
                    StreamIntegrityChecker.copyAndHash(source, destination, cancellation)
                }
            }
            val verifiedOriginal = documentGateway.openRead(original).use {
                StreamIntegrityChecker.hash(it, cancellation)
            }
            check(writtenCandidate == expectedCandidate && verifiedOriginal == expectedCandidate) {
                "Optimized document verification failed"
            }
            mutationCommitted = true
            FileOutcome.Optimized(relativePath, expectedOriginal.bytes, writtenCandidate.bytes, toolId, note)
        } catch (failure: Throwable) {
            val rollbackBackup = backup?.file
            val rollbackIntegrity = originalIntegrity
            if (!originalMutationStarted && !undoAppendStarted) {
                backup?.let { backupTree.cleanupBeforeOriginalMutation(it, failure) }
            }
            val rollback = if (
                originalMutationStarted && !mutationCommitted && rollbackBackup != null && rollbackIntegrity != null
            ) restoreBackup(original, rollbackBackup, rollbackIntegrity) else RollbackResult.NotNeeded
            if (undoAppendStarted && !undoDurable) throw poisonedUndoFailure(failure, rollback)
            val fatal = fatalPrimary(failure, rollback)
            if (fatal != null) {
                attachSecondaryFailure(fatal, failure, rollback)
                throw fatal
            }
            if (rollback is RollbackResult.Failed) throw EmergencyRollbackException(failure, rollback.cause)
            if (failure is OptimizationCancelledException) throw failure
            FileOutcome.Failed(relativePath, failure.message ?: failure.javaClass.name, failure, rollback)
        }
    }

    private fun restoreBackup(original: DocumentNode, backup: DocumentNode, expected: StreamIntegrity): RollbackResult = try {
        val restored = documentGateway.openRead(backup).use { source ->
            documentGateway.openWrite(original).use { destination ->
                StreamIntegrityChecker.copyAndHash(source, destination, NeverCancelled)
            }
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
        return "FileForge_Backups_$runId/$relativePath"
    }
}

private fun readExactly(input: InputStream, target: ByteArray, cancellation: CancellationToken): Boolean {
    var offset = 0
    while (offset < target.size) {
        cancellation.throwIfCancelled()
        val read = input.read(target, offset, target.size - offset)
        if (read < 0) return false
        if (read > 0) offset += read
    }
    return true
}

private fun skipExactly(input: InputStream, byteCount: Long, cancellation: CancellationToken) {
    var remaining = byteCount
    val buffer = ByteArray(32 * 1024)
    while (remaining > 0) {
        cancellation.throwIfCancelled()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw java.io.EOFException("Unexpected end of native staged input")
        if (read > 0) remaining -= read.toLong()
    }
}
