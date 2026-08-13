package com.fileforge.optimizer

enum class OptimizeMode { SAFE, AGGRESSIVE }

data class OptimizerSettings(
    val mode: OptimizeMode,
    val apkLabMode: Boolean,
    val textMinify: Boolean
)

data class RunIntent(
    val mode: OptimizeMode,
    val dryRun: Boolean,
    val apkLabMode: Boolean,
    val textMinify: Boolean
) {
    val allowsSelectedTreeWrites: Boolean get() = !dryRun
}

enum class RunStatus { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED }

enum class SkipReason { UNSUPPORTED, NO_CHANGE, NO_GAIN, APK_GUARD, MEMORY_LIMIT, VERIFICATION_FAILED }

data class ProgressSnapshot(
    val phase: String,
    val currentRelativePath: String? = null,
    val filesDiscovered: Int = 0,
    val filesProcessed: Int = 0,
    val candidates: Int = 0,
    val optimized: Int = 0,
    val skipsByReason: Map<SkipReason, Int> = emptyMap(),
    val errors: Int = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    val savedBytes: Long = 0,
    val potentialSavingsBytes: Long = 0,
    val totalWork: Int? = null
)

class FileHeader(name: String, bytes: ByteArray) {
    val name: String = name
    private val headerBytes = bytes.copyOf()

    val bytes: ByteArray get() = headerBytes.copyOf()
}

data class OptimizationReport(
    var scanned: Int = 0,
    var optimized: Int = 0,
    var skipped: Int = 0,
    var errors: Int = 0,
    var savedBytes: Long = 0,
    var candidates: Int = 0,
    var potentialSavingsBytes: Long = 0,
    var bytesRead: Long = 0,
    var bytesWritten: Long = 0,
    var status: RunStatus = RunStatus.RUNNING,
    var skipsByReason: Map<SkipReason, Int> = emptyMap()
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
