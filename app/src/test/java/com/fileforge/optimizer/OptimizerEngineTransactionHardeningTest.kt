package com.fileforge.optimizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OptimizerEngineTransactionHardeningTest {
    @Test
    fun zipUndoWriteOrFlushFailureRollsBackAndNeverCountsUnloggedReplacement() {
        listOf(UndoFault.WRITE, UndoFault.FLUSH).forEach { fault ->
            withEngine(realRun) { gateway, engine, _ ->
                gateway.put("archive.zip", zipFixture)
                gateway.undoFault = fault

                val report = engine.run(NeverCancelled) {}

                assertArrayEquals("original restored for $fault", zipFixture, gateway.contents("archive.zip"))
                assertEquals(0, report.optimized)
                assertEquals(0, report.savedBytes)
                assertTrue(report.errors > 0)
            }
        }
    }

    @Test
    fun nonZipUndoWriteOrFlushFailureRollsBackAndNeverCountsUnloggedReplacement() {
        listOf(UndoFault.WRITE, UndoFault.FLUSH).forEach { fault ->
            withEngine(realRun) { gateway, engine, _ ->
                val original = "%PDF-1.4\n%%EOF\ntrailing garbage".toByteArray()
                gateway.put("document.pdf", original)
                gateway.undoFault = fault

                val report = engine.run(NeverCancelled) {}

                assertArrayEquals("original restored for $fault", original, gateway.contents("document.pdf"))
                assertEquals(0, report.optimized)
                assertEquals(0, report.savedBytes)
                assertTrue(report.errors > 0)
            }
        }
    }

    @Test
    fun undoExactCreationFaultRejectsBeforeAnyOriginalMutation() {
        ExactCreationFault.entries.forEach { fault ->
            withEngine(realRun) { gateway, engine, _ ->
                gateway.put("archive.zip", zipFixture)
                gateway.exactCreationFault = fault

                val report = engine.run(NeverCancelled) {}

                assertEquals(RunStatus.FAILED, report.status)
                assertArrayEquals(zipFixture, gateway.contents("archive.zip"))
                assertTrue(gateway.mutations.none { it == "open-write:archive.zip" })
            }
        }
    }

    @Test
    fun secondRunOfSameEngineRejectsBeforeAnyAdditionalTreeMutation() = withEngine(realRun) { gateway, engine, _ ->
        gateway.put("unknown.bin", byteArrayOf(1))
        engine.run(NeverCancelled) {}
        val mutationsAfterFirst = gateway.mutations.toList()

        assertThrows(IllegalStateException::class.java) { engine.run(NeverCancelled) {} }

        assertEquals(mutationsAfterFirst, gateway.mutations)
    }

    @Test
    fun concurrentSecondRunRejectsBeforeSelectedTreeMutation() = withEngine(realRun) { gateway, engine, _ ->
        gateway.put("unknown.bin", byteArrayOf(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.firstListEntered = entered
        gateway.blockFirstList = release
        val first = Thread { engine.run(NeverCancelled) {} }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val mutationsDuringFirst = gateway.mutations.toList()

        try {
            assertThrows(IllegalStateException::class.java) { engine.run(NeverCancelled) {} }
            assertEquals(mutationsDuringFirst, gateway.mutations)
        } finally {
            release.countDown()
            first.join(5_000)
        }
        assertFalse(first.isAlive)
    }

    @Test
    fun closeFailureAfterFlushedTerminalDoesNotChangeReturnedDurableReport() = withEngine(realRun) { gateway, engine, _ ->
        gateway.put("unknown.bin", byteArrayOf(1))
        gateway.undoFault = UndoFault.CLOSE_AFTER_FLUSH

        val report = engine.run(NeverCancelled) {}
        val durable = gateway.readDurableUndo()

        assertEquals(RunStatus.COMPLETED, report.status)
        assertEquals(report.status, durable.status)
        assertEquals(report.errors, durable.terminal!!.errors)
        assertEquals(report.scanned, durable.terminal!!.scanned)
        assertEquals(report.skipped, durable.terminal!!.skipped)
    }

    @Test
    fun terminalWriteOrFlushFailureReturnsFailedNeverRunningEvenWhenLogRemainsInterrupted() {
        listOf(UndoFault.WRITE, UndoFault.FLUSH).forEach { fault ->
            withEngine(realRun) { gateway, engine, _ ->
                gateway.put("unknown.bin", byteArrayOf(1))
                gateway.undoFault = fault

                val report = engine.run(NeverCancelled) {}
                val durable = gateway.readDurableUndo()

                assertEquals(RunStatus.FAILED, report.status)
                assertTrue(report.errors > 0)
                assertEquals("unfinalized durable log must parse as interrupted for $fault", RunStatus.RUNNING, durable.status)
                assertEquals(null, durable.terminal)
            }
        }
    }

    @Test
    fun candidateCleanupFailureAfterCommitKeepsReportAndTerminalConsistentWithDurableEntry() {
        val cache = Files.createTempDirectory("fileforge-engine-cleanup").toFile()
        try {
            val gateway = TransactionalEngineGateway().apply { put("archive.zip", zipFixture) }
            val engine = OptimizerEngine(
                documentGateway = gateway,
                selectedRoot = gateway.root,
                candidateStore = CandidateStore(cache, RUN_ID, DeleteFailingCandidateFileSystem),
                runId = RUN_ID,
                runIntent = realRun,
                startedAt = { "2026-08-13T20:00:00Z" },
                completedAt = { "2026-08-13T20:01:00Z" },
                appVersion = "test",
                buildVariant = "standard-test"
            )

            val report = engine.run(NeverCancelled) {}
            val durable = gateway.readDurableUndo()

            assertEquals(1, report.optimized)
            assertEquals(1, durable.entries.size)
            assertEquals(report.optimized, durable.terminal!!.optimized)
            assertEquals(report.savedBytes, durable.terminal!!.savedBytes)
            assertArrayEquals(optimizeZip(zipFixture), gateway.contents("archive.zip"))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun runInvariantFailureWritesFailedTerminalNeverRunningTerminal() = withEngine(realRun) { gateway, engine, _ ->
        gateway.put("unknown.bin", byteArrayOf(1))
        gateway.runInvariantFailure = RunInvariantException("selected tree contract lost")

        val report = engine.run(NeverCancelled) {}
        val durable = gateway.readDurableUndo()

        assertEquals(RunStatus.FAILED, report.status)
        assertEquals(RunStatus.FAILED, durable.status)
        assertEquals(RunStatus.FAILED, durable.terminal!!.status)
    }

    private fun withEngine(
        intent: RunIntent,
        block: (TransactionalEngineGateway, OptimizerEngine, File) -> Unit
    ) {
        val cache = Files.createTempDirectory("fileforge-engine-hardening").toFile()
        try {
            val gateway = TransactionalEngineGateway()
            val engine = OptimizerEngine(
                documentGateway = gateway,
                selectedRoot = gateway.root,
                candidateStore = CandidateStore(cache, RUN_ID),
                runId = RUN_ID,
                runIntent = intent,
                startedAt = { "2026-08-13T20:00:00Z" },
                completedAt = { "2026-08-13T20:01:00Z" },
                appVersion = "test",
                buildVariant = "standard-test"
            )
            block(gateway, engine, cache)
        } finally {
            cache.deleteRecursively()
        }
    }

    private class TransactionalEngineGateway : FaultInjectingEngineGateway() {
        var runInvariantFailure: RunInvariantException? = null
        override fun list(node: DocumentNode): List<DocumentNode> {
            runInvariantFailure?.let { throw it }
            return super.list(node)
        }

        fun readDurableUndo(): UndoRun = UndoLogRepository().read(
            StringReader(contents("FileForge_Undo_v2_$RUN_ID.jsonl").toString(Charsets.UTF_8))
        )
    }

    private companion object {
        const val RUN_ID = "run-transaction"
        val realRun = RunIntent(OptimizeMode.SAFE, dryRun = false, apkLabMode = false, textMinify = true)
        val zipFixture = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry("repeated.bin").apply { time = 0L })
                zip.write(ByteArray(128 * 1024) { (it % 4).toByte() })
                zip.closeEntry()
            }
        }.toByteArray()

        private fun optimizeZip(input: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
            StreamingZipOptimizer().optimize(input.inputStream(), output, OptimizeMode.SAFE, NeverCancelled) {}
        }.toByteArray()
    }
}
