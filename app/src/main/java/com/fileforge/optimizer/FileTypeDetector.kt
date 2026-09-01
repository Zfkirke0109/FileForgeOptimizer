package com.fileforge.optimizer

import java.util.Locale

object FileTypeDetector {
    private val zipLikeExtensions = setOf("zip", "jar", "epub", "docx", "xlsx", "pptx")
    private val textExtensions = setOf("txt")

    fun detect(name: String, header: ByteArray): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)

        val isZipMagic = header.hasMagic(0x50, 0x4b, 0x03, 0x04) ||
            header.hasMagic(0x50, 0x4b, 0x05, 0x06) ||
            header.hasMagic(0x50, 0x4b, 0x07, 0x08)
        val isPngMagic = header.hasMagic(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val isJpegMagic = header.hasMagic(0xff, 0xd8, 0xff)
        val isPdfMagic = header.hasMagic(0x25, 0x50, 0x44, 0x46, 0x2d)

        return when {
            ext == "apk" && isZipMagic -> FileKind.APK
            ext in zipLikeExtensions && isZipMagic -> FileKind.ZIP_LIKE
            ext == "png" && isPngMagic -> FileKind.PNG
            (ext == "jpg" || ext == "jpeg") && isJpegMagic -> FileKind.JPEG
            ext == "pdf" && isPdfMagic -> FileKind.PDF
            ext == "json" -> FileKind.JSON
            ext == "xml" -> FileKind.XML
            ext == "svg" -> FileKind.SVG
            ext in textExtensions -> FileKind.TEXT
            else -> FileKind.UNSUPPORTED
        }
    }

    private fun ByteArray.hasMagic(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { index -> this[index].unsigned() == expected[index] }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
