package com.fileforge.optimizer

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed class NativeExecution {
    data class Success(val output: File, val detail: String) : NativeExecution()
    data class Unavailable(val message: String) : NativeExecution()
    data class TimedOut(val message: String) : NativeExecution()
    data class Failed(val exitCode: Int?, val message: String) : NativeExecution()
    data class Cancelled(val message: String) : NativeExecution()
}

fun interface NativeToolExecutor {
    fun run(
        tool: NativeToolId,
        stagedInput: File,
        candidateOutput: File,
        mode: OptimizeMode,
        cancellation: CancellationToken
    ): NativeExecution
}

interface NativeProcess {
    val output: InputStream
    fun waitFor(timeoutMillis: Long): Boolean
    fun exitValue(): Int
    fun destroy()
    fun destroyForcibly()
}

fun interface ProcessLauncher {
    fun start(command: List<String>, workingDirectory: File): NativeProcess
}

class NativeToolRunner(
    private val registry: NativeToolRegistry,
    private val processLauncher: ProcessLauncher = JvmProcessLauncher,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val timeoutFor: (NativeToolId) -> Long = ::defaultTimeoutMillis,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS
) : NativeToolExecutor {
    init {
        require(pollMillis > 0) { "Native process poll interval must be positive" }
    }

    override fun run(
        tool: NativeToolId,
        stagedInput: File,
        candidateOutput: File,
        mode: OptimizeMode,
        cancellation: CancellationToken
    ): NativeExecution {
        if (!registry.available(tool)) {
            return NativeExecution.Unavailable("Native tool is unavailable in this build: ${tool.manifestId}")
        }
        val timeoutMillis = timeoutFor(tool)
        require(timeoutMillis > 0) { "Native process timeout must be positive" }
        try {
            cancellation.throwIfCancelled()
        } catch (cancelled: OptimizationCancelledException) {
            return NativeExecution.Cancelled(cancelled.message ?: "Native optimization cancelled")
        }
        val workingDirectory = candidateOutput.absoluteFile.parentFile
            ?: return NativeExecution.Failed(null, "Native candidate has no private parent directory")
        if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
            return NativeExecution.Failed(null, "Cannot create private native candidate directory")
        }
        if (candidateOutput.exists() && !candidateOutput.delete()) {
            return NativeExecution.Failed(null, "Cannot replace stale private native candidate")
        }
        val command = registry.commandFor(tool, stagedInput, candidateOutput, mode)
        val process = try {
            processLauncher.start(command, workingDirectory)
        } catch (failure: Exception) {
            return NativeExecution.Failed(null, sanitize(failure.message ?: failure.javaClass.name, stagedInput, candidateOutput))
        }
        val capture = BoundedProcessCapture()
        val captureFailure = AtomicReference<Throwable?>(null)
        val drainer = Thread(
            {
                try {
                    process.output.use { source ->
                        val buffer = ByteArray(PROCESS_BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read > 0) capture.append(buffer, read)
                        }
                    }
                } catch (failure: Throwable) {
                    captureFailure.set(failure)
                }
            },
            "FileForge-Native-Output"
        ).apply { isDaemon = true }
        drainer.start()

        val started = clock.nowMillis()
        while (true) {
            try {
                cancellation.throwIfCancelled()
            } catch (limited: CandidateSizeLimitExceededException) {
                terminate(process)
                joinDrainer(drainer)
                candidateOutput.delete()
                throw limited
            } catch (cancelled: OptimizationCancelledException) {
                terminate(process)
                joinDrainer(drainer)
                candidateOutput.delete()
                return NativeExecution.Cancelled(cancelled.message ?: "Native optimization cancelled")
            }
            if (process.waitFor(pollMillis)) break
            val elapsed = (clock.nowMillis() - started).coerceAtLeast(0)
            if (elapsed >= timeoutMillis) {
                terminate(process)
                joinDrainer(drainer)
                candidateOutput.delete()
                return NativeExecution.TimedOut("${tool.manifestId} exceeded its ${timeoutMillis}ms time limit")
            }
        }
        joinDrainer(drainer)
        captureFailure.get()?.let { failure ->
            candidateOutput.delete()
            return NativeExecution.Failed(
                null,
                sanitize("Cannot capture native process output: ${failure.message ?: failure.javaClass.name}", stagedInput, candidateOutput)
            )
        }
        val exitCode = try {
            process.exitValue()
        } catch (failure: Exception) {
            candidateOutput.delete()
            return NativeExecution.Failed(null, "Native process ended without an exit status")
        }
        val captured = sanitize(capture.text(), stagedInput, candidateOutput)
        if (exitCode != 0) {
            candidateOutput.delete()
            return NativeExecution.Failed(
                exitCode,
                buildString {
                    append(tool.manifestId).append(" exited with code ").append(exitCode)
                    if (captured.isNotBlank()) append(": ").append(captured)
                }
            )
        }
        if (!candidateOutput.isFile || candidateOutput.length() <= 0L) {
            candidateOutput.delete()
            return NativeExecution.Failed(exitCode, "${tool.manifestId} produced no candidate output")
        }
        return NativeExecution.Success(
            candidateOutput,
            captured.ifBlank { "${tool.manifestId} completed successfully" }
        )
    }

    private fun terminate(process: NativeProcess) {
        process.destroy()
        if (!process.waitFor(TERMINATION_GRACE_MILLIS)) {
            process.destroyForcibly()
            process.waitFor(TERMINATION_GRACE_MILLIS)
        }
    }

    private fun joinDrainer(drainer: Thread) {
        try {
            drainer.join(DRAIN_JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sanitize(message: String, input: File, output: File): String {
        var sanitized = message
        listOfNotNull(
            input.absolutePath,
            output.absolutePath,
            input.absoluteFile.parentFile?.absolutePath,
            output.absoluteFile.parentFile?.absolutePath
        )
            .distinct()
            .sortedByDescending(String::length)
            .forEach { path -> sanitized = sanitized.replace(path, "<private-path>") }
        return sanitized
    }

    private class BoundedProcessCapture {
        private val bytes = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
        private var truncated = false

        @Synchronized
        fun append(source: ByteArray, length: Int) {
            val remaining = MAX_CAPTURE_BYTES - bytes.size()
            val retained = minOf(length, remaining)
            bytes.write(source, 0, retained)
            if (retained < length) truncated = true
        }

        @Synchronized
        fun text(): String {
            val value = bytes.toByteArray().toString(Charsets.UTF_8).trim()
            return if (truncated) "$value [output truncated]" else value
        }
    }

    companion object {
        const val MAX_CAPTURE_BYTES = 64 * 1024
        const val DEFAULT_POLL_MILLIS = 100L
        private const val PROCESS_BUFFER_BYTES = 8 * 1024
        private const val TERMINATION_GRACE_MILLIS = 2_000L
        private const val DRAIN_JOIN_MILLIS = 2_000L
        private const val TEN_MINUTES_MILLIS = 10L * 60L * 1_000L
        private const val THIRTY_MINUTES_MILLIS = 30L * 60L * 1_000L

        fun defaultTimeoutMillis(tool: NativeToolId): Long = when (tool) {
            NativeToolId.OXIPNG, NativeToolId.ZOPFLIPNG -> THIRTY_MINUTES_MILLIS
            NativeToolId.QPDF, NativeToolId.JPEGTRAN, NativeToolId.ZIPALIGN -> TEN_MINUTES_MILLIS
        }
    }
}

class NativeFallbackOptimizer(private val executor: NativeToolExecutor) {
    fun runOrFallback(
        tool: NativeToolId,
        input: File,
        output: File,
        mode: OptimizeMode,
        cancellation: CancellationToken,
        verify: (File) -> Boolean,
        accept: (NativeExecution.Success) -> FileOutcome,
        fallback: () -> FileOutcome
    ): FileOutcome = when (val execution = executor.run(tool, input, output, mode, cancellation)) {
        is NativeExecution.Cancelled -> throw OptimizationCancelledException(execution.message)
        is NativeExecution.Success -> {
            val verified = try {
                execution.output.canonicalFile == output.canonicalFile &&
                    execution.output.isFile &&
                    execution.output.length() in 1 until input.length() &&
                    verify(execution.output)
            } catch (cancelled: OptimizationCancelledException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isVmFatal()) throw failure
                false
            }
            if (verified) accept(execution) else fallback()
        }
        is NativeExecution.Unavailable,
        is NativeExecution.TimedOut,
        is NativeExecution.Failed -> fallback()
    }
}

private object JvmProcessLauncher : ProcessLauncher {
    override fun start(command: List<String>, workingDirectory: File): NativeProcess =
        JvmNativeProcess(
            ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .start()
        )
}

private class JvmNativeProcess(private val process: Process) : NativeProcess {
    override val output: InputStream get() = process.inputStream
    override fun waitFor(timeoutMillis: Long): Boolean = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    override fun exitValue(): Int = process.exitValue()
    override fun destroy() = process.destroy()
    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}
