package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OptimizationCoordinatorDryRunTest {
    @Test
    fun dryRunCalculatesExactSavingsWithoutTreeWrites() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val outcome = coordinator(gateway, candidateDirectory).process(
                gateway.rootFile,
                "archive.zip",
                dryRunIntent,
                NeverCancelled
            )

            assertTrue(outcome is FileOutcome.WouldOptimize)
            outcome as FileOutcome.WouldOptimize
            assertEquals(compressibleZipFixture.size.toLong(), outcome.oldBytes)
            assertTrue(outcome.newBytes < outcome.oldBytes)
            assertEquals(outcome.oldBytes - outcome.newBytes, outcome.potentialSavingsBytes)
            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun dryRunSkipsAlreadyOptimizedArchiveWithoutTreeWritesOrLeakedCandidate() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(alreadyOptimizedZipFixture)
            val outcome = coordinator(gateway, candidateDirectory).process(
                gateway.rootFile,
                "archive.zip",
                dryRunIntent,
                NeverCancelled
            )

            assertTrue(outcome is FileOutcome.Skipped)
            outcome as FileOutcome.Skipped
            assertEquals("archive.zip", outcome.relativePath)
            assertEquals(SkipReason.NO_GAIN, outcome.reason)
            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun dryRunDoesNotReportUnsafeArchiveAsWouldOptimizeAndLeavesNoCandidate() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(unsafePathZipFixture)
            val outcome = coordinator(gateway, candidateDirectory).process(
                gateway.rootFile,
                "archive.zip",
                dryRunIntent,
                NeverCancelled
            )

            assertTrue(outcome is FileOutcome.Failed)
            assertEquals("archive.zip", outcome.relativePath)
            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun unsupportedZip64ArchiveIsReportedAsAnExplicitSkip() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary = throw UnsupportedZipFeatureException("ZIP64 archives")

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
                    error("verify must not run")
            }

            val outcome = expectType<FileOutcome.Skipped>(
                coordinator(gateway, candidateDirectory, processor).process(
                    gateway.rootFile,
                    "archive.zip",
                    dryRunIntent,
                    NeverCancelled
                )
            )

            assertEquals(SkipReason.UNSUPPORTED, outcome.reason)
            assertTrue(outcome.note.contains("ZIP64"))
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun archiveWorkLimitIsReportedAsAnExplicitSkip() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary = throw ArchiveResourceLimitException("inflated payload limit")

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
                    error("verify must not run")
            }

            val outcome = expectType<FileOutcome.Skipped>(
                coordinator(gateway, candidateDirectory, processor).process(
                    gateway.rootFile,
                    "archive.zip",
                    dryRunIntent,
                    NeverCancelled
                )
            )

            assertEquals(SkipReason.ARCHIVE_LIMIT, outcome.reason)
            assertTrue(outcome.note.contains("limit"))
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun candidateStorageLimitIsReportedAsAnExplicitSkipAndCleansThePartialFile() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary {
                    input.copyTo(OutputStream.nullOutputStream())
                    output.write(ByteArray(16))
                    return ZipOptimizationSummary(1, compressibleZipFixture.size.toLong(), 16, "oversized")
                }

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
                    error("verify must not run")
            }
            val coordinator = OptimizationCoordinator(
                gateway,
                CandidateStore(candidateDirectory, "storage-test", maxCandidateBytes = 8),
                processor
            )

            val outcome = expectType<FileOutcome.Skipped>(
                coordinator.process(gateway.rootFile, "archive.zip", dryRunIntent, NeverCancelled)
            )

            assertEquals(SkipReason.STORAGE_LIMIT, outcome.reason)
            assertTrue(outcome.note.contains("8-byte"))
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun briefConstructorUsesItsRunIdWithAnUnscopedCandidateStore() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val coordinator = OptimizationCoordinator(
                gateway,
                CandidateStore(candidateDirectory),
                "brief-run"
            )

            val outcome = coordinator.process(gateway.rootFile, "archive.zip", dryRunIntent, NeverCancelled)

            assertTrue(outcome is FileOutcome.WouldOptimize)
            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun dryRunVerifiesNonSmallerCandidateBeforeReturningNoGain() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                var verifyCalls = 0

                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary {
                    output.write(ByteArray(compressibleZipFixture.size + 1))
                    return ZipOptimizationSummary(1, 0, compressibleZipFixture.size + 1L, "invalid")
                }

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification {
                    verifyCalls++
                    throw IOException("invalid candidate")
                }
            }

            val outcome = coordinator(gateway, candidateDirectory, processor).process(
                gateway.rootFile,
                "archive.zip",
                dryRunIntent,
                NeverCancelled
            )

            assertEquals(1, processor.verifyCalls)
            assertTrue(outcome is FileOutcome.Failed)
            assertFalse(outcome is FileOutcome.Skipped && outcome.reason == SkipReason.NO_GAIN)
            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun cancellationBeforeCandidateCreationPropagatesWithoutCreatingCandidate() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)

            assertThrows(OptimizationCancelledException::class.java) {
                coordinator(gateway, candidateDirectory).process(
                    gateway.rootFile,
                    "archive.zip",
                    dryRunIntent,
                    CancellationToken { throw OptimizationCancelledException() }
                )
            }

            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun cancellationDuringOptimizationPropagatesAndDeletesCandidate() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary = throw OptimizationCancelledException()

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
                    error("verify must not run")
            }

            assertThrows(OptimizationCancelledException::class.java) {
                coordinator(gateway, candidateDirectory, processor).process(
                    gateway.rootFile, "archive.zip", dryRunIntent, NeverCancelled
                )
            }

            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    @Test
    fun cancellationDuringVerificationPropagatesAndDeletesCandidate() {
        withCandidateDirectory { candidateDirectory ->
            val gateway = RecordingDocumentGateway(compressibleZipFixture)
            val processor = object : ZipCandidateProcessor {
                override fun optimize(
                    input: InputStream,
                    output: OutputStream,
                    mode: OptimizeMode,
                    cancellation: CancellationToken
                ): ZipOptimizationSummary {
                    output.write(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
                    return ZipOptimizationSummary(1, 0, 4, "candidate")
                }

                override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification =
                    throw OptimizationCancelledException()
            }

            assertThrows(OptimizationCancelledException::class.java) {
                coordinator(gateway, candidateDirectory, processor).process(
                    gateway.rootFile, "archive.zip", dryRunIntent, NeverCancelled
                )
            }

            assertEquals(emptyList<String>(), gateway.writeOperations)
            assertDirectoryEmpty(candidateDirectory)
        }
    }

    private fun coordinator(
        gateway: RecordingDocumentGateway,
        candidateDirectory: java.io.File,
        processor: ZipCandidateProcessor = StrictStreamingZipCandidateProcessor
    ): OptimizationCoordinator = OptimizationCoordinator(
        gateway,
        CandidateStore(candidateDirectory, "dry-run-test"),
        processor
    )

    private fun withCandidateDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("fileforge-dry-run").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertDirectoryEmpty(directory: java.io.File) {
        assertEquals(emptyList<String>(), directory.list().orEmpty().sorted())
    }

    private class RecordingDocumentGateway(private val archive: ByteArray) : DocumentGateway {
        val rootFile = DocumentNode(
            id = "archive-id",
            name = "archive.zip",
            isDirectory = false,
            length = archive.size.toLong()
        )
        val writeOperations = mutableListOf<String>()

        override fun openRead(node: DocumentNode) = ByteArrayInputStream(archive)

        override fun openWrite(node: DocumentNode): OutputStream = mutation("openWrite:${node.id}")

        override fun list(node: DocumentNode): List<DocumentNode> = emptyList()

        override fun resolve(parent: DocumentNode, name: String): DocumentNode? = null

        override fun createDirectory(parent: DocumentNode, name: String): DocumentNode =
            mutation("createDirectory:${parent.id}:$name")

        override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode =
            mutation("createFile:${parent.id}:$mimeType:$name")

        override fun length(node: DocumentNode): Long = archive.size.toLong()

        private fun <T> mutation(operation: String): T {
            writeOperations += operation
            throw AssertionError("Dry run must not call $operation")
        }
    }

    private companion object {
        val dryRunIntent = RunIntent(
            mode = OptimizeMode.SAFE,
            dryRun = true,
            apkLabMode = false,
            textMinify = false
        )

        val compressibleZipFixture = zipBytes(
            entryName = "assets/repeated.bin",
            payload = ByteArray(512 * 1024) { (it % 4).toByte() },
            deflateLevel = Deflater.BEST_SPEED
        )

        val alreadyOptimizedZipFixture = zipBytes(
            entryName = "assets/already-optimized.bin",
            payload = deterministicBytes(256 * 1024),
            deflateLevel = 7
        )

        val unsafePathZipFixture = zipBytes(
            entryName = "../outside.txt",
            payload = ByteArray(32 * 1024) { 'x'.code.toByte() },
            deflateLevel = Deflater.BEST_SPEED
        )

        private fun zipBytes(entryName: String, payload: ByteArray, deflateLevel: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.setLevel(deflateLevel)
                zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                zip.write(payload)
                zip.closeEntry()
            }
            return output.toByteArray()
        }

        private fun deterministicBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var state = 0x13579bdf
            for (index in bytes.indices) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                bytes[index] = state.toByte()
            }
            return bytes
        }
    }
}
