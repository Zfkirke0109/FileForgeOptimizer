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

    override fun length(node: DocumentNode): Long = document(node).length()

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
