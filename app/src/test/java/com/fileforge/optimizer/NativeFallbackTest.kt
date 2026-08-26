package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeFallbackTest {
    @Test
    fun nativeFailureRunsKotlinFallbackWithoutAcceptingOrWritingTheNativeCandidate() = withFiles { input, output ->
        var accepted = false
        var originalWritten = false
        val policy = NativeFallbackOptimizer(
            NativeToolExecutor { _, _, _, _, _ -> NativeExecution.Failed(2, "native failed") }
        )

        val outcome = policy.runOrFallback(
            NativeToolId.QPDF,
            input,
            output,
            OptimizeMode.SAFE,
            NeverCancelled,
            verify = { true },
            accept = {
                accepted = true
                originalWritten = true
                FileOutcome.Optimized("document.pdf", 10, 5, "native", "accepted")
            },
            fallback = { FileOutcome.WouldOptimize("document.pdf", 10, 6, "kotlin", "fallback") }
        )

        assertTrue(outcome is FileOutcome.WouldOptimize)
        assertFalse(accepted)
        assertFalse(originalWritten)
    }

    @Test
    fun malformedOrNonSmallerNativeOutputFallsBackWhileVerifiedGainIsAccepted() = withFiles { input, output ->
        val executor = NativeToolExecutor { _, _, candidate, _, _ ->
            candidate.writeText("short")
            NativeExecution.Success(candidate, "native ok")
        }
        val policy = NativeFallbackOptimizer(executor)
        var fallbackCalls = 0

        val malformed = policy.runOrFallback(
            NativeToolId.QPDF, input, output, OptimizeMode.SAFE, NeverCancelled,
            verify = { false },
            accept = { error("must not accept malformed output") },
            fallback = { fallbackCalls++; FileOutcome.Skipped("document.pdf", SkipReason.VERIFICATION_FAILED) }
        )
        assertTrue(malformed is FileOutcome.Skipped)

        output.delete()
        val accepted = policy.runOrFallback(
            NativeToolId.QPDF, input, output, OptimizeMode.SAFE, NeverCancelled,
            verify = { true },
            accept = { FileOutcome.WouldOptimize("document.pdf", input.length(), it.output.length(), "native", it.detail) },
            fallback = { fallbackCalls++; error("must not fall back for verified gain") }
        )

        assertTrue(accepted is FileOutcome.WouldOptimize)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun cancelledNativeExecutionPropagatesAsOptimizationCancellation() = withFiles { input, output ->
        val policy = NativeFallbackOptimizer(
            NativeToolExecutor { _, _, _, _, _ -> NativeExecution.Cancelled("cancelled") }
        )

        assertThrows(OptimizationCancelledException::class.java) {
            policy.runOrFallback(
                NativeToolId.QPDF, input, output, OptimizeMode.SAFE, NeverCancelled,
                verify = { true },
                accept = { error("must not accept") },
                fallback = { error("must not fall back") }
            )
        }
    }

    private fun withFiles(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("fileforge-native-fallback").toFile()
        try {
            val input = File(root, "input.pdf").apply { writeText("long original payload") }
            block(input, File(root, "output.pdf"))
        } finally {
            root.deleteRecursively()
        }
    }
}
