package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CandidateStoreTest {
    @Test
    fun candidateIsCreatedOnlyInsideItsOwnRunDirectory() {
        withCacheDirectory { cacheDirectory ->
            val candidate = CandidateStore(cacheDirectory, "run-a").create(".zip")

            assertEquals(
                File(cacheDirectory, "fileforge/run-a").canonicalFile,
                candidate.file.parentFile.canonicalFile
            )
            assertTrue(candidate.file.name.startsWith("candidate-"))
            candidate.close()
            assertFalse(File(cacheDirectory, "fileforge/run-a").exists())
        }
    }

    @Test
    fun explicitStaleCleanupRemovesPriorRunContent() {
        withCacheDirectory { cacheDirectory ->
            val staleFile = File(cacheDirectory, "fileforge/stale-run/candidate-stale.zip")
            staleFile.parentFile.mkdirs()
            staleFile.writeText("stale")

            CandidateStore.cleanupStale(cacheDirectory)

            assertFalse(File(cacheDirectory, "fileforge").exists())
        }
    }

    @Test
    fun createFailureRemovesNewlyCreatedRunDirectory() {
        withCacheDirectory { cacheDirectory ->
            val fileSystem = FaultInjectingCandidateFileSystem(failCreate = true)
            val store = CandidateStore(cacheDirectory, "run-a", fileSystem)

            assertThrows(IOException::class.java) { store.create(".zip") }

            assertFalse(File(cacheDirectory, "fileforge/run-a").exists())
            assertFalse(File(cacheDirectory, "fileforge").exists())
        }
    }

    @Test
    fun closeReportsDeletionFailureAndExplicitCleanupCanRetryLater() {
        withCacheDirectory { cacheDirectory ->
            val fileSystem = FaultInjectingCandidateFileSystem()
            val candidate = CandidateStore(cacheDirectory, "run-a", fileSystem).create(".zip")
            fileSystem.failDelete = true

            assertThrows(CandidateCleanupException::class.java) { candidate.close() }
            assertTrue(candidate.file.exists())

            fileSystem.failDelete = false
            CandidateStore.cleanupStale(cacheDirectory, fileSystem)
            assertFalse(File(cacheDirectory, "fileforge").exists())
        }
    }

    @Test
    fun twoStoreInstancesDoNotDeleteEachOthersActiveRunDirectories() {
        withCacheDirectory { cacheDirectory ->
            val first = CandidateStore(cacheDirectory, "run-a").create(".zip")
            val second = CandidateStore(cacheDirectory, "run-b").create(".zip")

            first.close()
            assertTrue(second.file.exists())
            second.close()
            assertFalse(File(cacheDirectory, "fileforge").exists())
        }
    }

    @Test
    fun candidateOperationsAfterCloseAreRejected() {
        withCacheDirectory { cacheDirectory ->
            val candidate = CandidateStore(cacheDirectory, "run-a").create(".zip")
            candidate.close()

            assertThrows(IllegalStateException::class.java) { candidate.length }
            assertThrows(IllegalStateException::class.java) { candidate.openInputStream() }
            assertThrows(IllegalStateException::class.java) { candidate.openOutputStream() }
        }
    }

    private fun withCacheDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("fileforge-candidates").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FaultInjectingCandidateFileSystem(
        private val failCreate: Boolean = false
    ) : CandidateFileSystem {
        var failDelete = false

        override fun exists(file: File): Boolean = file.exists()

        override fun createDirectories(directory: File) {
            if (!directory.exists() && !directory.mkdirs()) throw IOException("cannot create directory")
        }

        override fun createTempFile(directory: File, suffix: String): File {
            if (failCreate) throw IOException("cannot create candidate")
            return File.createTempFile("candidate-", suffix, directory)
        }

        override fun delete(file: File): Boolean = !failDelete && (!file.exists() || file.delete())

        override fun deleteRecursively(directory: File): Boolean =
            !failDelete && (!directory.exists() || directory.deleteRecursively())

        override fun list(directory: File): Array<String>? = directory.list()
    }
}
