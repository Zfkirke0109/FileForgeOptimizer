package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

class OptimizerEngineTraversalHardeningTest {
    @Test
    fun veryDeepTreeIsTraversedIterativelyAndManagedArtifactsRemainExcluded() = withEngine { gateway, engine ->
        val depth = 12_000
        gateway.depth = depth

        val report = engine.run(NeverCancelled) {}

        assertEquals(RunStatus.COMPLETED, report.status)
        assertEquals(2, report.scanned)
        assertEquals(2, report.skipped)
        assertEquals(depth + 1, gateway.listCalls)
        assertFalse(gateway.readIds.any { it.contains("FileForge_") })
    }

    @Test
    fun earlyCancellationProcessesFirstFileWithoutListingOrRetainingRemainingGeneratedTree() = withEngine { gateway, engine ->
        gateway.depth = 20_000
        var cancel = false

        val report = engine.run(CancellationToken {
            if (cancel) throw OptimizationCancelledException()
        }) { snapshot ->
            if (snapshot.filesProcessed == 1) cancel = true
        }

        assertEquals(RunStatus.CANCELLED, report.status)
        assertEquals(1, report.scanned)
        assertTrue("lazy traversal should not enumerate the full chain", gateway.listCalls <= 2)
    }

    @Test
    fun cyclicProviderDirectoryIdentityIsSkippedWithABoundedError() = withEngine { gateway, engine ->
        gateway.cycleRoot = true
        val cancellation = CancellationToken {
            if (gateway.listCalls > 10) throw OptimizationCancelledException("cycle was not bounded")
        }

        val report = engine.run(cancellation) {}

        assertEquals(RunStatus.COMPLETED_WITH_ERRORS, report.status)
        assertEquals(1, report.errors)
        assertTrue(gateway.listCalls <= 2)
    }

    @Test
    fun uniqueProviderDepthIsStoppedByATraversalBudget() = withEngine { gateway, engine ->
        gateway.depth = 17_000

        val report = engine.run(NeverCancelled) {}

        assertEquals(RunStatus.FAILED, report.status)
        assertTrue(report.terminalError.orEmpty().contains("depth", ignoreCase = true))
    }

    @Test
    fun opaqueProviderNamesContainingSlashesAreRejectedBeforeAnyDocumentRead() = withEngine { gateway, engine ->
        gateway.includeUnsafeName = true

        val report = engine.run(NeverCancelled) {}

        assertEquals(RunStatus.COMPLETED_WITH_ERRORS, report.status)
        assertEquals(1, report.errors)
        assertFalse(gateway.readIds.contains("file:unsafe"))
    }

    private fun withEngine(block: (GeneratedDeepGateway, OptimizerEngine) -> Unit) {
        val cache = Files.createTempDirectory("fileforge-deep-engine").toFile()
        try {
            val gateway = GeneratedDeepGateway()
            val engine = OptimizerEngine(
                documentGateway = gateway,
                selectedRoot = gateway.root,
                candidateStore = CandidateStore(cache, "deep-run"),
                runId = "deep-run",
                runIntent = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = false),
                startedAt = { "start" },
                completedAt = { "complete" },
                appVersion = "test",
                buildVariant = "standard-test"
            )
            block(gateway, engine)
        } finally {
            cache.deleteRecursively()
        }
    }

    private class GeneratedDeepGateway : DocumentGateway {
        val root = DocumentNode("directory:0", "selected", true, 0)
        val readIds = mutableListOf<String>()
        var depth: Int = 0
        var listCalls: Int = 0
        var cycleRoot: Boolean = false
        var includeUnsafeName: Boolean = false

        override fun openRead(node: DocumentNode): InputStream {
            readIds += node.id
            return ByteArrayInputStream(byteArrayOf(1))
        }
        override fun openWrite(node: DocumentNode): OutputStream = error("dry run must not write")

        override fun list(node: DocumentNode): List<DocumentNode> {
            listCalls++
            val level = node.id.substringAfter(':').toInt()
            return when {
                cycleRoot && level == 0 -> listOf(DocumentNode("directory:0", "loop", true, 0))
                level == 0 -> listOf(
                    DocumentNode("file:0", "first.bin", false, 1),
                    DocumentNode("directory:1", "d1", true, 0),
                    DocumentNode("artifact:0", "FileForge_Backups_hidden", true, 0)
                ) + if (includeUnsafeName) listOf(DocumentNode("file:unsafe", "folder/file.txt", false, 1)) else emptyList()
                level < depth -> listOf(DocumentNode("directory:${level + 1}", "d${level + 1}", true, 0))
                else -> listOf(DocumentNode("file:deep", "deep.bin", false, 1))
            }
        }

        override fun resolve(parent: DocumentNode, name: String): DocumentNode? = null
        override fun createDirectory(parent: DocumentNode, name: String): DocumentNode = error("dry run must not create")
        override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode = error("dry run must not create")
        override fun length(node: DocumentNode): Long = node.length
    }
}
