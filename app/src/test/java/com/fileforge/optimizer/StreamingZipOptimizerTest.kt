package com.fileforge.optimizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StreamingZipOptimizerTest {
    private val optimizer = StreamingZipOptimizer()

    @Test
    fun optimizesNestedDirectoryAndEmptyEntriesFromAnEightKiBChunkedSource() {
        val source = zipBytes(
            directoryEntry("nested/"),
            fileEntry("nested/empty.txt", byteArrayOf()),
            fileEntry("nested/data.txt", "payload".toByteArray(), extra = testExtra, comment = "keep me")
        )
        val output = ByteArrayOutputStream()
        var transferred = 0L

        val summary = optimizer.optimize(
            EightKiBInputStream(ByteArrayInputStream(source)),
            output,
            OptimizeMode.SAFE,
            NeverCancelled
        ) { transferred += it }

        assertEquals(3, summary.entries)
        assertEquals(7L, summary.inputBytes)
        assertEquals(7L, transferred)
        assertEquals(output.size().toLong(), summary.outputBytes)

        val entries = readEntries(output.toByteArray())
        assertEquals(listOf("nested/", "nested/empty.txt", "nested/data.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertArrayEquals(byteArrayOf(), entries[1].contents)
        assertArrayEquals("payload".toByteArray(), entries[2].contents)
        assertArrayEquals(testExtra, entries[2].extra)
    }

    @Test
    fun rejectsTraversalEntry() {
        val zip = zipBytes(fileEntry("../escape.txt", "no".toByteArray()))

        assertThrows(UnsafeArchivePathException::class.java) {
            optimizer.optimize(zip.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
        }
    }

    @Test
    fun rejectsAbsoluteEntry() {
        val zip = zipBytes(fileEntry("/outside.txt", "no".toByteArray()))

        assertThrows(UnsafeArchivePathException::class.java) {
            optimizer.optimize(zip.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
        }
    }

    @Test
    fun rejectsUnsafeEntryDuringVerification() {
        val zip = zipBytes(fileEntry("nested/../../outside.txt", "no".toByteArray()))

        assertThrows(UnsafeArchivePathException::class.java) {
            optimizer.verify(zip.inputStream(), NeverCancelled)
        }
    }

    @Test
    fun rejectsArchiveWithNoEntriesDuringVerification() {
        val emptyArchive = ByteArrayOutputStream().also { ZipOutputStream(it).use { } }.toByteArray()

        assertThrows(ZipException::class.java) {
            optimizer.verify(emptyArchive.inputStream(), NeverCancelled)
        }
    }

    @Test
    fun surfacesMalformedEntryCrcDuringVerification() {
        val source = storedZipBytes("crc.txt", "integrity".toByteArray())
        val corrupt = source.clone().also { bytes ->
            bytes[firstLocalPayloadOffset(bytes)] = (bytes[firstLocalPayloadOffset(bytes)].toInt() xor 0x01).toByte()
        }

        assertThrows(ZipException::class.java) {
            optimizer.verify(corrupt.inputStream(), NeverCancelled)
        }
    }

    @Test
    fun rejectsDuplicateEntryNamesInsteadOfSilentlyCollapsingThem() {
        val duplicateArchive = rawStoredEntries(
            "same.txt" to "first".toByteArray(),
            "same.txt" to "second".toByteArray()
        )

        assertThrows(ZipException::class.java) {
            optimizer.optimize(duplicateArchive.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
        }
    }

    @Test
    fun invokesCancellationBeforePayloadTransfer() {
        val zip = zipBytes(fileEntry("large.bin", ByteArray(40 * 1024) { it.toByte() }))
        var checks = 0
        val cancellation = CancellationToken {
            if (++checks == 2) throw TestCancellation()
        }

        assertThrows(TestCancellation::class.java) {
            optimizer.optimize(zip.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, cancellation) {}
        }
    }

    @Test
    fun verificationReportsEntriesAndDrainedPayloadBytes() {
        val zip = zipBytes(
            fileEntry("one.txt", "one".toByteArray()),
            fileEntry("two.txt", "two-two".toByteArray())
        )

        val verification = optimizer.verify(zip.inputStream(), NeverCancelled)

        assertEquals(2, verification.entries)
        assertEquals(10L, verification.bytesRead)
    }

    private data class EntryFixture(
        val name: String,
        val contents: ByteArray,
        val isDirectory: Boolean = false,
        val extra: ByteArray? = null,
        val comment: String? = null
    )

    private data class ReadEntry(
        val name: String,
        val contents: ByteArray,
        val isDirectory: Boolean,
        val extra: ByteArray?
    )

    private fun directoryEntry(name: String) = EntryFixture(name, byteArrayOf(), isDirectory = true)

    private fun fileEntry(name: String, contents: ByteArray, extra: ByteArray? = null, comment: String? = null) =
        EntryFixture(name, contents, extra = extra, comment = comment)

    private fun zipBytes(vararg entries: EntryFixture): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { fixture ->
                zip.putNextEntry(ZipEntry(fixture.name).apply {
                    fixture.extra?.let { extra = it }
                    comment = fixture.comment
                })
                if (!fixture.isDirectory) zip.write(fixture.contents)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun storedZipBytes(name: String, contents: ByteArray): ByteArray {
        val crc = CRC32().apply { update(contents) }.value
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = contents.size.toLong()
                compressedSize = contents.size.toLong()
                this.crc = crc
            })
            zip.write(contents)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun rawStoredEntries(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { (name, contents) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(contents) }.value
            writeInt(output, 0x04034b50)
            writeShort(output, 20)
            writeShort(output, 0)
            writeShort(output, ZipEntry.STORED)
            writeShort(output, 0)
            writeShort(output, 0)
            writeInt(output, crc)
            writeInt(output, contents.size.toLong())
            writeInt(output, contents.size.toLong())
            writeShort(output, nameBytes.size)
            writeShort(output, 0)
            output.write(nameBytes)
            output.write(contents)
        }
        return output.toByteArray()
    }

    private fun readEntries(bytes: ByteArray): List<ReadEntry> {
        val entries = mutableListOf<ReadEntry>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += ReadEntry(entry.name, zip.readBytes(), entry.isDirectory, entry.extra)
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun firstLocalPayloadOffset(bytes: ByteArray): Int {
        assertEquals(0x04034b50, littleEndianInt(bytes, 0))
        val nameLength = littleEndianShort(bytes, 26)
        val extraLength = littleEndianShort(bytes, 28)
        return 30 + nameLength + extraLength
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Long) {
        writeShort(output, (value and 0xffff).toInt())
        writeShort(output, ((value ushr 16) and 0xffff).toInt())
    }

    private class EightKiBInputStream(input: InputStream) : FilterInputStream(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, 8 * 1024))
    }

    private class TestCancellation : RuntimeException()

    private companion object {
        val testExtra = byteArrayOf(0x34, 0x12, 0x01, 0x00, 0x7f)
    }
}
