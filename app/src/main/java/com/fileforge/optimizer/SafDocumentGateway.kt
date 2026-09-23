package com.fileforge.optimizer

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

class SafDocumentGateway(
    private val context: Context,
    root: DocumentFile
) : DocumentGateway {
    private val documents = linkedMapOf<String, DocumentFile>()

    val rootNode: DocumentNode = remember(root)

    override fun openRead(node: DocumentNode): InputStream =
        context.contentResolver.openInputStream(document(node).uri)
            ?: throw IllegalStateException("Cannot open ${node.name} for reading")

    override fun openWrite(node: DocumentNode): OutputStream =
        context.contentResolver.openOutputStream(document(node).uri, "wt")
            ?: throw IllegalStateException("Cannot open ${node.name} for writing")

    override fun list(node: DocumentNode): List<DocumentNode> =
        document(node).listFiles().map(::remember)

    override fun listBounded(node: DocumentNode, maxChildren: Int): List<DocumentNode> {
        require(maxChildren >= 0) { "Child limit must be nonnegative" }
        val parent = document(node)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent.uri,
            DocumentsContract.getDocumentId(parent.uri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val children = ArrayList<DocumentNode>(minOf(maxChildren, 256))
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IllegalStateException("Cannot list ${node.name}")
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (it.moveToNext()) {
                if (children.size == maxChildren) {
                    throw RunInvariantException("Selected directory exceeded the child-count limit")
                }
                val documentId = it.getString(idIndex)
                    ?: throw IllegalStateException("Provider returned a child without an identity")
                val name = it.getString(nameIndex) ?: documentId
                val mimeType = it.getString(typeIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(parent.uri, documentId)
                val child = DocumentFile.fromSingleUri(context, childUri)
                    ?: throw IllegalStateException("Provider returned an invalid child identity")
                val length = if (it.isNull(sizeIndex)) 0L else it.getLong(sizeIndex).coerceAtLeast(0L)
                children += remember(
                    child,
                    name = name,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    length = length
                )
            }
        }
        return children
    }

    override fun resolve(parent: DocumentNode, name: String): DocumentNode? =
        document(parent).findFile(name)?.let(::remember)

    override fun createDirectory(parent: DocumentNode, name: String): DocumentNode =
        remember(
            document(parent).createDirectory(name)
                ?: throw IllegalStateException("Cannot create directory $name")
        )

    override fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode =
        remember(
            document(parent).createFile(mimeType, name)
                ?: throw IllegalStateException("Cannot create file $name")
        )

    override fun delete(node: DocumentNode): Boolean {
        val deleted = document(node).delete()
        if (deleted) documents.remove(node.id)
        return deleted
    }

    override fun createDirectoryExact(parent: DocumentNode, name: String): DocumentNode {
        if (resolve(parent, name) != null) throw DocumentAlreadyExistsException("Document already exists: $name")
        return requireExact(parent, name, directory = true, createDirectory(parent, name))
    }

    override fun createFileExact(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
        if (resolve(parent, name) != null) throw DocumentAlreadyExistsException("Document already exists: $name")
        return requireExact(parent, name, directory = false, createFile(parent, mimeType, name))
    }

    override fun length(node: DocumentNode): Long = document(node).length()

    private fun requireExact(parent: DocumentNode, name: String, directory: Boolean, created: DocumentNode): DocumentNode {
        return requireExactCreation(
            requestedName = name,
            directory = directory,
            created = created,
            resolveCreated = { resolve(parent, name) },
            deleteCreated = {
                val deleted = document(created).delete()
                if (deleted) documents.remove(created.id)
                deleted
            }
        )
    }

    private fun document(node: DocumentNode): DocumentFile =
        documents[node.id] ?: throw IllegalArgumentException("Unknown document node: ${node.id}")

    private fun remember(document: DocumentFile): DocumentNode {
        return remember(
            document,
            name = document.name ?: document.uri.toString(),
            isDirectory = document.isDirectory,
            length = document.length()
        )
    }

    private fun remember(document: DocumentFile, name: String, isDirectory: Boolean, length: Long): DocumentNode {
        val id = document.uri.toString()
        documents[id] = document
        return DocumentNode(
            id = id,
            name = name,
            isDirectory = isDirectory,
            length = length
        )
    }
}

internal fun requireExactCreation(
    requestedName: String,
    directory: Boolean,
    created: DocumentNode,
    resolveCreated: () -> DocumentNode?,
    deleteCreated: () -> Boolean
): DocumentNode = try {
    if (created.name != requestedName) {
        throw DocumentAlreadyExistsException(
            "Provider collision-renamed $requestedName to ${created.name}"
        )
    }
    check(created.isDirectory == directory) {
        "Provider returned the wrong document type for $requestedName"
    }
    val resolved = resolveCreated()
    check(resolved?.id == created.id && resolved.isDirectory == directory) {
        "Provider-created document is not reachable at its exact name: $requestedName"
    }
    created
} catch (failure: Throwable) {
    try {
        check(deleteCreated()) {
            "Cannot delete provider-created document after exact-creation validation failed: ${created.name}"
        }
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
}
