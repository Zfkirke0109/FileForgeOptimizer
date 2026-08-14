package com.fileforge.optimizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.CountDownLatch

internal enum class UndoFault {
    WRITE,
    MID_LINE_WRITE,
    MID_LINE_ASSERTION,
    MID_LINE_CANCELLATION,
    FLUSH,
    FLUSH_AFTER_DELEGATE,
    FLUSH_AFTER_DELEGATE_ASSERTION,
    CLOSE_AFTER_FLUSH
}

internal open class FaultInjectingEngineGateway : DocumentGateway {
    val root = DocumentNode("root", "selected", isDirectory = true, length = 0)
    val mutations = mutableListOf<String>()
    val readPaths = mutableListOf<String>()
    var undoFault: UndoFault? = null
    var exactCreationFault: ExactCreationFault? = null
    var blockFirstList: CountDownLatch? = null
    var firstListEntered: CountDownLatch? = null
    var listFailure: Throwable? = null
    var readFailure: Throwable? = null
    var finalizeFailure: AssertionError? = null
    var failEmergencyRollbackForPath: String? = null
    var undoWritesAfterPoison = 0
    var undoFlushesAfterPoison = 0
    var undoCloses = 0

    protected val nodes = linkedMapOf(root.id to root)
    protected val children = linkedMapOf(root.id to linkedMapOf<String, String>())
    protected val bytes = linkedMapOf<String, ByteArray>()
    protected val advertisedLengths = mutableMapOf<String, Long>()
    private val writeAttempts = mutableMapOf<String, Int>()

    fun put(relativePath: String, contents: ByteArray, advertisedLength: Long = contents.size.toLong()): DocumentNode {
        val parts = relativePath.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." })
        var parent = root
        parts.dropLast(1).forEach { name -> parent = resolve(parent, name) ?: create(parent, name, true) }
        val node = resolve(parent, parts.last()) ?: create(parent, parts.last(), false)
        bytes[node.id] = contents.copyOf()
        advertisedLengths[node.id] = advertisedLength
        return node
    }

    fun contents(relativePath: String): ByteArray = bytes.getValue(node(relativePath).id).copyOf()

    fun node(relativePath: String): DocumentNode {
        var current = root
        relativePath.split('/').forEach { name -> current = resolve(current, name) ?: error("No node at $relativePath") }
        return current
    }

    fun relativePaths(): List<String> {
        val paths = mutableListOf<String>()
        val pending = ArrayDeque<Pair<DocumentNode, String>>()
        pending.addLast(root to "")
        while (pending.isNotEmpty()) {
            val (parent, prefix) = pending.removeFirst()
            list(parent).forEach { child ->
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                paths += path
                if (child.isDirectory) pending.addLast(child to path)
            }
        }
        return paths
    }

    override fun openRead(node: DocumentNode): InputStream {
        readPaths += path(node)
        readFailure?.let { throw it }
        return ByteArrayInputStream(bytes[node.id] ?: throw IOException("Not a file: ${node.id}"))
    }

