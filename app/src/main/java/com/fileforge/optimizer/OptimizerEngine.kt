package com.fileforge.optimizer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OptimizerEngine(
    private val context: Context,
    private val root: DocumentFile,
    private val settings: OptimizerSettings,
    private val logger: (String) -> Unit
) {
    private val resolver = context.contentResolver
    private val maxBytes = 300L * 1024L * 1024L
    private val report = OptimizationReport()
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val undoLines = StringBuilder()
    private lateinit var backupRoot: DocumentFile

    fun run(): OptimizationReport {
        backupRoot = root.createDirectory("FileForge_Backups_$stamp")
            ?: throw IllegalStateException("Could not create backup directory in selected folder.")
        undoLines.append("FileForge Undo Log $stamp\n")
        undoLines.append("Mode=$settings\n")
        undoLines.append("Format: relative_path | original_bytes | optimized_bytes | backup_path | note\n\n")

        walk(root, "")
        writeUndoLog()
        return report
    }

    private fun walk(dir: DocumentFile, relativeDir: String) {
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            report.errors++
            logger("ERROR listing $relativeDir: ${t.message}")
            return
        }

        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (name.startsWith("FileForge_Backups_") || name.startsWith("FileForge_Undo_")) continue
                val childRel = if (relativeDir.isBlank()) name else "$relativeDir/$name"
                walk(child, childRel)
            } else if (child.isFile) {
                val rel = if (relativeDir.isBlank()) name else "$relativeDir/$name"
                processFile(child, rel)
            }
        }
    }

    private fun processFile(file: DocumentFile, relativePath: String) {
        report.scanned++
        val name = file.name ?: relativePath.substringAfterLast('/')

        val length = file.length()
        if (length > maxBytes) {
            report.skipped++
            logger("SKIP too large: $relativePath (${length} bytes)")
            return
        }

        try {
            val original = readFile(file, maxBytes)
            val kind = FileTypeDetector.detect(name, original)
            if (kind == FileKind.UNSUPPORTED) {
                report.skipped++
                return
            }
            if (kind == FileKind.APK && !settings.apkLabMode) {
                report.skipped++
                logger("SKIP APK safe guard: $relativePath")
                return
            }

            val result = Optimizers.optimize(name, kind, original, settings)
            if (result == null) {
                report.skipped++
                logger("NO CHANGE candidate skipped: $relativePath [$kind]")
                return
            }

            if (result.bytes.size >= original.size) {
                report.skipped++
                logger("NO GAIN: $relativePath [$kind] old=${original.size}, new=${result.bytes.size}")
                return
            }

            val newKind = FileTypeDetector.detect(name, result.bytes)
            if (newKind != kind) {
                report.errors++
                logger("ERROR type changed, not replacing: $relativePath [$kind -> $newKind]")
                return
            }

            if (!Optimizers.verify(kind, result.bytes, settings)) {
                report.errors++
                logger("ERROR verification failed, not replacing: $relativePath [$kind]")
                return
            }

            val backupPath = backupOriginal(relativePath, original)
            try {
                writeFile(file, result.bytes)
            } catch (t: Throwable) {
                report.errors++
                logger("ERROR writing optimized file, attempting restore: $relativePath: ${t.message}")
                try { writeFile(file, original) } catch (_: Throwable) {}
                return
            }

            val saved = original.size - result.bytes.size
            report.optimized++
            report.savedBytes += saved.toLong()
            undoLines.append(relativePath)
                .append(" | ").append(original.size)
                .append(" | ").append(result.bytes.size)
                .append(" | ").append(backupPath)
                .append(" | ").append(result.note.replace('\n', ' '))
                .append('\n')
            logger("OPTIMIZED: $relativePath saved=$saved bytes. ${result.note}")
        } catch (t: Throwable) {
            report.errors++
            logger("ERROR processing $relativePath: ${t.message ?: t.javaClass.name}")
        }
    }

    private fun readFile(file: DocumentFile, maxAllowed: Long): ByteArray {
        resolver.openInputStream(file.uri).use { input ->
            if (input == null) throw IllegalStateException("Cannot open input stream")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read.toLong()
                if (total > maxAllowed) throw IllegalStateException("File exceeded max read guard: $maxAllowed bytes")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun writeFile(file: DocumentFile, bytes: ByteArray) {
        resolver.openOutputStream(file.uri, "wt").use { output ->
            if (output == null) throw IllegalStateException("Cannot open output stream")
            output.write(bytes)
            output.flush()
        }
    }

    private fun backupOriginal(relativePath: String, bytes: ByteArray): String {
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) throw IllegalStateException("Invalid relative path")
        var current = backupRoot
        for (dirName in parts.dropLast(1)) {
            current = findOrCreateDirectory(current, dirName)
        }
        val fileName = parts.last()
        val backupFile = current.createFile("application/octet-stream", fileName)
            ?: throw IllegalStateException("Could not create backup file for $relativePath")
        resolver.openOutputStream(backupFile.uri, "wt").use { output ->
            if (output == null) throw IllegalStateException("Cannot write backup")
            output.write(bytes)
            output.flush()
        }
        return "FileForge_Backups_$stamp/$relativePath"
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
            ?: throw IllegalStateException("Could not create backup subdirectory: $name")
    }

    private fun writeUndoLog() {
        val undoFile = root.createFile("text/plain", "FileForge_Undo_$stamp.txt")
        if (undoFile == null) {
            logger("WARN could not create undo log in selected folder.")
            return
        }
        resolver.openOutputStream(undoFile.uri, "wt").use { output ->
            if (output == null) {
                logger("WARN could not write undo log.")
                return
            }
            output.write(undoLines.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}
