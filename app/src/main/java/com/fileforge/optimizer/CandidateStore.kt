package com.fileforge.optimizer

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class CandidateStore(private val cacheDirectory: File) {
    private val root = File(cacheDirectory, ROOT_DIRECTORY)

    init {
        root.deleteRecursively()
    }

    fun create(runId: String, suffix: String): CandidateFile {
        require(runId.isSafePathSegment()) { "Invalid candidate run id" }
        require(suffix.isNotEmpty() && suffix.indexOf('/') < 0 && suffix.indexOf('\\') < 0) {
            "Invalid candidate suffix"
        }

        val runDirectory = File(root, runId)
        if (!runDirectory.exists() && !runDirectory.mkdirs()) {
            throw IllegalStateException("Cannot create candidate directory")
        }
        val file = File.createTempFile("candidate-", suffix, runDirectory)
        return CandidateFile(file) { release(runDirectory, file) }
    }

    private fun release(runDirectory: File, file: File) {
        file.delete()
        if (runDirectory.list().isNullOrEmpty()) runDirectory.delete()
        if (root.list().isNullOrEmpty()) root.delete()
    }

    private fun String.isSafePathSegment(): Boolean =
        isNotBlank() && this != "." && this != ".." && indexOf('/') < 0 && indexOf('\\') < 0

    private companion object {
        const val ROOT_DIRECTORY = "fileforge"
    }
}

class CandidateFile internal constructor(
    val file: File,
    private val release: () -> Unit
) : Closeable {
    private var closed = false

    val length: Long get() = file.length()

    fun openInputStream(): InputStream = FileInputStream(file)

    fun openOutputStream(): OutputStream = FileOutputStream(file)

    override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}
