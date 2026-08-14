package com.fileforge.optimizer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class DocumentNode(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val length: Long
)

class DocumentAlreadyExistsException(message: String) : IOException(message)

interface DocumentGateway {
    fun openRead(node: DocumentNode): InputStream
    fun openWrite(node: DocumentNode): OutputStream
    fun list(node: DocumentNode): List<DocumentNode>
    fun resolve(parent: DocumentNode, name: String): DocumentNode?
    fun createDirectory(parent: DocumentNode, name: String): DocumentNode
    fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode
    fun createDirectoryExact(parent: DocumentNode, name: String): DocumentNode {
        if (resolve(parent, name) != null) throw DocumentAlreadyExistsException("Document already exists: $name")
        val created = createDirectory(parent, name)
        if (created.name != name) {
            throw DocumentAlreadyExistsException("Provider collision-renamed $name to ${created.name}")
        }
        check(created.isDirectory) { "Provider returned the wrong document type for $name" }
        val resolved = resolve(parent, name)
        check(resolved?.id == created.id && resolved.isDirectory) {
            "Created document is not reachable by its exact requested name: $name"
        }
        return created
    }
    fun createFileExact(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
        if (resolve(parent, name) != null) throw DocumentAlreadyExistsException("Document already exists: $name")
        val created = createFile(parent, mimeType, name)
        if (created.name != name) {
            throw DocumentAlreadyExistsException("Provider collision-renamed $name to ${created.name}")
        }
        check(!created.isDirectory) { "Provider returned the wrong document type for $name" }
        val resolved = resolve(parent, name)
        check(resolved?.id == created.id && !resolved.isDirectory) {
            "Created document is not reachable by its exact requested name: $name"
        }
        return created
    }
    fun length(node: DocumentNode): Long
}
