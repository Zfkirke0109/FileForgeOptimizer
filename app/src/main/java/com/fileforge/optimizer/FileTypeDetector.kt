package com.fileforge.optimizer

import java.util.Locale

object FileTypeDetector {
    private val zipLikeExtensions = setOf("zip", "jar", "epub", "docx", "xlsx", "pptx")
    private val textExtensions = setOf("txt")

    fun detect(name: String, bytes: ByteArray): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val magic = bytes.take(16).map { it.toInt() and 0xff }

        val isZipMagic = magic.size >= 4 && magic[0] == 0x50 && magic[1] == 0x4b &&
            (magic[2] == 0x03 || magic[2] == 0x05 || magic[2] == 0x07) &&
            (magic[3] == 0x04 || magic[3] == 0x06 || magic[3] == 0x08)
        val isPngMagic = magic.size >= 8 &&
            magic[0] == 0x89 && magic[1] == 0x50 && magic[2] == 0x4e && magic[3] == 0x47 &&
            magic[4] == 0x0d && magic[5] == 0x0a && magic[6] == 0x1a && magic[7] == 0x0a
        val isJpegMagic = magic.size >= 3 && magic[0] == 0xff && magic[1] == 0xd8 && magic[2] == 0xff
        val isPdfMagic = magic.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.ISO_8859_1) == "%PDF-"

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
}