    override fun openWrite(node: DocumentNode): OutputStream {
        val relativePath = path(node)
        mutations += "open-write:$relativePath"
        val writeAttempt = (writeAttempts[relativePath] ?: 0) + 1
        writeAttempts[relativePath] = writeAttempt
        val output = ByteArrayOutputStream()
        val undo = node.name.startsWith("FileForge_Undo_v2_")
        return object : OutputStream() {
            private var flushes = 0
            private var flushFailed = false
            private var poisoned = false
            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }
            override fun write(source: ByteArray, offset: Int, length: Int) {
                if (relativePath == failEmergencyRollbackForPath && writeAttempt >= 2) {
                    throw IOException("emergency rollback write failed")
                }
                if (undo && poisoned) undoWritesAfterPoison++
                if (undo && undoFault == UndoFault.WRITE && flushes >= 1) throw IOException("undo append write failed")
                if (undo && undoFault in setOf(
                        UndoFault.MID_LINE_WRITE,
                        UndoFault.MID_LINE_ASSERTION,
                        UndoFault.MID_LINE_CANCELLATION
                    ) && flushes >= 1
                ) {
                    val prefix = maxOf(1, length / 3)
                    output.write(source, offset, prefix)
                    persist(node, output)
                    poisoned = true
                    when (undoFault) {
                        UndoFault.MID_LINE_ASSERTION -> throw AssertionError("undo append assertion after a durable line prefix")
                        UndoFault.MID_LINE_CANCELLATION -> throw OptimizationCancelledException("undo append cancellation after a durable line prefix")
                        else -> throw IOException("undo append failed after a durable line prefix")
                    }
                }
                output.write(source, offset, length)
            }
            override fun flush() {
                if (undo && poisoned) undoFlushesAfterPoison++
                flushes++
                if (undo && undoFault == UndoFault.FLUSH && flushes >= 2) {
                    flushFailed = true
                    poisoned = true
                    throw IOException("undo append flush failed")
                }
                persist(node, output)
                if (undo && undoFault in setOf(
                        UndoFault.FLUSH_AFTER_DELEGATE,
                        UndoFault.FLUSH_AFTER_DELEGATE_ASSERTION
                    ) && flushes >= 2
                ) {
                    poisoned = true
                    if (undoFault == UndoFault.FLUSH_AFTER_DELEGATE_ASSERTION) {
                        throw AssertionError("undo append assertion after delegate flush")
                    }
                    throw IOException("undo append failed after delegate flush")
                }
            }
            override fun close() {
                if (undo) undoCloses++
                if (!flushFailed) persist(node, output)
                if (undo && undoFault == UndoFault.CLOSE_AFTER_FLUSH) throw IOException("undo close failed after flush")
                finalizeFailure?.let { throw it }
            }
        }
    }

    open override fun list(node: DocumentNode): List<DocumentNode> {
        listFailure?.let { throw it }
        if (node == root && blockFirstList != null) {
            firstListEntered?.countDown()
            blockFirstList?.await()
            blockFirstList = null
        }
        return children[node.id].orEmpty().values.map(nodes::getValue)
    }

    override fun resolve(parent: DocumentNode, name: String): DocumentNode? =
        children[parent.id]?.get(name)?.let(nodes::get)

    override fun createDirectory(parent: DocumentNode, name: String): DocumentNode {
        mutations += "mkdir:${path(parent)}:$name"
        return create(parent, name, true)
    }

    override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
        mutations += "create-file:${path(parent)}:$name"
        return create(parent, name, false).also {
            bytes[it.id] = byteArrayOf()
            advertisedLengths[it.id] = 0
        }
    }

    override fun createDirectoryExact(parent: DocumentNode, name: String): DocumentNode = exactCreate(parent, name, true)
    override fun createFileExact(parent: DocumentNode, mimeType: String, name: String): DocumentNode = exactCreate(parent, name, false)

    override fun length(node: DocumentNode): Long = advertisedLengths[node.id] ?: bytes[node.id]?.size?.toLong() ?: 0

    private fun exactCreate(parent: DocumentNode, name: String, directory: Boolean): DocumentNode = when (exactCreationFault) {
        ExactCreationFault.COLLISION_RENAME -> {
            if (directory) createDirectory(parent, "$name (1)") else createFile(parent, "application/octet-stream", "$name (1)")
            throw IOException("provider collision-renamed exact creation")
        }
        ExactCreationFault.CREATE_RACE -> {
            if (resolve(parent, name) == null) {
                if (directory) createDirectory(parent, name) else createFile(parent, "application/octet-stream", name)
            }
            throw IOException("exact creation lost a race")
        }
        ExactCreationFault.UNREACHABLE_RETURNED_NODE -> throw IOException("created node is unreachable by requested name")
        null -> if (directory) createDirectory(parent, name) else createFile(parent, "application/octet-stream", name)
    }

    protected fun create(parent: DocumentNode, name: String, directory: Boolean): DocumentNode {
        check(resolve(parent, name) == null) { "Duplicate child $name" }
        val id = "${parent.id}/$name"
        val created = DocumentNode(id, name, directory, 0)
        nodes[id] = created
        children.getOrPut(parent.id) { linkedMapOf() }[name] = id
        if (directory) children[id] = linkedMapOf()
        return created
    }

    private fun persist(node: DocumentNode, output: ByteArrayOutputStream) {
        bytes[node.id] = output.toByteArray()
        advertisedLengths[node.id] = output.size().toLong()
    }

    private fun path(node: DocumentNode): String = if (node == root) "" else node.id.removePrefix("${root.id}/")
}

internal class ScriptedUndoSinkWriter(
    private val delegate: Writer,
    private val failWriteAtCall: Int? = null,
    private val failFlushAtCall: Int? = null,
    private val failClose: Boolean = false
) : Writer() {
    private var writes = 0
    private var flushes = 0
    override fun write(source: CharArray, offset: Int, length: Int) {
        writes++
        if (writes == failWriteAtCall) throw IOException("scripted undo write failure")
        delegate.write(source, offset, length)
    }
    override fun flush() {
        flushes++
        delegate.flush()
        if (flushes == failFlushAtCall) throw IOException("scripted undo flush failure")
    }
    override fun close() {
        delegate.close()
        if (failClose) throw IOException("scripted close failure")
    }
}
