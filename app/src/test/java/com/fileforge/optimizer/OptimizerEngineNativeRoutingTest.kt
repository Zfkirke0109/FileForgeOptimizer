package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OptimizerEngineNativeRoutingTest {
    @Test
    fun injectedNativeExecutorOwnsSupportedFormatsWhileTextKeepsTheKotlinPath() {
        val cache = Files.createTempDirectory("fileforge-engine-native").toFile()
        try {
            val gateway = FaultInjectingEngineGateway().apply {
                put("document.pdf", "%PDF-1.7\n%%EOF\n${"x".repeat(128)}".toByteArray())
                put("notes.txt", "line with spaces   \n".toByteArray())
            }
            val calls = mutableListOf<NativeToolId>()
            val executor = NativeToolExecutor { tool, _, output, _, _ ->
                calls += tool
                output.writeBytes("%PDF-1.7\n%%EOF\n".toByteArray())
                NativeExecution.Success(output, "native ok")
            }
            val engine = OptimizerEngine(
                documentGateway = gateway,
                selectedRoot = gateway.root,
                candidateStore = CandidateStore(cache, RUN_ID),
                runId = RUN_ID,
                runIntent = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = true),
                startedAt = { "2026-08-25T00:00:00.000Z" },
                completedAt = { "2026-08-25T00:00:01.000Z" },
                appVersion = "0.2.0-test",
                buildVariant = "native-arm64-test",
                nativeToolExecutor = executor
            )

            val report = engine.run(NeverCancelled) {}

            assertEquals(listOf(NativeToolId.QPDF), calls)
            assertEquals(2, report.candidates)
            assertEquals(0, report.errors)
            assertEquals(RunStatus.COMPLETED, report.status)
            assertTrue(cache.list().orEmpty().isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }

    private companion object {
        const val RUN_ID = "run-native-engine"
    }
}
