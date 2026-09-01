package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeDetectorTest {
    @Test
    fun detectsZipFromBoundedHeader() {
        val header = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(12)
        assertEquals(FileKind.ZIP_LIKE, FileTypeDetector.detect("huge.zip", header))
    }

    @Test
    fun truncatedZipMagicFallsBackToUnsupported() {
        assertEquals(FileKind.UNSUPPORTED, FileTypeDetector.detect("truncated.zip", byteArrayOf(0x50, 0x4b, 0x03)))
    }

    @Test
    fun truncatedPngMagicFallsBackToUnsupported() {
        assertEquals(FileKind.UNSUPPORTED, FileTypeDetector.detect("truncated.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e)))
    }

    @Test
    fun truncatedJpegMagicFallsBackToUnsupported() {
        assertEquals(FileKind.UNSUPPORTED, FileTypeDetector.detect("truncated.jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte())))
    }

    @Test
    fun truncatedPdfMagicFallsBackToUnsupported() {
        assertEquals(FileKind.UNSUPPORTED, FileTypeDetector.detect("truncated.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46)))
    }
}
