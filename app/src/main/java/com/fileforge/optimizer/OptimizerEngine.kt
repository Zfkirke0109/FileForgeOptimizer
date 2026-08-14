package com.fileforge.optimizer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Android-free orchestration for one selected-tree optimization run. */
class OptimizerEngine(
    private val documentGateway: DocumentGateway,
    private val selectedRoot: DocumentNode,
    private val candidateStore: CandidateStore,
    private val runId: String,
    private val runIntent: RunIntent,
    private val startedAt: () -> String,
    private val completedAt: () -> String,
    private val appVersion: String,
    private val buildVariant: String
) {
    private val undoRepository = UndoLogRepository()
    private val runClaimed = AtomicBoolean(false)
    private var compatibilityLogger: ((String) -> Unit)? = null

    init {
        require(selectedRoot.isDirectory) { "The selected root must be a directory" }
        DocumentPathPolicy.requireSafeSegment(runId)
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        require(buildVariant.isNotBlank()) { "buildVariant must not be blank" }
    }

    /** Compatibility bridge for the original activity. New callers should use the Android-free primary constructor. */
    constructor(
        context: Context,
        root: DocumentFile,
        settings: OptimizerSettings,
        logger: (String) -> Unit
    ) : this(AndroidBridge(context, root), settings, logger)

    private constructor(
        bridge: AndroidBridge,
        settings: OptimizerSettings,
        logger: (String) -> Unit
    ) : this(
        documentGateway = bridge.gateway,
        selectedRoot = bridge.gateway.rootNode,
        candidateStore = bridge.candidateStore,
        runId = bridge.runId,
        runIntent = RunIntent(
            mode = settings.mode,
            dryRun = false,
            apkLabMode = settings.apkLabMode,
            textMinify = settings.textMinify
        ),
        startedAt = bridge::timestamp,
        completedAt = bridge::timestamp,
        appVersion = bridge.appVersion,
        buildVariant = "standard"
    ) {
        compatibilityLogger = logger
    }

    fun run(): OptimizationReport = run(NeverCancelled) { snapshot ->
        compatibilityLogger?.invoke(
            buildString {
                append(snapshot.phase)
                snapshot.currentRelativePath?.let { append(": ").append(it) }
                append(" (processed=").append(snapshot.filesProcessed)
                append(", optimized=").append(snapshot.optimized)
                append(", saved=").append(snapshot.savedBytes).append(" bytes)")
            }
        )
    }

    fun run(
        cancellation: CancellationToken,
        onProgress: (ProgressSnapshot) -> Unit
    ): OptimizationReport {
        check(runClaimed.compareAndSet(false, true)) { "An OptimizerEngine instance can run only once" }
        val report = OptimizationReport()
        var filesDiscovered = 0
        var filesProcessed = 0
        var undo: UndoSession? = null
        var undoPoisoned = false
        var vmFatal: Throwable? = null

        fun progress(phase: String, path: String? = null) {
            try {
                onProgress(
                    ProgressSnapshot(
                        phase = phase,
                        currentRelativePath = path,
                        filesDiscovered = filesDiscovered,
                        filesProcessed = filesProcessed,
                        candidates = report.candidates,
                        optimized = report.optimized,
                        skipsByReason = report.skipsByReason,
                        errors = report.errors,
                        bytesRead = report.bytesRead,
                        bytesWritten = report.bytesWritten,
                        savedBytes = report.savedBytes,
                        potentialSavingsBytes = report.potentialSavingsBytes,
                        totalWork = null
                    )
                )
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
                // Observers (activities, services, or notifications) do not own engine state.
            }
        }

        try {
            if (!runIntent.dryRun) undo = openUndoSession()
            progress(if (runIntent.dryRun) "analyzing" else "optimizing")

            val commitContext = undo?.let { session ->
                CommitContext(
                    selectedRoot = selectedRoot,
                    runId = runId,
                    undoEntrySink = UndoEntrySink { entry ->
                        undoRepository.appendEntry(session.writer, entry)
                        session.entriesCommitted = checkedIncrement(session.entriesCommitted)
                    },
                    completedAt = completedAt
                )
            }
            val streamingCoordinator = if (commitContext == null) {
                OptimizationCoordinator(documentGateway, candidateStore, runId)
            } else {
                OptimizationCoordinator(
                    documentGateway,
                    candidateStore,
                    StrictStreamingZipCandidateProcessor,
                    commitContext
                )
            }
            val byteArrayAdapter = ByteArrayOptimizerAdapter(documentGateway, commitContext)

            val files = TreeCursor(cancellation) { report.errors = checkedIncrement(report.errors) }
            while (true) {
                cancellation.throwIfCancelled()
                val file = files.next() ?: break
                filesDiscovered = checkedIncrement(filesDiscovered)
                report.scanned = checkedIncrement(report.scanned)
                progress(if (runIntent.dryRun) "analyzing" else "optimizing", file.relativePath)
                val outcome = process(file, streamingCoordinator, byteArrayAdapter, cancellation)
                aggregate(report, outcome)
                filesProcessed = checkedIncrement(filesProcessed)
                progress(if (runIntent.dryRun) "analyzing" else "optimizing", file.relativePath)
            }
            report.status = if (report.errors == 0) RunStatus.COMPLETED else RunStatus.COMPLETED_WITH_ERRORS
        } catch (_: OptimizationCancelledException) {
            report.status = RunStatus.CANCELLED
        } catch (poisoned: UndoDurabilityException) {
            undoPoisoned = true
            report.errors = saturatingIncrement(report.errors)
            report.status = RunStatus.FAILED
            report.terminalError = poisoned.message ?: poisoned.javaClass.name
        } catch (failure: Throwable) {
            report.errors = saturatingIncrement(report.errors)
            report.status = RunStatus.FAILED
            report.terminalError = failure.message ?: failure.javaClass.name
            if (failure.isVmFatal()) vmFatal = failure
        } finally {
            if (report.status == RunStatus.RUNNING) {
                report.errors = saturatingIncrement(report.errors)
                report.status = RunStatus.FAILED
                report.terminalError = report.terminalError ?: "Run ended without a terminal status"
            }
            val session = undo
            if (session != null) {
                var terminalDurable = false
                if (!undoPoisoned) try {
                    undoRepository.appendTerminal(session.writer, report.toTerminal(session.entriesCommitted, completedAt()))
                    terminalDurable = true
                } catch (failure: Throwable) {
                    val primaryFatal = vmFatal
                    when {
                        primaryFatal != null -> if (failure !== primaryFatal) primaryFatal.addSuppressed(failure)
                        failure.isVmFatal() -> vmFatal = failure
                        else -> recordFinalizationFailure(report, failure, mutateStatus = true)
                    }
                }
                try {
                    if (undoPoisoned) session.output.close() else session.writer.close()
                } catch (failure: Throwable) {
                    val primaryFatal = vmFatal
                    when {
                        primaryFatal != null -> if (failure !== primaryFatal) primaryFatal.addSuppressed(failure)
                        failure.isVmFatal() -> vmFatal = failure
                        else -> recordFinalizationFailure(report, failure, mutateStatus = !terminalDurable && !undoPoisoned)
                    }
                }
            }
            try {
                progress(
                    when (report.status) {
                        RunStatus.COMPLETED -> "completed"
                        RunStatus.COMPLETED_WITH_ERRORS -> "completed-with-errors"
                        RunStatus.CANCELLED -> "cancelled"
                        RunStatus.FAILED -> "failed"
                        RunStatus.RUNNING -> "running"
                    }
                )
            } catch (failure: Throwable) {
                val primary = vmFatal
                if (primary != null) {
                    if (failure !== primary) primary.addSuppressed(failure)
                } else throw failure
            }
        }
        vmFatal?.let { throw it }
        return report
    }

    private fun process(
        file: ScannedFile,
        streamingCoordinator: OptimizationCoordinator,
        byteArrayAdapter: ByteArrayOptimizerAdapter,
        cancellation: CancellationToken
    ): FileOutcome = try {
        val kind = FileTypeDetector.detect(file.node.name, readHeader(file.node, cancellation))
        when {
            kind == FileKind.UNSUPPORTED -> FileOutcome.Skipped(file.relativePath, SkipReason.UNSUPPORTED)
            kind == FileKind.APK && !runIntent.apkLabMode ->
                FileOutcome.Skipped(file.relativePath, SkipReason.APK_GUARD)
            kind == FileKind.ZIP_LIKE || kind == FileKind.APK ->
                streamingCoordinator.process(file.node, file.relativePath, runIntent, cancellation)
            else -> byteArrayAdapter.process(file.node, file.relativePath, kind, runIntent, cancellation)
        }
    } catch (cancelled: OptimizationCancelledException) {
        throw cancelled
    } catch (poisoned: UndoDurabilityException) {
        throw poisoned
    } catch (failure: Exception) {
        FileOutcome.Failed(file.relativePath, failure.message ?: failure.javaClass.name, failure)
    }

    private inner class TreeCursor(
        private val cancellation: CancellationToken,
        private val onListError: () -> Unit
    ) {
        private val frames = ArrayDeque<DirectoryFrame>()
        private val directorySegments = ArrayDeque<String>()

        init {
            frames.addLast(DirectoryFrame(children(selectedRoot)))
        }

        fun next(): ScannedFile? {
            while (frames.isNotEmpty()) {
                cancellation.throwIfCancelled()
                val frame = frames.last()
                if (!frame.children.hasNext()) {
                    frames.removeLast()
                    if (directorySegments.isNotEmpty()) directorySegments.removeLast()
                    continue
                }
                val child = frame.children.next()
                if (child.isDirectory) {
                    val childIterator = children(child)
                    directorySegments.addLast(child.name)
                    frames.addLast(DirectoryFrame(childIterator))
                } else {
                    val relativePath = buildString {
                        directorySegments.forEach { segment -> append(segment).append('/') }
                        append(child.name)
                    }
                    return ScannedFile(child, relativePath)
                }
            }
            return null
        }

        private fun children(directory: DocumentNode): Iterator<DocumentNode> {
            cancellation.throwIfCancelled()
            val listed = try {
                documentGateway.list(directory)
            } catch (invariant: RunInvariantException) {
                throw invariant
            } catch (_: Exception) {
                onListError()
                emptyList()
            }
            return listed.asSequence()
                .filterNot { isManagedArtifact(it.name) }
                .sortedWith(compareBy<DocumentNode>({ it.isDirectory }, { it.name }, { it.id }))
                .iterator()
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
        return header.copyOf(offset)
    }

    private fun aggregate(report: OptimizationReport, outcome: FileOutcome) {
        when (outcome) {
            is FileOutcome.WouldOptimize -> {
                report.candidates = checkedIncrement(report.candidates)
                report.bytesRead = checkedAdd(report.bytesRead, outcome.oldBytes)
                report.bytesWritten = checkedAdd(report.bytesWritten, outcome.newBytes)
                report.potentialSavingsBytes = checkedAdd(report.potentialSavingsBytes, outcome.potentialSavingsBytes)
            }
            is FileOutcome.Optimized -> {
                report.candidates = checkedIncrement(report.candidates)
                report.optimized = checkedIncrement(report.optimized)
                report.bytesRead = checkedAdd(report.bytesRead, outcome.oldBytes)
                report.bytesWritten = checkedAdd(report.bytesWritten, outcome.newBytes)
                report.savedBytes = checkedAdd(report.savedBytes, outcome.savedBytes)
            }
            is FileOutcome.Skipped -> {
                report.skipped = checkedIncrement(report.skipped)
                val updated = LinkedHashMap(report.skipsByReason)
                updated[outcome.reason] = checkedIncrement(updated[outcome.reason] ?: 0)
                report.skipsByReason = updated
            }
            is FileOutcome.Failed -> report.errors = checkedIncrement(report.errors)
        }
    }

    private fun openUndoSession(): UndoSession {
        val name = "FileForge_Undo_v2_$runId.jsonl"
        val node = documentGateway.createFileExact(selectedRoot, "application/x-ndjson", name)
        val output = documentGateway.openWrite(node)
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        return try {
            undoRepository.start(
                writer,
                UndoHeader(
                    runId = runId,
                    startedAt = startedAt(),
                    mode = runIntent.mode,
                    apkLabMode = runIntent.apkLabMode,
                    textMinify = runIntent.textMinify,
                    dryRun = false,
                    appVersion = appVersion,
                    buildVariant = buildVariant
                )
            )
            UndoSession(writer, output)
        } catch (failure: Exception) {
            try { writer.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private fun OptimizationReport.toTerminal(entries: Int, timestamp: String) = UndoTerminalSummary(
        status = status,
        completedAt = timestamp,
        entriesCommitted = entries,
        scanned = scanned,
        optimized = optimized,
        skipped = skipped,
        errors = errors,
        savedBytes = savedBytes,
        bytesRead = bytesRead,
        bytesWritten = bytesWritten,
        potentialSavingsBytes = potentialSavingsBytes
    )

    private fun isManagedArtifact(name: String): Boolean = MANAGED_PREFIXES.any { name.startsWith(it) }

    private fun checkedIncrement(value: Int): Int = Math.addExact(value, 1)
    private fun checkedAdd(left: Long, right: Long): Long = Math.addExact(left, right)

    private fun recordFinalizationFailure(report: OptimizationReport, failure: Throwable, mutateStatus: Boolean) {
        val message = failure.message ?: failure.javaClass.name
        report.terminalFailures = report.terminalFailures + message
        if (mutateStatus) {
            report.errors = saturatingIncrement(report.errors)
            report.status = RunStatus.FAILED
            report.terminalError = report.terminalError ?: message
        }
    }

    private fun Throwable.isVmFatal(): Boolean =
        this is OutOfMemoryError || this is StackOverflowError || this is ThreadDeath

    private fun saturatingIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private data class ScannedFile(val node: DocumentNode, val relativePath: String)
    private data class DirectoryFrame(val children: Iterator<DocumentNode>)
    private data class UndoSession(val writer: Writer, val output: OutputStream, var entriesCommitted: Int = 0)

    private class AndroidBridge(context: Context, root: DocumentFile) {
        val gateway = SafDocumentGateway(context, root)
        val runId: String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
            "_${UUID.randomUUID().toString().take(12)}"
        val candidateStore = CandidateStore(context.cacheDir, runId)
        val appVersion: String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    }

    private companion object {
        const val HEADER_BYTES = 8 * 1024
        val MANAGED_PREFIXES = listOf(
            "FileForge_Backups_",
            "FileForge_Undo_",
            "FileForge_Restore_",
            "FileForge_Candidate_",
            "FileForge_Temp_"
        )
    }
}
