package com.fileforge.optimizer

import java.io.InputStream
import java.io.OutputStream

data class DocumentNode(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val length: Long
)

interface DocumentGateway {
    fun openRead(node: DocumentNode): InputStream
    fun openWrite(node: DocumentNode): OutputStream
    fun list(node: DocumentNode): List<DocumentNode>
    fun resolve(parent: DocumentNode, name: String): DocumentNode?
    fun createDirectory(parent: DocumentNode, name: String): DocumentNode
    fun createFile(parent: DocumentNode, mimeType: String, name: String): DocumentNode
    fun length(node: DocumentNode): Long
}
