package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class NativeToolRunnerTest {
    @Test
    fun exitZeroWithAProducedOutputReturnsSuccessAndUsesTheFixedRegistryCommand() = withFixture { fixture ->
        fixture.process.exitCode = 0
        fixture.launcher.onStart = { command -> File(command.last()).writeText("optimized") }

        val result = fixture.runner.run(
            NativeToolId.QPDF,
            fixture.input,
            fixture.output,
            OptimizeMode.SAFE,
            NeverCancelled
        )

        assertTrue(result is NativeExecution.Success)
        assertEquals(fixture.input.absolutePath, fixture.launcher.command!![fixture.launcher.command!!.lastIndex - 1])
        assertEquals(fixture.output.absolutePath, fixture.launcher.command!!.last())
    }

    @Test
    fun nonzeroExitAndMissingOutputFailWithoutClaimingSuccess() = withFixture { fixture ->
        fixture.process.exitCode = 7
        val nonzero = fixture.runner.run(
            NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, NeverCancelled
        )
        assertTrue(nonzero is NativeExecution.Failed)

        fixture.process.exitCode = 0
        val missing = fixture.runner.run(
            NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, NeverCancelled
        )
        assertTrue(missing is NativeExecution.Failed)
    }

    @Test
    fun capturedProcessTextIsBoundedAndPrivatePathsAreSanitized() = withFixture { fixture ->
        fixture.process.exitCode = 9
        fixture.process.captured = (fixture.input.absolutePath + "\n" + "x".repeat(100_000)).toByteArray()

        val result = fixture.runner.run(
            NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, NeverCancelled
        ) as NativeExecution.Failed

        assertFalse(result.message.contains(fixture.input.absolutePath))
        assertTrue(result.message.length <= NativeToolRunner.MAX_CAPTURE_BYTES + 256)
        assertTrue(result.message.contains("truncated"))
    }

    @Test
    fun timeoutDestroysThenForciblyDestroysAStuckProcess() = withFixture(timeoutMillis = 5) { fixture ->
        fixture.process.finished = false
        fixture.process.finishOnDestroy = false

        val result = fixture.runner.run(
            NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, NeverCancelled
        )

        assertTrue(result is NativeExecution.TimedOut)
        assertTrue(fixture.process.destroyed)
        assertTrue(fixture.process.forciblyDestroyed)
    }

    @Test
    fun cancellationTerminatesTheProcessAndReturnsAnExplicitCancelledOutcome() = withFixture { fixture ->
        fixture.process.finished = false
        var checks = 0
        val cancellation = CancellationToken {
            if (++checks >= 2) throw OptimizationCancelledException("cancel native")
        }

        val result = fixture.runner.run(
            NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, cancellation
        )

        assertTrue(result is NativeExecution.Cancelled)
        assertTrue(fixture.process.destroyed)
    }

    @Test
    fun liveCandidateLimitTerminatesTheNativeProcessBeforePropagatingTheFailure() = withFixture { fixture ->
        fixture.process.finished = false
        var checks = 0
        val liveLimit = CancellationToken {
            if (++checks >= 2) throw CandidateSizeLimitExceededException(8)
        }

        assertThrows(CandidateSizeLimitExceededException::class.java) {
            fixture.runner.run(
                NativeToolId.QPDF, fixture.input, fixture.output, OptimizeMode.SAFE, liveLimit
            )
        }

        assertTrue(fixture.process.destroyed)
    }

    private fun withFixture(timeoutMillis: Long = 10_000, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("fileforge-native-runner").toFile()
        try {
            val nativeDir = File(root, "native").apply { mkdirs() }
            File(nativeDir, "libfileforge_qpdf.so").apply {
                writeText("fake executable")
                setExecutable(true)
            }
            val manifest = NativeToolManifest.parse(
                """{"schemaVersion":1,"abi":"arm64-v8a","tools":[{"id":"qpdf","version":"12.4.0","executableName":"libfileforge_qpdf.so","licenseId":"Apache-2.0","sourceUrl":"https://github.com/qpdf/qpdf","resolvedCommit":"${"a".repeat(40)}"}]}"""
            )
            val process = FakeNativeProcess()
            val launcher = FakeProcessLauncher(process)
            val clock = AdvancingClock()
            val runner = NativeToolRunner(
                NativeToolRegistry(manifest, nativeDir),
                launcher,
                clock,
                timeoutFor = { timeoutMillis },
                pollMillis = 1
            )
            val input = File(root, "private/input.pdf").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("original payload")
            }
            block(Fixture(runner, launcher, process, input, File(root, "private/output.pdf")))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val runner: NativeToolRunner,
        val launcher: FakeProcessLauncher,
        val process: FakeNativeProcess,
        val input: File,
        val output: File
    )

    private class FakeProcessLauncher(private val process: FakeNativeProcess) : ProcessLauncher {
        var command: List<String>? = null
        var onStart: (List<String>) -> Unit = {}

        override fun start(command: List<String>, workingDirectory: File): NativeProcess {
            this.command = command
            onStart(command)
            return process
        }
    }

    private class FakeNativeProcess : NativeProcess {
        var captured = byteArrayOf()
        var exitCode = 0
        var finished = true
        var finishOnDestroy = true
        var destroyed = false
        var forciblyDestroyed = false

        override val output: InputStream get() = ByteArrayInputStream(captured)
        override fun waitFor(timeoutMillis: Long): Boolean = finished
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed = true
            if (finishOnDestroy) finished = true
        }
        override fun destroyForcibly() {
            forciblyDestroyed = true
            finished = true
        }
    }

    private class AdvancingClock : MonotonicClock {
        private var now = 0L
        override fun nowMillis(): Long = now++
    }
}
