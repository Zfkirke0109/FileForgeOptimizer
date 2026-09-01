package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.file.Files

class OptimizationCoordinatorHardeningTest {
    @Test
    fun exactZipDecisionUsesStreamedSourceBytesInsteadOfProviderLength() {
        listOf(0L, original.size.toLong() / 2, original.size.toLong() + 500_000L).forEach { advertised ->
            withCoordinator(advertisedLength = advertised) { gateway, coordinator, _ ->
                val outcome = expectType<FileOutcome.WouldOptimize>(
                    coordinator.process(gateway.node("archive.zip")!!, "archive.zip", dryRun, NeverCancelled)
                )
                assertEquals(original.size.toLong(), outcome.oldBytes)
                assertEquals(candidate.size.toLong(), outcome.newBytes)
                assertEquals(original.size.toLong() - candidate.size, outcome.potentialSavingsBytes)
            }
        }
    }

    @Test
    fun realZipCommitIgnoresProviderLengthAndUsesStreamedIntegrity() {
        listOf(0L, original.size.toLong() / 2, original.size.toLong() + 500_000L).forEach { advertised ->
            withCoordinator(advertisedLength = advertised) { gateway, coordinator, undo ->
                val outcome = expectType<FileOutcome.Optimized>(
                    coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
                )
                assertEquals(original.size.toLong(), outcome.oldBytes)
                assertEquals(candidate.size.toLong(), outcome.newBytes)
                assertEquals(original.sha256(), undo.entries.single().originalSha256)
                assertEquals(candidate.toList(), gateway.contents("archive.zip").toList())
            }
        }
    }

    @Test
    fun sameSizeSourceSwapBetweenCandidateAndBackupNeverWritesOriginal() = withCoordinator { gateway, coordinator, undo ->
        val changed = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        gateway.afterEvent = { event ->
            if (event == "mkdir:root:FileForge_Backups_run-1") gateway.replaceContents("archive.zip", changed)
        }

        val outcome = expectType<FileOutcome.Failed>(
            coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
        )

        assertEquals(RollbackResult.NotNeeded, outcome.rollback)
        assertFalse(gateway.events.contains("write:root/archive.zip"))
        assertEquals(changed.toList(), gateway.contents("archive.zip").toList())
        assertTrue(undo.entries.isEmpty())
    }

    @Test
    fun backupExactCreationFailureNeverMutatesOriginal() {
        listOf(ExactCreationFault.COLLISION_RENAME, ExactCreationFault.CREATE_RACE, ExactCreationFault.UNREACHABLE_RETURNED_NODE).forEach { fault ->
            withCoordinator { gateway, coordinator, undo ->
                gateway.exactCreationFault = fault

                expectType<FileOutcome.Failed>(
                    coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
                )

                assertFalse("original mutated for $fault", gateway.events.contains("write:root/archive.zip"))
                assertEquals(original.toList(), gateway.contents("archive.zip").toList())
                assertTrue(undo.entries.isEmpty())
            }
        }
    }

