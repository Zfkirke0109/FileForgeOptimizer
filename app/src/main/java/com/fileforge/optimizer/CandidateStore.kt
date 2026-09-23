package com.fileforge.optimizer

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class CandidateCleanupException(message: String, cause: Throwable? = null) : IOException(message, cause)
class CandidateSizeLimitExceededException(val maxBytes: Long) :
    IOException("Candidate exceeded the $maxBytes-byte cache storage limit")

class CandidateStore private constructor(
    cacheDirectory: File,
    private val scopedRunId: String?,
    private val fileSystem: CandidateFileSystem,
    private val maxCandidateBytes: Long,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Boolean
) {
    private val root = File(cacheDirectory, ROOT_DIRECTORY)

    init {
        scopedRunId?.let { require(it.isSafePathSegment()) { "Invalid candidate run id" } }
        require(maxCandidateBytes > 0) { "Candidate size limit must be positive" }
    }

    constructor(cacheDirectory: File) :
        this(cacheDirectory, null, JvmCandidateFileSystem, DEFAULT_MAX_CANDIDATE_BYTES, true)

    constructor(cacheDirectory: File, runId: String) :
        this(cacheDirectory, runId, JvmCandidateFileSystem, DEFAULT_MAX_CANDIDATE_BYTES, true)

    internal constructor(cacheDirectory: File, runId: String, maxCandidateBytes: Long) :
        this(cacheDirectory, runId, JvmCandidateFileSystem, maxCandidateBytes, true)

    internal constructor(
        cacheDirectory: File,
        runId: String,
        fileSystem: CandidateFileSystem
    ) : this(cacheDirectory, runId, fileSystem, DEFAULT_MAX_CANDIDATE_BYTES, true)

    fun create(runId: String, suffix: String): CandidateFile {
        require(runId.isSafePathSegment()) { "Invalid candidate run id" }
        val ownedRunId = scopedRunId
        if (ownedRunId != null) {
            require(runId == ownedRunId) { "Scoped store cannot create candidates for another run" }
        }
        return createInRun(File(root, runId), suffix)
    }

    fun create(suffix: String): CandidateFile {
        val runId = checkNotNull(scopedRunId) {
            "An unscoped CandidateStore requires create(runId, suffix)."
        }
        return createInRun(File(root, runId), suffix)
    }

    private fun createInRun(runDirectory: File, suffix: String): CandidateFile {
        require(suffix.isSafeCandidateSuffix()) { "Invalid candidate suffix" }
        fileSystem.createDirectories(runDirectory)
        val file = try {
            fileSystem.createTempFile(runDirectory, suffix)
        } catch (failure: Exception) {
            cleanDirectoriesAfterCreateFailure(runDirectory, failure)
            throw failure
        }
        val usableSpace = fileSystem.usableSpace(runDirectory)
        val spaceBound = if (usableSpace == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (usableSpace - MIN_FREE_SPACE_BYTES).coerceAtLeast(0)
        }
        return CandidateFile(file, minOf(maxCandidateBytes, spaceBound)) { release(runDirectory, file) }
    }

    private fun release(runDirectory: File, file: File) {
        requireDeleted(file, "candidate file")
        deleteIfEmpty(runDirectory, "candidate run directory")
        deleteIfEmpty(root, "candidate root directory")
    }

    private fun cleanDirectoriesAfterCreateFailure(runDirectory: File, createFailure: Exception) {
        try {
            deleteIfEmpty(runDirectory, "candidate run directory")
            deleteIfEmpty(root, "candidate root directory")
        } catch (cleanupFailure: CandidateCleanupException) {
            createFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun deleteIfEmpty(directory: File, label: String) {
        if (!fileSystem.exists(directory)) return
        val children = fileSystem.list(directory)
            ?: throw CandidateCleanupException("Cannot list $label for cleanup: ${directory.path}")
        if (children.isEmpty()) requireDeleted(directory, label)
    }

    private fun requireDeleted(file: File, label: String) {
        if (!fileSystem.delete(file)) {
            throw CandidateCleanupException("Cannot delete $label: ${file.path}")
        }
    }

    private fun String.isSafePathSegment(): Boolean =
        isNotBlank() && this != "." && this != ".." && indexOf('/') < 0 && indexOf('\\') < 0

    private fun String.isSafeCandidateSuffix(): Boolean =
        isNotEmpty() && indexOf('/') < 0 && indexOf('\\') < 0

    companion object {
        fun cleanupStale(cacheDirectory: File) = cleanupStale(cacheDirectory, JvmCandidateFileSystem)

        internal fun cleanupStale(cacheDirectory: File, fileSystem: CandidateFileSystem) {
            val root = File(cacheDirectory, ROOT_DIRECTORY)
            if (fileSystem.exists(root) && !fileSystem.deleteRecursively(root)) {
                throw CandidateCleanupException("Cannot remove stale candidate cache: ${root.path}")
            }
        }

        private const val ROOT_DIRECTORY = "fileforge"
        const val DEFAULT_MAX_CANDIDATE_BYTES = 0xffff_ffffL - 1L
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
    }
}

class CandidateFile internal constructor(
    val file: File,
    private val maxBytes: Long,
    private val release: () -> Unit
) : Closeable {
    private var closed = false

    val length: Long
        get() {
            requireOpen()
            return file.length()
        }

    fun openInputStream(): InputStream {
        requireOpen()
        return FileInputStream(file)
    }

    fun openOutputStream(): OutputStream {
        requireOpen()
        return SizeLimitedOutputStream(FileOutputStream(file), maxBytes)
    }

    internal fun validateExternalWrite() {
        requireOpen()
        if (file.length() > maxBytes) throw CandidateSizeLimitExceededException(maxBytes)
    }

    internal fun guardExternalWrite(delegate: CancellationToken): CancellationToken = CancellationToken {
        delegate.throwIfCancelled()
        validateExternalWrite()
    }

    override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }

    private fun requireOpen() {
        check(!closed) { "Candidate file is closed" }
    }

    private class SizeLimitedOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            requireCapacity(1)
            delegate.write(value)
            written += 1
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            delegate.write(source, offset, length)
            written += length.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()

        private fun requireCapacity(additionalBytes: Int) {
            if (additionalBytes < 0 || written > maxBytes - additionalBytes.toLong()) {
                throw CandidateSizeLimitExceededException(maxBytes)
            }
        }
    }
}

internal interface CandidateFileSystem {
    fun exists(file: File): Boolean
    @Throws(IOException::class)
    fun createDirectories(directory: File)
    @Throws(IOException::class)
    fun createTempFile(directory: File, suffix: String): File
    fun delete(file: File): Boolean
    fun deleteRecursively(directory: File): Boolean
    fun list(directory: File): Array<String>?
    fun usableSpace(directory: File): Long = Long.MAX_VALUE
}

private object JvmCandidateFileSystem : CandidateFileSystem {
    override fun exists(file: File): Boolean = file.exists()

    override fun createDirectories(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create candidate directory: ${directory.path}")
        }
    }

    override fun createTempFile(directory: File, suffix: String): File =
        File.createTempFile("candidate-", suffix, directory)

    override fun delete(file: File): Boolean = !file.exists() || file.delete()

    override fun deleteRecursively(directory: File): Boolean =
        !directory.exists() || directory.deleteRecursively()

    override fun list(directory: File): Array<String>? = directory.list()
    override fun usableSpace(directory: File): Long = directory.usableSpace
}
