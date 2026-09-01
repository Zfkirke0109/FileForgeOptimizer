package com.fileforge.optimizer

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal data class StreamIntegrity(val bytes: Long, val sha256: String)

/** Counts and hashes the raw source bytes observed by a streaming processor. */
internal class IntegrityTrackingInputStream(input: InputStream) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytes = 0L
    private var finished: StreamIntegrity? = null

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(target, offset, length)
        if (read > 0) record(target, offset, read)
        return read
    }

    fun finish(): StreamIntegrity = finished ?: StreamIntegrity(bytes, digest.digest().toHex()).also { finished = it }

    private fun record(source: ByteArray, offset: Int, length: Int) {
        check(finished == null) { "Source integrity was already finalized" }
        bytes = StreamIntegrityChecker.checkedAdd(bytes, length)
        digest.update(source, offset, length)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Shared bounded streaming primitives for SAF reads and writes. */
internal object StreamIntegrityChecker {
    const val BUFFER_BYTES = 32 * 1024

    fun copyAndHash(
        input: InputStream,
        output: OutputStream,
        cancellation: CancellationToken = NeverCancelled
    ): StreamIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            cancellation.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            cancellation.throwIfCancelled()
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            bytes = checkedAdd(bytes, read)
        }
        output.flush()
        return StreamIntegrity(bytes, digest.digest().toHex())
    }

    fun hash(input: InputStream, cancellation: CancellationToken = NeverCancelled): StreamIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            cancellation.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            cancellation.throwIfCancelled()
            digest.update(buffer, 0, read)
            bytes = checkedAdd(bytes, read)
        }
        return StreamIntegrity(bytes, digest.digest().toHex())
    }

    internal fun checkedAdd(bytes: Long, read: Int): Long {
        if (read < 0 || bytes > Long.MAX_VALUE - read) throw ArithmeticException("Stream byte count overflow")
        return bytes + read
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal object DocumentPathPolicy {
    fun requireSafeSegment(value: String): String {
        requireWellFormedUtf16(value)
        require(value.isNotEmpty() && value != "." && value != ".." && '/' !in value) {
            "Unsafe path segment"
        }
        return value
    }

    fun requireSafeRelative(path: String): List<String> {
        requireWellFormedUtf16(path)
        require(path.isNotEmpty() && !path.startsWith('/')) { "Path must be relative" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe relative path"
        }
        return segments
    }

    fun resolve(root: DocumentNode, path: String, gateway: DocumentGateway): DocumentNode? {
        var current = root
        requireSafeRelative(path).forEach { segment -> current = gateway.resolve(current, segment) ?: return null }
        return current
    }

    private fun requireWellFormedUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "Malformed UTF-16 path" }
                index += 2
            } else {
                require(!character.isLowSurrogate()) { "Malformed UTF-16 path" }
                index++
            }
        }
    }
}
