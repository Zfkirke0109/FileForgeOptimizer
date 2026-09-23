package com.fileforge.optimizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OptimizerEngineIntegrationTest {
    @Test
    fun recursivelyScansOrdinaryFilesButExcludesEveryFileForgeManagedArtifact() = withCache { cache ->
        val gateway = EngineDocumentGateway().apply {
            put("root.bin", byteArrayOf(1))
            put("one/two/nested.bin", byteArrayOf(2))
            put("FileForge_Backups_old/should-not-scan.zip", compressibleZip)
            put("one/FileForge_Backups_nested/should-not-scan.zip", compressibleZip)
            put("FileForge_Undo_old.txt", "legacy interrupted log".toByteArray())
            put("FileForge_Undo_v2_old.jsonl", "{\"status\":\"RUNNING\"}\n".toByteArray())
            put("FileForge_Restore_old.jsonl", "restore receipt".toByteArray())
            put("FileForge_Backups_masquerade.zip", compressibleZip)
            resetObservations()
        }
        val snapshots = mutableListOf<ProgressSnapshot>()

        val report = engine(gateway, cache, dryRun).run(NeverCancelled, snapshots::add)

        assertEquals(2, report.scanned)
        assertEquals(2, report.skipped)
        assertEquals(mapOf(SkipReason.UNSUPPORTED to 2), report.skipsByReason)
        assertEquals(RunStatus.COMPLETED, report.status)
        assertEquals(2, snapshots.last().filesDiscovered)
        assertEquals(2, snapshots.last().filesProcessed)
        assertTrue(gateway.readPaths.none { it.contains("FileForge_") })
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun apkIsGuardedUnlessApkLabWasExplicitlyEnabled() = withCache { cache ->
        val gateway = EngineDocumentGateway().apply {
            put("application.apk", compressibleZip)
            resetObservations()
        }

        val report = engine(gateway, cache, dryRun.copy(apkLabMode = false)).run(NeverCancelled) {}

        assertEquals(1, report.scanned)
        assertEquals(0, report.candidates)
        assertEquals(1, report.skipped)
        assertEquals(mapOf(SkipReason.APK_GUARD to 1), report.skipsByReason)
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun zipFamilyIgnoresInflatedProviderLengthAndUsesActualStreamedBytes() = withCache { cache ->
        val gateway = EngineDocumentGateway().apply {
            put("archive.zip", compressibleZip, FORMER_ZIP_GUARD_BYTES + 1)
            resetObservations()
        }
        val expectedCandidate = optimizeZip(compressibleZip)

        val report = engine(gateway, cache, dryRun).run(NeverCancelled) {}

        assertEquals(1, report.scanned)
        assertEquals(1, report.candidates)
        assertEquals(0, report.skipped)
        assertFalse(report.skipsByReason.containsKey(SkipReason.MEMORY_LIMIT))
        assertEquals(compressibleZip.size.toLong() - expectedCandidate.size, report.potentialSavingsBytes)
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun nonZipByteArrayOptimizersUseNamedSixtyFourMiBActualByteLimit() = withCache { cache ->
        assertEquals(64L * 1024L * 1024L, ByteArrayOptimizerAdapter.DEFAULT_MAX_INPUT_BYTES)
        val original = "%PDF-1.4\n%%EOF\ntrailing".toByteArray()
        val gateway = EngineDocumentGateway().apply {
            put("overreported.pdf", original, ByteArrayOptimizerAdapter.DEFAULT_MAX_INPUT_BYTES + 1)
            resetObservations()
        }

        val report = engine(gateway, cache, dryRun).run(NeverCancelled) {}

        assertEquals(1, report.scanned)
        assertEquals(1, report.candidates)
        assertEquals(0, report.skipped)
        assertTrue(report.potentialSavingsBytes in 1 until original.size.toLong())
        assertTrue(gateway.readPaths.count { it == "overreported.pdf" } >= 2)
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun dryRunReportsExactTotalsAndNeverWritesOrCreatesSelectedTreeArtifacts() = withCache { cache ->
        val expectedCandidate = optimizeZip(compressibleZip)
        val gateway = EngineDocumentGateway().apply {
            put("documents/archive.zip", compressibleZip)
            put("documents/unknown.bin", byteArrayOf(7, 8, 9))
            resetObservations()
        }
        val snapshots = mutableListOf<ProgressSnapshot>()

        val report = engine(gateway, cache, dryRun).run(NeverCancelled, snapshots::add)

        assertEquals(2, report.scanned)
        assertEquals(1, report.candidates)
        assertEquals(0, report.optimized)
        assertEquals(1, report.skipped)
        assertEquals(0, report.errors)
        assertEquals(0, report.savedBytes)
        assertEquals(compressibleZip.size.toLong() - expectedCandidate.size, report.potentialSavingsBytes)
        assertEquals(mapOf(SkipReason.UNSUPPORTED to 1), report.skipsByReason)
        assertEquals(RunStatus.COMPLETED, report.status)
        assertEquals(report.potentialSavingsBytes, snapshots.last().potentialSavingsBytes)
        assertEquals(1, snapshots.last().candidates)
        assertTrue(gateway.mutations.isEmpty())
        assertTrue(gateway.relativePaths().none { it.substringAfterLast('/').startsWith("FileForge_") })
        assertDirectoryEmpty(cache)
    }

    @Test
    fun realRunReportsExactTotalsOnlyAfterVerifiedBackupReplacementAndV2UndoEntry() = withCache { cache ->
        val expectedCandidate = optimizeZip(compressibleZip)
        val gateway = EngineDocumentGateway().apply {
            put("documents/archive.zip", compressibleZip)
            resetObservations()
        }

        val report = engine(gateway, cache, realRun).run(NeverCancelled) {}

        assertEquals(1, report.scanned)
        assertEquals(1, report.candidates)
        assertEquals(1, report.optimized)
        assertEquals(0, report.skipped)
        assertEquals(0, report.errors)
        assertEquals(compressibleZip.size.toLong() - expectedCandidate.size, report.savedBytes)
        assertEquals(RunStatus.COMPLETED, report.status)
        assertArrayEquals(expectedCandidate, gateway.contents("documents/archive.zip"))
        assertArrayEquals(compressibleZip, gateway.contents("FileForge_Backups_$RUN_ID/documents/archive.zip"))

        val undo = readUndo(gateway)
        assertEquals(RUN_ID, undo.header.runId)
        assertEquals(RunStatus.COMPLETED, undo.status)
        assertEquals(1, undo.entries.size)
        assertEquals("documents/archive.zip", undo.entries.single().relativePath)
        assertEquals(compressibleZip.sha256(), undo.entries.single().originalSha256)
        assertEquals(expectedCandidate.sha256(), undo.entries.single().optimizedSha256)
        assertNotNull(undo.terminal)
        assertEquals(1, undo.terminal!!.entriesCommitted)
        assertEquals(report.savedBytes, undo.terminal!!.savedBytes)
        assertDirectoryEmpty(cache)
    }

    @Test
    fun cancellationObservedBetweenFilesDoesNotBeginAnotherCommitAndClosesUndoAsCancelled() = withCache { cache ->
        val firstOriginal = compressibleZip
        val secondOriginal = zipBytes("second.bin", ByteArray(192 * 1024) { (it % 5).toByte() })
        val gateway = EngineDocumentGateway().apply {
            put("first.zip", firstOriginal)
            put("second.zip", secondOriginal)
            resetObservations()
        }
        var cancelled = false
        val token = CancellationToken {
            if (cancelled) throw OptimizationCancelledException("cancel between files")
        }

        val report = engine(gateway, cache, realRun).run(token) { snapshot ->
            if (snapshot.filesProcessed == 1) cancelled = true
        }

        assertEquals(RunStatus.CANCELLED, report.status)
        assertEquals(1, report.optimized)
        assertArrayEquals(secondOriginal, gateway.contents("second.zip"))
        assertTrue(gateway.mutations.none { it.contains("FileForge_Backups_$RUN_ID/second.zip") })
        val undo = readUndo(gateway)
        assertEquals(RunStatus.CANCELLED, undo.status)
        assertEquals(1, undo.entries.size)
        assertEquals("first.zip", undo.entries.single().relativePath)
        assertEquals(1, undo.terminal!!.entriesCommitted)
    }

    @Test
    fun interruptedFileProcessingIsIsolatedAndRunStillGetsCompletedWithErrorsTerminal() = withCache { cache ->
        val gateway = EngineDocumentGateway().apply {
            put("broken.zip", compressibleZip)
            failRead("broken.zip", IOException("provider interrupted the read"))
            resetObservations()
        }

        val report = engine(gateway, cache, realRun).run(NeverCancelled) {}

        assertEquals(1, report.scanned)
        assertEquals(1, report.errors)
        assertEquals(0, report.optimized)
        assertEquals(RunStatus.COMPLETED_WITH_ERRORS, report.status)
        assertEquals(listOf("broken.zip: provider interrupted the read"), report.terminalFailures)
        val undo = readUndo(gateway)
        assertEquals(RunStatus.COMPLETED_WITH_ERRORS, undo.status)
        assertNotNull(undo.terminal)
        assertEquals(0, undo.terminal!!.entriesCommitted)
        assertEquals(1, undo.terminal!!.errors)
    }

    @Test
    fun fileFailureDetailsAreBoundedInCountAndLength() = withCache { cache ->
        val gateway = EngineDocumentGateway().apply {
            repeat(25) { index ->
                val path = "broken-${index.toString().padStart(2, '0')}.zip"
                put(path, compressibleZip)
                failRead(path, IOException("provider failure ${"x".repeat(1_024)}"))
            }
            resetObservations()
        }

        val report = engine(gateway, cache, realRun).run(NeverCancelled) {}

        assertEquals(25, report.errors)
        assertEquals(20, report.terminalFailures.size)
        assertTrue(report.terminalFailures.all { it.length <= 512 })
    }

    private fun engine(gateway: EngineDocumentGateway, cache: File, intent: RunIntent): OptimizerEngine =
        OptimizerEngine(
            documentGateway = gateway,
            selectedRoot = gateway.root,
            candidateStore = CandidateStore(cache, RUN_ID),
            runId = RUN_ID,
            runIntent = intent,
            startedAt = { STARTED_AT },
            completedAt = { COMPLETED_AT },
            appVersion = "0.2.0-test",
            buildVariant = "standard-test"
        )

    private fun readUndo(gateway: EngineDocumentGateway): UndoRun = UndoLogRepository().read(
        StringReader(gateway.contents("FileForge_Undo_v2_$RUN_ID.jsonl").toString(Charsets.UTF_8))
    )

    private fun withCache(block: (File) -> Unit) {
        val cache = Files.createTempDirectory("fileforge-engine").toFile()
        try {
            block(cache)
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun assertDirectoryEmpty(directory: File) {
        assertEquals(emptyList<String>(), directory.list().orEmpty().sorted())
    }

    private fun optimizeZip(input: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        StreamingZipOptimizer().optimize(input.inputStream(), output, OptimizeMode.SAFE, NeverCancelled) {}
    }.toByteArray()

    private class EngineDocumentGateway : DocumentGateway {
        val root = DocumentNode("root", "selected", isDirectory = true, length = 0)
        val mutations = mutableListOf<String>()
        val readPaths = mutableListOf<String>()

        private val nodes = linkedMapOf(root.id to root)
        private val children = linkedMapOf(root.id to linkedMapOf<String, String>())
        private val bytes = linkedMapOf<String, ByteArray>()
        private val advertisedLengths = mutableMapOf<String, Long>()
        private val readFailures = mutableMapOf<String, IOException>()

        fun put(relativePath: String, contents: ByteArray, advertisedLength: Long = contents.size.toLong()) {
            val parts = safeParts(relativePath)
            var parent = root
            parts.dropLast(1).forEach { name ->
                parent = resolve(parent, name) ?: create(parent, name, isDirectory = true)
            }
            val node = resolve(parent, parts.last()) ?: create(parent, parts.last(), isDirectory = false)
            bytes[node.id] = contents.copyOf()
            advertisedLengths[node.id] = advertisedLength
        }

        fun contents(relativePath: String): ByteArray = bytes.getValue(resolvePath(relativePath).id).copyOf()

        fun failRead(relativePath: String, failure: IOException) {
            readFailures[resolvePath(relativePath).id] = failure
        }

        fun resetObservations() {
            mutations.clear()
            readPaths.clear()
        }

        fun relativePaths(): List<String> {
            val paths = mutableListOf<String>()
            fun visit(parent: DocumentNode, prefix: String) {
                list(parent).forEach { child ->
                    val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    paths += path
                    if (child.isDirectory) visit(child, path)
                }
            }
            visit(root, "")
            return paths
        }

        override fun openRead(node: DocumentNode): InputStream {
            readPaths += relativePath(node)
            readFailures[node.id]?.let { throw it }
            return ByteArrayInputStream(bytes[node.id] ?: throw IOException("Not a file: ${node.id}"))
        }

        override fun openWrite(node: DocumentNode): OutputStream {
            mutations += "open-write:${relativePath(node)}"
            val output = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) = output.write(value)
                override fun write(source: ByteArray, offset: Int, length: Int) = output.write(source, offset, length)
                override fun close() {
                    bytes[node.id] = output.toByteArray()
                    advertisedLengths[node.id] = output.size().toLong()
                }
            }
        }

        override fun list(node: DocumentNode): List<DocumentNode> =
            children[node.id].orEmpty().values.map(nodes::getValue)

        override fun resolve(parent: DocumentNode, name: String): DocumentNode? =
            children[parent.id]?.get(name)?.let(nodes::get)

        override fun createDirectory(parent: DocumentNode, name: String): DocumentNode {
            mutations += "mkdir:${relativePath(parent)}:$name"
            return create(parent, name, isDirectory = true)
        }

        override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
            mutations += "create-file:${relativePath(parent)}:$name"
            return create(parent, name, isDirectory = false).also {
                bytes[it.id] = byteArrayOf()
                advertisedLengths[it.id] = 0
            }
        }

        override fun length(node: DocumentNode): Long = advertisedLengths[node.id] ?: 0

        private fun create(parent: DocumentNode, name: String, isDirectory: Boolean): DocumentNode {
            require(name.isNotBlank() && '/' !in name && '\\' !in name)
            check(resolve(parent, name) == null) { "Duplicate child $name" }
            val id = "${parent.id}/$name"
            val node = DocumentNode(id, name, isDirectory, 0)
            nodes[id] = node
            children.getOrPut(parent.id) { linkedMapOf() }[name] = id
            if (isDirectory) children[id] = linkedMapOf()
            return node
        }

        private fun resolvePath(relativePath: String): DocumentNode {
            var current = root
            safeParts(relativePath).forEach { name ->
                current = resolve(current, name) ?: error("No node at $relativePath")
            }
            return current
        }

        private fun relativePath(node: DocumentNode): String =
            if (node == root) "" else node.id.removePrefix("${root.id}/")

        private fun safeParts(relativePath: String): List<String> = relativePath.split('/').also { parts ->
            require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." })
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STARTED_AT = "2026-08-13T20:00:00Z"
        const val COMPLETED_AT = "2026-08-13T20:01:00Z"
        const val FORMER_ZIP_GUARD_BYTES = 300L * 1024L * 1024L

        val dryRun = RunIntent(OptimizeMode.SAFE, dryRun = true, apkLabMode = false, textMinify = true)
        val realRun = dryRun.copy(dryRun = false)
        val compressibleZip = zipBytes(
            "assets/repeated.bin",
            ByteArray(256 * 1024) { (it % 4).toByte() }
        )

        private fun zipBytes(entryName: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                zip.write(payload)
                zip.closeEntry()
            }
        }.toByteArray()
    }
}
