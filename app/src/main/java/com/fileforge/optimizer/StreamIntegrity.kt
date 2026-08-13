package com.fileforge.optimizer

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal data class StreamIntegrity(val bytes: Long, val sha256: String)

/** Shared bounded streaming primitives for SAF reads and writes. */
internal object StreamIntegrityChecker {
    const val BUFFER_BYTES = 32 * 1024

    fun copyAndHash(input: InputStream, output: OutputStream): StreamIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            bytes += read
        }
        output.flush()
        return StreamIntegrity(bytes, digest.digest().toHex())
    }

    fun hash(input: InputStream): StreamIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            bytes += read
        }
        return StreamIntegrity(bytes, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal object DocumentPathPolicy {
    fun requireSafeRelative(path: String): List<String> {
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
}