    @Test
    fun cleanupFailureAfterDurableCommitPreservesOptimizedOutcomeAndSurfacesWarning() {
        val cache = Files.createTempDirectory("fileforge-cleanup-warning").toFile()
        try {
            val gateway = HardenedGateway().apply { put("archive.zip", original); events.clear() }
            val undo = RecordingUndoEntrySink(gateway.events)
            val store = CandidateStore(cache, "run-1", DeleteFailingCandidateFileSystem)
            val coordinator = OptimizationCoordinator(
                gateway,
                store,
                FixedCandidateProcessor(candidate, gateway.events),
                CommitContext(gateway.root, "run-1", undo) { "2026-08-13T20:00:00Z" }
            )

            val outcome = expectType<FileOutcome.Optimized>(
                coordinator.process(gateway.node("archive.zip")!!, "archive.zip", realRun, NeverCancelled)
            )

            assertEquals(candidate.toList(), gateway.contents("archive.zip").toList())
            assertEquals(1, undo.entries.size)
            assertTrue(outcome.note.contains("cleanup", ignoreCase = true))
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun withCoordinator(
        advertisedLength: Long = original.size.toLong(),
        block: (HardenedGateway, OptimizationCoordinator, RecordingUndoEntrySink) -> Unit
    ) {
        val cache = Files.createTempDirectory("fileforge-coordinator-hardening").toFile()
        try {
            val gateway = HardenedGateway().apply {
                put("archive.zip", original, advertisedLength)
                events.clear()
            }
            val undo = RecordingUndoEntrySink(gateway.events)
            val coordinator = OptimizationCoordinator(
                gateway,
                CandidateStore(cache, "run-1"),
                FixedCandidateProcessor(candidate, gateway.events),
                CommitContext(gateway.root, "run-1", undo) { "2026-08-13T20:00:00Z" }
            )
            block(gateway, coordinator, undo)
        } finally {
            cache.deleteRecursively()
        }
    }

    private companion object {
        val original = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(96 * 1024) { (it % 7).toByte() }
        val candidate = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(48 * 1024) { (it % 3).toByte() }
        val realRun = RunIntent(OptimizeMode.SAFE, dryRun = false, apkLabMode = false, textMinify = false)
        val dryRun = realRun.copy(dryRun = true)
    }
}

internal object DeleteFailingCandidateFileSystem : CandidateFileSystem {
    override fun exists(file: File): Boolean = file.exists()
    override fun createDirectories(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("mkdir failed")
    }
    override fun createTempFile(directory: File, suffix: String): File = File.createTempFile("candidate-", suffix, directory)
    override fun delete(file: File): Boolean = if (file.isFile) false else !file.exists() || file.delete()
    override fun deleteRecursively(directory: File): Boolean = !directory.exists() || directory.deleteRecursively()
    override fun list(directory: File): Array<String>? = directory.list()
}

internal enum class ExactCreationFault { COLLISION_RENAME, CREATE_RACE, UNREACHABLE_RETURNED_NODE }

/** Adds only hardening hooks to the existing deterministic SAF-shaped gateway fixture. */
internal class HardenedGateway : DocumentGateway {
    private val delegate = RecordingDocumentGateway()
    val root: DocumentNode get() = delegate.root
    val events: MutableList<String> get() = delegate.events
    var afterEvent: ((String) -> Unit)?
        get() = delegate.afterEvent
        set(value) { delegate.afterEvent = value }
    var exactCreationFault: ExactCreationFault? = null

    fun put(path: String, contents: ByteArray, advertisedLength: Long = contents.size.toLong()): DocumentNode =
        delegate.put(path, contents).also { advertisedLengths[it.id] = advertisedLength }

    fun node(path: String): DocumentNode? = delegate.node(path)
    fun contents(path: String): ByteArray = delegate.contents(path)
    fun replaceContents(path: String, contents: ByteArray) = delegate.replaceContents(path, contents)

    private val advertisedLengths = mutableMapOf<String, Long>()

    override fun openRead(node: DocumentNode) = delegate.openRead(node)
    override fun openWrite(node: DocumentNode) = delegate.openWrite(node)
    override fun list(node: DocumentNode) = delegate.list(node)
    override fun resolve(parent: DocumentNode, name: String) = delegate.resolve(parent, name)
    override fun createDirectory(parent: DocumentNode, name: String) = delegate.createDirectory(parent, name)
    override fun createFile(parent: DocumentNode, mimeType: String, name: String) = delegate.createFile(parent, mimeType, name)
    override fun length(node: DocumentNode): Long = advertisedLengths[node.id] ?: delegate.length(node)

    override fun createDirectoryExact(parent: DocumentNode, name: String): DocumentNode = exactCreate(parent, name, true)
    override fun createFileExact(parent: DocumentNode, mimeType: String, name: String): DocumentNode = exactCreate(parent, name, false)

    private fun exactCreate(parent: DocumentNode, name: String, directory: Boolean): DocumentNode = when (exactCreationFault) {
        ExactCreationFault.COLLISION_RENAME -> {
            if (directory) delegate.createDirectory(parent, "$name (1)")
            else delegate.createFile(parent, "application/octet-stream", "$name (1)")
            throw IOException("provider collision-renamed exact creation")
        }
        ExactCreationFault.CREATE_RACE -> {
            if (delegate.resolve(parent, name) == null) {
                if (directory) delegate.createDirectory(parent, name) else delegate.createFile(parent, "application/octet-stream", name)
            }
            throw IOException("exact creation lost a race")
        }
        ExactCreationFault.UNREACHABLE_RETURNED_NODE -> throw IOException("created node is unreachable by requested name")
        null -> if (directory) delegate.createDirectory(parent, name)
            else delegate.createFile(parent, "application/octet-stream", name)
    }
}
