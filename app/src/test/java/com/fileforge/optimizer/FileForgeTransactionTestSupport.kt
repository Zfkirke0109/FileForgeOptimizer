package com.fileforge.optimizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.io.Writer
import java.security.MessageDigest

/** A deterministic SAF-shaped tree. It deliberately keeps test data in memory; production must stream. */
internal class RecordingDocumentGateway : DocumentGateway {
    val root = DocumentNode("root", "selected", isDirectory = true, length = 0)
    val events = mutableListOf<String>()
    var fail: ((String) -> Throwable?)? = null
    var corruptAfterWrite: ((DocumentNode) -> Boolean)? = null
    var maximumTransferRequest: Int? = null
    var afterEvent: ((String) -> Unit)? = null

    private val nodes = linkedMapOf(root.id to root)
    private val children = linkedMapOf(root.id to linkedMapOf<String, String>())
    private val bytes = linkedMapOf<String, ByteArray>()

    fun put(relativePath: String, contents: ByteArray): DocumentNode {
        val parts = relativePath.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." })
        var parent = root
        parts.dropLast(1).forEach { part ->
            parent = resolve(parent, part) ?: createDirectory(parent, part)
        }
        val file = resolve(parent, parts.last()) ?: createFile(parent, "application/octet-stream", parts.last())
        bytes[file.id] = contents.copyOf()
        return file
    }

    fun putDirectory(relativePath: String): DocumentNode {
        var parent = root
        relativePath.split('/').forEach { part ->
            val existing = resolve(parent, part)
            parent = when {
                existing == null -> createDirectory(parent, part)
                existing.isDirectory -> existing
                else -> DocumentNode(existing.id, existing.name, isDirectory = true, length = 0).also {
                    nodes[it.id] = it
                    children.getOrPut(it.id) { linkedMapOf() }
                    bytes.remove(it.id)
                }
            }
        }
        return parent
    }

    fun contents(relativePath: String): ByteArray {
        val node = node(relativePath) ?: error("No node at $relativePath")
        return bytes.getValue(node.id).copyOf()
    }

    fun replaceContents(relativePath: String, contents: ByteArray) {
        val node = node(relativePath) ?: error("No node at $relativePath")
        check(!node.isDirectory) { "Cannot replace directory contents" }
        bytes[node.id] = contents.copyOf()
    }

    fun node(relativePath: String): DocumentNode? {
        var current = root
        relativePath.split('/').forEach { name ->
            current = resolve(current, name) ?: return null
        }
        return current
    }

    override fun openRead(node: DocumentNode): InputStream {
        event("read:${node.id}")
        val source = ByteArrayInputStream(bytes[node.id] ?: throw IOException("Not a file: ${node.id}"))
        return object : InputStream() {
            override fun read(): Int = source.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                requireTransferAtMost(length)
                return source.read(target, offset, length)
            }
            override fun close() = source.close()
        }
    }

    override fun openWrite(node: DocumentNode): OutputStream {
        event("write:${node.id}")
        val output = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(source: ByteArray, offset: Int, length: Int) {
                requireTransferAtMost(length)
                event("write-bytes:${node.id}:$length")
                output.write(source, offset, length)
            }
            override fun close() {
                val written = output.toByteArray()
                bytes[node.id] = if (corruptAfterWrite?.invoke(node) == true) written + 0 else written
                event("write-closed:${node.id}")
            }
        }
    }

    override fun list(node: DocumentNode): List<DocumentNode> =
        children[node.id].orEmpty().values.map(nodes::getValue)

    override fun resolve(parent: DocumentNode, name: String): DocumentNode? =
        children[parent.id]?.get(name)?.let(nodes::get)

    override fun createDirectory(parent: DocumentNode, name: String): DocumentNode {
        event("mkdir:${parent.id}:$name")
        return create(parent, name, true)
    }

    override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
        event("create-file:${parent.id}:$name")
        return create(parent, name, false).also { bytes[it.id] = byteArrayOf() }
    }

    override fun length(node: DocumentNode): Long = bytes[node.id]?.size?.toLong() ?: node.length

    private fun create(parent: DocumentNode, name: String, directory: Boolean): DocumentNode {
        val id = "${parent.id}/$name"
        val created = DocumentNode(id, name, directory, 0)
        nodes[id] = created
        children.getOrPut(parent.id) { linkedMapOf() }[name] = id
        if (directory) children.getOrPut(id) { linkedMapOf() }
        return created
    }

    private fun event(value: String) {
        events += value
        fail?.invoke(value)?.let { throw it }
        afterEvent?.invoke(value)
    }

    private fun requireTransferAtMost(length: Int) {
        val maximum = maximumTransferRequest ?: return
        check(length <= maximum) { "Requested $length bytes; streaming transfer limit is $maximum" }
    }
}

internal class FixedCandidateProcessor(
    private val candidate: ByteArray,
    private val events: MutableList<String>,
    private val verificationFailure: Throwable? = null
) : ZipCandidateProcessor {
    override fun optimize(input: InputStream, output: OutputStream, mode: OptimizeMode, cancellation: CancellationToken): ZipOptimizationSummary {
        input.copyTo(OutputStream.nullOutputStream(), 32 * 1024)
        candidate.inputStream().copyTo(output, 32 * 1024)
        return ZipOptimizationSummary(1, candidate.size.toLong(), candidate.size.toLong(), "candidate")
    }

    override fun verify(input: InputStream, cancellation: CancellationToken): ZipVerification {
        events += "candidate-verified"
        verificationFailure?.let { throw it }
        input.copyTo(OutputStream.nullOutputStream(), 32 * 1024)
        return ZipVerification(1, candidate.size.toLong())
    }
}

internal class RecordingUndoEntrySink(private val events: MutableList<String>) : UndoEntrySink {
    val entries = mutableListOf<UndoEntry>()
    var failure: Throwable? = null
    override fun appendAndFlush(entry: UndoEntry) {
        events += "undo-entry-appended-and-flushed"
        failure?.let { throw it }
        entries += entry
    }
}

internal class RecordingReceiptWriter : ExclusiveRestoreReceiptWriter {
    val names = mutableListOf<String>()
    val contents = mutableListOf<String>()
    override fun openExclusive(name: String): RestoreReceipt {
        if (name in names) throw ReceiptAlreadyExistsException("Receipt already exists: $name")
        names += name
        return RestoreReceipt(name, object : StringWriter() {
            override fun close() {
                contents += toString()
            }
        })
    }
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it) }

internal fun List<String>.assertOrdered(vararg expected: String) {
    var index = 0
    expected.forEach { wanted ->
        index = indexOfFirstFrom(index) { it == wanted }
        check(index >= 0) { "Missing '$wanted' in $this" }
        index++
    }
}

internal inline fun <reified T> expectType(value: Any): T {
    check(value is T) { "Expected ${T::class.java.simpleName}, got ${value::class.java.simpleName}" }
    return value
}

private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
    for (index in start until size) if (predicate(this[index])) return index
    return -1
}
