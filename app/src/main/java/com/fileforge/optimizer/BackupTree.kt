package com.fileforge.optimizer

import java.io.IOException

internal data class BackupArtifact(
    val file: DocumentNode,
    val createdDirectories: List<DocumentNode>
)

internal class BackupTree(private val documentGateway: DocumentGateway) {
    fun create(root: DocumentNode, backupPath: String): BackupArtifact {
        val segments = DocumentPathPolicy.requireSafeRelative(backupPath)
        val createdDirectories = mutableListOf<DocumentNode>()
        return try {
            var parent = root
            segments.dropLast(1).forEach { name ->
                val existing = documentGateway.resolve(parent, name)
                parent = when {
                    existing == null -> documentGateway.createDirectoryExact(parent, name).also(createdDirectories::add)
                    existing.isDirectory -> existing
                    else -> throw IOException("Backup path component is not a directory: $name")
                }
            }
            val name = segments.last()
            check(documentGateway.resolve(parent, name) == null) { "Backup already exists: $backupPath" }
            BackupArtifact(
                documentGateway.createFileExact(parent, "application/octet-stream", name),
                createdDirectories.toList()
            )
        } catch (failure: Throwable) {
            cleanupDirectories(createdDirectories, failure)
            throw failure
        }
    }

    fun cleanupBeforeOriginalMutation(artifact: BackupArtifact, primaryFailure: Throwable) {
        delete(artifact.file, primaryFailure)
        cleanupDirectories(artifact.createdDirectories, primaryFailure)
    }

    private fun cleanupDirectories(directories: List<DocumentNode>, primaryFailure: Throwable) {
        directories.asReversed().forEach { directory ->
            try {
                if (documentGateway.list(directory).isEmpty()) delete(directory, primaryFailure)
            } catch (cleanupFailure: Throwable) {
                attachCleanupFailure(primaryFailure, cleanupFailure)
            }
        }
    }

    private fun delete(node: DocumentNode, primaryFailure: Throwable) {
        try {
            check(documentGateway.delete(node)) { "Cannot delete abandoned backup document: ${node.name}" }
        } catch (cleanupFailure: Throwable) {
            attachCleanupFailure(primaryFailure, cleanupFailure)
        }
    }

    private fun attachCleanupFailure(primaryFailure: Throwable, cleanupFailure: Throwable) {
        if (cleanupFailure === primaryFailure) return
        if (cleanupFailure.isVmFatal()) {
            cleanupFailure.addSuppressed(primaryFailure)
            throw cleanupFailure
        }
        primaryFailure.addSuppressed(cleanupFailure)
    }
}
