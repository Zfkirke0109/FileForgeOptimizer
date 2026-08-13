package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeDetectorTest {
    @Test
    fun detectsZipFromBoundedHeader() {
        val header = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(12)
        assertEquals(FileKind.ZIP_LIKE, FileTypeDetector.detect("huge.zip", header))
    }
}
