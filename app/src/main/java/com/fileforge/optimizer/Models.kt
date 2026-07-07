package com.fileforge.optimizer

enum class OptimizeMode { SAFE, AGGRESSIVE }

data class OptimizerSettings(
    val mode: OptimizeMode,
    val apkLabMode: Boolean,
    val textMinify: Boolean
)

data class OptimizationReport(
    var scanned: Int = 0,
    var optimized: Int = 0,
    var skipped: Int = 0,
    var errors: Int = 0,
    var savedBytes: Long = 0
)

enum class FileKind {
    ZIP_LIKE,
    APK,
    PNG,
    JPEG,
    PDF,
    JSON,
    XML,
    SVG,
    TEXT,
    UNSUPPORTED
}

data class OptimizeResult(
    val bytes: ByteArray,
    val note: String
)
