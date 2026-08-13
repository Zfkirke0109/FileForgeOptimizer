package com.fileforge.optimizer

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal data class StreamIntegrity(val bytes: Long, val sha256: String)

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
        require(value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' }) { "Unsafe path segment" }
        return value
    }

    fun requireSafeRelative(path: String): List<String> {
        requireWellFormedUtf16(path)
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) { "Path must be relative" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || it.contains('\\') || it.contains(':') }) {
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
