package com.fileforge.optimizer

import java.io.InputStream

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
        val cause: Throwable? = null
    ) : FileOutcome()
}

class OptimizationCoordinator(
    private val documentGateway: DocumentGateway,
    private val candidateStore: CandidateStore
) {
    private var runId: String? = null
    private var zipCandidateProcessor: ZipCandidateProcessor = StrictStreamingZipCandidateProcessor

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
                    processZip(node, relativePath, runIntent, cancellation)
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
                FileOutcome.Failed(
                    relativePath,
                    "Replacement is unavailable until the backup transaction is installed."
                )
            }
        }
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
    }
}
