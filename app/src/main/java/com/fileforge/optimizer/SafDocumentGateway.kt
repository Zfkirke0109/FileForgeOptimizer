package com.fileforge.optimizer

import android.content.Context
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
        if (created.name != name) {
            throw DocumentAlreadyExistsException("Provider collision-renamed $name to ${created.name}")
        }
        check(created.isDirectory == directory) { "Provider returned the wrong document type for $name" }
        val resolved = resolve(parent, name)
        check(resolved?.id == created.id && resolved.isDirectory == directory) {
            "Provider-created document is not reachable at its exact name: $name"
        }
        return created
    }

    private fun document(node: DocumentNode): DocumentFile =
        documents[node.id] ?: throw IllegalArgumentException("Unknown document node: ${node.id}")

    private fun remember(document: DocumentFile): DocumentNode {
        val id = document.uri.toString()
        documents[id] = document
        return DocumentNode(
            id = id,
            name = document.name ?: id,
            isDirectory = document.isDirectory,
            length = document.length()
        )
    }
}
