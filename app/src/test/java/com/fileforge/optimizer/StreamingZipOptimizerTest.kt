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
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class StreamingZipOptimizerTest {
    private val optimizer = StreamingZipOptimizer()

    @Test
    fun preservesForwardReadableMetadataForNestedDirectoryAndEmptyEntriesFromAnEightKiBChunkedSource() {
        val source = zipBytes(
            directoryEntry("nested/"),
            fileEntry("nested/empty.txt", byteArrayOf()),
            fileEntry("nested/data.txt", "payload".toByteArray(), extra = testExtra)
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
    fun rejectsWindowsDriveAndUncAbsoluteEntries() {
        listOf("C:\\outside.txt", "\\\\server\\share\\outside.txt").forEach { name ->
            val zip = zipBytes(fileEntry(name, "no".toByteArray()))

            assertThrows(UnsafeArchivePathException::class.java) {
                optimizer.optimize(zip.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
            }
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
    fun verificationAcceptsAnArchiveWithAValidCentralDirectoryAndEocd() {
        val zip = zipBytes(fileEntry("valid.txt", "complete".toByteArray()))

        val verification = optimizer.verify(zip.inputStream(), NeverCancelled)

        assertEquals(1, verification.entries)
        assertEquals(8L, verification.bytesRead)
    }

    @Test
    fun verificationAcceptsAValidMultiEntryCentralDirectory() {
        val zip = zipBytes(
            fileEntry("first.txt", "first".toByteArray()),
            fileEntry("second.txt", "second".toByteArray())
        )

        val verification = optimizer.verify(zip.inputStream(), NeverCancelled)

        assertEquals(2, verification.entries)
        assertEquals(11L, verification.bytesRead)
    }

    @Test
    fun verificationAcceptsOptionalDigitalSignatureAfterAllCentralFileHeaders() {
        val archive = appendToCentralDirectory(
            zipBytes(fileEntry("signed.txt", "payload".toByteArray())),
            digitalSignature(byteArrayOf(0x12, 0x34, 0x56))
        )

        val verification = optimizer.verify(archive.inputStream(), NeverCancelled)

        assertEquals(1, verification.entries)
        assertEquals(7L, verification.bytesRead)
    }

    @Test
    fun verificationRejectsDigitalSignatureWhoseDeclaredDataLengthExceedsCentralDirectory() {
        val malformed = appendToCentralDirectory(
            zipBytes(fileEntry("signed.txt", "payload".toByteArray())),
            digitalSignature(byteArrayOf(0x12), declaredLength = 2)
        )

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsBytesAfterDigitalSignatureInsideCentralDirectory() {
        val malformed = appendToCentralDirectory(
            zipBytes(fileEntry("signed.txt", "payload".toByteArray())),
            digitalSignature(byteArrayOf(0x12)) + byteArrayOf(0x7f)
        )

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsDigitalSignatureBeforeAllCentralFileHeaders() {
        val archive = zipBytes(
            fileEntry("first.txt", "first".toByteArray()),
            fileEntry("second.txt", "second".toByteArray())
        )
        val malformed = transformCentralDirectory(archive) { centralDirectory ->
            val firstRecordSize = centralDirectoryRecordSize(centralDirectory, 0)
            centralDirectory.copyOfRange(0, firstRecordSize) +
                digitalSignature(byteArrayOf(0x12)) +
                centralDirectory.copyOfRange(firstRecordSize, centralDirectory.size)
        }

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsArchiveTruncatedBeforeEocd() {
        val complete = zipBytes(fileEntry("truncated.txt", "content".toByteArray()))
        val truncated = complete.copyOfRange(0, complete.size - EOCD_BYTES)

        assertThrows(ZipException::class.java) {
            optimizer.verify(truncated.inputStream(), NeverCancelled)
        }
    }

    @Test
    fun verificationRejectsFabricatedFourByteCentralDirectory() {
        val malformed = archiveWithCentralDirectory(byteArrayOf(0x50, 0x4b, 0x01, 0x02))

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsIncompleteCentralDirectoryFixedHeader() {
        val malformed = archiveWithCentralDirectory(centralDirectoryBytes().copyOfRange(0, CENTRAL_DIRECTORY_FIXED_BYTES - 1))

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsCentralDirectoryVariableLengthsBeyondDeclaredBounds() {
        val malformedCentralDirectory = ByteArray(CENTRAL_DIRECTORY_FIXED_BYTES)
        writeInt(malformedCentralDirectory, 0, CENTRAL_DIRECTORY_SIGNATURE)
        writeShort(malformedCentralDirectory, 28, 1)
        val malformed = archiveWithCentralDirectory(malformedCentralDirectory)

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsCentralDirectoryWithWrongRecordCount() {
        val record = centralDirectoryBytes()
        val malformed = archiveWithCentralDirectory(record + record)

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsCentralDirectoryWithTrailingBytes() {
        val malformed = archiveWithCentralDirectory(centralDirectoryBytes() + byteArrayOf(0x7f))

        assertCentralDirectoryFailure(malformed)
    }

    @Test
    fun verificationRejectsZip64CentralDirectorySentinel() {
        val archive = zipBytes(fileEntry("zip64.txt", "data".toByteArray()))
        val malformed = archive.clone().also { bytes ->
            writeInt(bytes, eocdOffset(bytes) + 12, ZIP64_SENTINEL)
        }

        val exception = assertThrows(ZipException::class.java) {
            optimizer.verify(malformed.inputStream(), NeverCancelled)
        }
        assertTrue(exception.message.orEmpty().contains("Unsupported"))
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
        val duplicateArchive = validStoredArchive(
            "same.txt" to "first".toByteArray(),
            "same.txt" to "second".toByteArray()
        )

        val exception = assertThrows(ZipException::class.java) {
            optimizer.optimize(duplicateArchive.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
        }
        assertTrue(exception.message.orEmpty().contains("Duplicate archive entry"))
    }

    @Test
    fun rejectsDuplicateNamesAfterNormalizingDirectorySeparatorsDuringOptimization() {
        val duplicateArchive = validStoredArchive(
            "nested/file.txt" to "first".toByteArray(),
            "nested\\file.txt" to "second".toByteArray()
        )

        val exception = assertThrows(ZipException::class.java) {
            optimizer.optimize(duplicateArchive.inputStream(), ByteArrayOutputStream(), OptimizeMode.SAFE, NeverCancelled) {}
        }
        assertTrue(exception.message.orEmpty().contains("Duplicate archive entry"))
    }

    @Test
    fun rejectsDuplicateNamesAfterNormalizingDirectorySeparatorsDuringVerification() {
        val duplicateArchive = validStoredArchive(
            "nested/file.txt" to "first".toByteArray(),
            "nested\\file.txt" to "second".toByteArray()
        )

        val exception = assertThrows(ZipException::class.java) {
            optimizer.verify(duplicateArchive.inputStream(), NeverCancelled)
        }
        assertTrue(exception.message.orEmpty().contains("Duplicate archive entry"))
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
    fun rechecksCancellationAfterReadBeforeWritingOrReportingPayload() {
        val source = storedZipBytes("payload.bin", "payload".toByteArray())
        var cancelled = false
        val input = CancellingPayloadInputStream(source, firstLocalPayloadOffset(source)) { cancelled = true }
        var callbackBytes = 0L
        val output = ByteArrayOutputStream()
        val cancellation = CancellationToken {
            if (cancelled) throw TestCancellation()
        }

        assertThrows(TestCancellation::class.java) {
            optimizer.optimize(input, output, OptimizeMode.SAFE, cancellation) {
                callbackBytes += it
            }
        }
        assertEquals(0L, callbackBytes)
        assertArrayEquals(byteArrayOf(), readEntries(output.toByteArray()).single().contents)
    }

    @Test
    fun configuresZipOutputStreamWithAggressiveLevelAndReportsItInTheSummaryNote() {
        val output = ByteArrayOutputStream()
        val recordingOptimizer = RecordingStreamingZipOptimizer()

        val summary = recordingOptimizer.optimize(
            zipBytes(fileEntry("mode.txt", "payload".toByteArray())).inputStream(),
            output,
            OptimizeMode.AGGRESSIVE,
            NeverCancelled
        ) {}

        assertEquals(9, recordingOptimizer.configuredDeflateLevel)
        assertEquals("Recompressed ZIP container at deflate level 9. Entry comments are best-effort with forward-only input.", summary.note)
    }

    @Test
    fun preservesEntryTimestamp() {
        val timestamp = 1_700_000_000_000L
        val output = ByteArrayOutputStream()

        optimizer.optimize(
            zipBytes(fileEntry("dated.txt", "payload".toByteArray(), time = timestamp)).inputStream(),
            output,
            OptimizeMode.SAFE,
            NeverCancelled
        ) {}

        assertEquals(timestamp, readEntries(output.toByteArray()).single().time)
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
        val time: Long? = null
    )

    private data class ReadEntry(
        val name: String,
        val contents: ByteArray,
        val isDirectory: Boolean,
        val extra: ByteArray?,
        val time: Long
    )

    private fun directoryEntry(name: String) = EntryFixture(name, byteArrayOf(), isDirectory = true)

    private fun fileEntry(name: String, contents: ByteArray, extra: ByteArray? = null, time: Long? = null) =
        EntryFixture(name, contents, extra = extra, time = time)

    private fun zipBytes(vararg entries: EntryFixture): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { fixture ->
                zip.putNextEntry(ZipEntry(fixture.name).apply {
                    fixture.extra?.let { extra = it }
                    fixture.time?.let { time = it }
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

    private fun validStoredArchive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val centralDirectory = ByteArrayOutputStream()
        var localOffset = 0L
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
            writeInt(centralDirectory, CENTRAL_DIRECTORY_SIGNATURE)
            writeShort(centralDirectory, 20)
            writeShort(centralDirectory, 20)
            writeShort(centralDirectory, 0)
            writeShort(centralDirectory, ZipEntry.STORED)
            writeShort(centralDirectory, 0)
            writeShort(centralDirectory, 0)
            writeInt(centralDirectory, crc)
            writeInt(centralDirectory, contents.size.toLong())
            writeInt(centralDirectory, contents.size.toLong())
            writeShort(centralDirectory, nameBytes.size)
            writeShort(centralDirectory, 0)
            writeShort(centralDirectory, 0)
            writeShort(centralDirectory, 0)
            writeShort(centralDirectory, 0)
            writeInt(centralDirectory, 0)
            writeInt(centralDirectory, localOffset)
            centralDirectory.write(nameBytes)
            localOffset += 30L + nameBytes.size + contents.size
        }
        centralDirectory.writeTo(output)
        writeInt(output, EOCD_SIGNATURE)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, entries.size)
        writeShort(output, entries.size)
        writeInt(output, centralDirectory.size().toLong())
        writeInt(output, localOffset)
        writeShort(output, 0)
        return output.toByteArray()
    }

    private fun readEntries(bytes: ByteArray): List<ReadEntry> {
        val entries = mutableListOf<ReadEntry>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += ReadEntry(entry.name, zip.readBytes(), entry.isDirectory, entry.extra, entry.time)
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

    private fun writeInt(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun archiveWithCentralDirectory(centralDirectory: ByteArray): ByteArray {
        val validArchive = zipBytes(fileEntry("local.txt", "payload".toByteArray()))
        val localEnd = littleEndianInt(validArchive, eocdOffset(validArchive) + 16)
        val output = ByteArrayOutputStream()
        output.write(validArchive, 0, localEnd)
        output.write(centralDirectory)
        writeInt(output, EOCD_SIGNATURE)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, 1)
        writeShort(output, 1)
        writeInt(output, centralDirectory.size.toLong())
        writeInt(output, localEnd.toLong())
        writeShort(output, 0)
        return output.toByteArray()
    }

    private fun centralDirectoryBytes(): ByteArray {
        val validArchive = zipBytes(fileEntry("local.txt", "payload".toByteArray()))
        val eocd = eocdOffset(validArchive)
        val offset = littleEndianInt(validArchive, eocd + 16)
        val size = littleEndianInt(validArchive, eocd + 12)
        return validArchive.copyOfRange(offset, offset + size)
    }

    private fun appendToCentralDirectory(archive: ByteArray, suffix: ByteArray): ByteArray =
        transformCentralDirectory(archive) { it + suffix }

    private fun transformCentralDirectory(archive: ByteArray, transform: (ByteArray) -> ByteArray): ByteArray {
        val oldEocd = eocdOffset(archive)
        val centralOffset = littleEndianInt(archive, oldEocd + 16)
        val centralSize = littleEndianInt(archive, oldEocd + 12)
        val transformed = transform(archive.copyOfRange(centralOffset, centralOffset + centralSize))
        val output = ByteArrayOutputStream()
        output.write(archive, 0, centralOffset)
        output.write(transformed)
        output.write(archive, oldEocd, archive.size - oldEocd)
        return output.toByteArray().also { rebuilt ->
            val newEocd = centralOffset + transformed.size
            writeInt(rebuilt, newEocd + 12, transformed.size.toLong())
        }
    }

    private fun centralDirectoryRecordSize(bytes: ByteArray, offset: Int): Int =
        CENTRAL_DIRECTORY_FIXED_BYTES + littleEndianShort(bytes, offset + 28) +
            littleEndianShort(bytes, offset + 30) + littleEndianShort(bytes, offset + 32)

    private fun digitalSignature(data: ByteArray, declaredLength: Int = data.size): ByteArray =
        ByteArrayOutputStream().also { output ->
            writeInt(output, CENTRAL_DIRECTORY_DIGITAL_SIGNATURE)
            writeShort(output, declaredLength)
            output.write(data)
        }.toByteArray()

    private fun eocdOffset(bytes: ByteArray): Int = bytes.size - EOCD_BYTES

    private fun assertCentralDirectoryFailure(archive: ByteArray) {
        val exception = assertThrows(ZipException::class.java) {
            optimizer.verify(archive.inputStream(), NeverCancelled)
        }
        assertTrue(exception.message.orEmpty().contains("central directory", ignoreCase = true))
    }

    private class EightKiBInputStream(input: InputStream) : FilterInputStream(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, 8 * 1024))
    }

    private class CancellingPayloadInputStream(
        private val source: ByteArray,
        private val payloadOffset: Int,
        private val cancel: () -> Unit
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            val oneByte = ByteArray(1)
            return if (read(oneByte, 0, 1) < 0) -1 else oneByte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= source.size) return -1
            if (position >= payloadOffset) cancel()
            val beforePayload = if (position < payloadOffset) payloadOffset - position else 1
            val count = minOf(length, beforePayload, source.size - position)
            System.arraycopy(source, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private class TestCancellation : RuntimeException()

    private class RecordingStreamingZipOptimizer : StreamingZipOptimizer() {
        var configuredDeflateLevel = -1

        override fun createZipOutputStream(output: OutputStream): ZipOutputStream =
            object : ZipOutputStream(output) {
                override fun setLevel(level: Int) {
                    configuredDeflateLevel = level
                    super.setLevel(level)
                }
            }
    }

    private companion object {
        const val EOCD_BYTES = 22
        const val CENTRAL_DIRECTORY_FIXED_BYTES = 46
        const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
        const val CENTRAL_DIRECTORY_DIGITAL_SIGNATURE = 0x05054b50L
        const val EOCD_SIGNATURE = 0x06054b50L
        const val ZIP64_SENTINEL = 0xffffffffL
        val testExtra = byteArrayOf(0x34, 0x12, 0x01, 0x00, 0x7f)
    }
}
