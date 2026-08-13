package com.fileforge.optimizer

import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

enum class UndoVerificationLevel { SHA_256, LEGACY_SIZE_ONLY }

private fun String?.isValidSha256(): Boolean =
    this != null && length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

data class UndoHeader(
    val runId: String,
    val startedAt: String,
    val schemaVersion: Int = 2
)

data class UndoEntry(
    val relativePath: String,
    val originalBytes: Long,
    val optimizedBytes: Long,
    val backupPath: String,
    val originalSha256: String?,
    val note: String,
    val verificationLevel: UndoVerificationLevel = UndoVerificationLevel.SHA_256
) {
    init {
        require(originalBytes >= 0) { "originalBytes must be nonnegative" }
        require(optimizedBytes >= 0) { "optimizedBytes must be nonnegative" }
        if (verificationLevel == UndoVerificationLevel.SHA_256) {
            require(originalSha256.isValidSha256()) { "SHA-256 undo entries require a 64-character hexadecimal digest" }
        }
    }
}

data class UndoTerminalSummary(
    val status: RunStatus,
    val completedAt: String,
    val entriesCommitted: Int
) {
    init {
        require(status != RunStatus.RUNNING) { "Terminal status cannot be RUNNING" }
        require(entriesCommitted >= 0) { "entriesCommitted must be nonnegative" }
    }
}

data class UndoRun(
    val header: UndoHeader,
    val entries: List<UndoEntry>,
    val status: RunStatus,
    val terminal: UndoTerminalSummary? = null
)

class UndoLogRepository {
    fun start(writer: Writer, header: UndoHeader) {
        require(header.schemaVersion == V2_SCHEMA_VERSION) { "Only undo log schema v2 can be written" }
        writeLine(writer, jsonObject(
            "schemaVersion" to header.schemaVersion,
            "recordType" to "header",
            "runId" to header.runId,
            "startedAt" to header.startedAt,
            "status" to RunStatus.RUNNING.name
        ))
    }

    fun appendEntry(writer: Writer, entry: UndoEntry) {
        require(entry.verificationLevel == UndoVerificationLevel.SHA_256) { "v2 entries require SHA-256 verification" }
        writeLine(writer, jsonObject(
            "schemaVersion" to V2_SCHEMA_VERSION,
            "recordType" to "entry",
            "relativePath" to entry.relativePath,
            "originalBytes" to entry.originalBytes,
            "optimizedBytes" to entry.optimizedBytes,
            "backupPath" to entry.backupPath,
            "originalSha256" to entry.originalSha256!!,
            "verificationLevel" to entry.verificationLevel.name,
            "note" to entry.note
        ))
    }

    fun appendTerminal(writer: Writer, terminal: UndoTerminalSummary) {
        writeLine(writer, jsonObject(
            "schemaVersion" to V2_SCHEMA_VERSION,
            "recordType" to "terminal",
            "status" to terminal.status.name,
            "completedAt" to terminal.completedAt,
            "entriesCommitted" to terminal.entriesCommitted
        ))
    }

    fun read(reader: Reader): UndoRun {
        val lines = BufferedReader(reader).readLines()
        val firstContent = lines.firstOrNull { it.isNotBlank() } ?: return emptyLegacyRun()
        val v2Header = parseV2Header(firstContent)
        return if (v2Header != null) readV2(lines, v2Header) else readLegacy(lines)
    }

    private fun readV2(lines: List<String>, header: UndoHeader): UndoRun {
        val entries = mutableListOf<UndoEntry>()
        var terminal: UndoTerminalSummary? = null
        lines.dropWhile { it.isBlank() }.drop(1).forEach { line ->
            val fields = parseJsonObject(line) ?: return@forEach
            if (fields.long("schemaVersion") != V2_SCHEMA_VERSION.toLong()) return@forEach
            when (fields.string("recordType")) {
                "entry" -> parseV2Entry(fields)?.let(entries::add)
                "terminal" -> if (terminal == null) parseTerminal(fields)?.let { terminal = it }
            }
        }
        val validTerminal = terminal?.takeIf { it.entriesCommitted == entries.size }
        return UndoRun(header, entries, validTerminal?.status ?: RunStatus.RUNNING, validTerminal)
    }

    private fun readLegacy(lines: List<String>): UndoRun {
        val contentLines = lines.dropWhile { it.isBlank() }
        val headerLine = contentLines.firstOrNull()?.trim().orEmpty()
        val stamp = LEGACY_HEADER.matchEntire(headerLine)?.groupValues?.get(1)
        if (stamp == null || contentLines.getOrNull(1)?.startsWith("Mode=") != true ||
            contentLines.getOrNull(2)?.trim() != LEGACY_FORMAT
        ) return emptyLegacyRun()
        val entries = contentLines.drop(3)
            .mapNotNull(::parseLegacyEntry)
        return UndoRun(
            header = UndoHeader(runId = stamp, startedAt = "", schemaVersion = LEGACY_SCHEMA_VERSION),
            entries = entries,
            status = RunStatus.COMPLETED
        )
    }

    private fun parseV2Header(line: String): UndoHeader? {
        val fields = parseJsonObject(line) ?: return null
        if (fields.long("schemaVersion") != V2_SCHEMA_VERSION.toLong() ||
            fields.string("recordType") != "header" ||
            fields.string("status") != RunStatus.RUNNING.name
        ) return null
        val runId = fields.string("runId") ?: return null
        val startedAt = fields.string("startedAt") ?: return null
        return UndoHeader(runId, startedAt)
    }

    private fun parseV2Entry(fields: Map<String, JsonField>): UndoEntry? = safely {
        if (fields.string("verificationLevel") != UndoVerificationLevel.SHA_256.name) return@safely null
        UndoEntry(
            relativePath = fields.string("relativePath") ?: return@safely null,
            originalBytes = fields.long("originalBytes") ?: return@safely null,
            optimizedBytes = fields.long("optimizedBytes") ?: return@safely null,
            backupPath = fields.string("backupPath") ?: return@safely null,
            originalSha256 = fields.string("originalSha256") ?: return@safely null,
            note = fields.string("note") ?: return@safely null
        )
    }

    private fun parseTerminal(fields: Map<String, JsonField>): UndoTerminalSummary? = safely {
        val status = fields.string("status")?.let { value -> RunStatus.entries.firstOrNull { it.name == value } }
            ?: return@safely null
        UndoTerminalSummary(
            status = status,
            completedAt = fields.string("completedAt") ?: return@safely null,
            entriesCommitted = fields.long("entriesCommitted")?.takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: return@safely null
        )
    }

    private fun parseLegacyEntry(line: String): UndoEntry? = safely {
        val parts = line.split(LEGACY_SEPARATOR, limit = 5)
        if (parts.size != 5) return@safely null
        UndoEntry(
            relativePath = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@safely null,
            originalBytes = parts[1].trim().toLongOrNull() ?: return@safely null,
            optimizedBytes = parts[2].trim().toLongOrNull() ?: return@safely null,
            backupPath = parts[3].trim().takeIf { it.isNotEmpty() } ?: return@safely null,
            originalSha256 = null,
            note = parts[4].trim(),
            verificationLevel = UndoVerificationLevel.LEGACY_SIZE_ONLY
        )
    }

    private fun emptyLegacyRun() = UndoRun(
        header = UndoHeader(runId = "", startedAt = "", schemaVersion = LEGACY_SCHEMA_VERSION),
        entries = emptyList(),
        status = RunStatus.RUNNING
    )

    private fun writeLine(writer: Writer, line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private fun jsonObject(vararg fields: Pair<String, Any>): String = fields.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ","
    ) { (name, value) -> "\"${escapeJson(name)}\":${if (value is String) "\"${escapeJson(value)}\"" else value}" }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character in '\uD800'..'\uDFFF') {
                    append("\\u%04x".format(character.code))
                } else append(character)
            }
        }
    }

    private fun parseJsonObject(line: String): Map<String, JsonField>? = JsonObjectParser(line).parse()

    private fun Map<String, JsonField>.string(name: String): String? = this[name]?.takeIf { it.quoted }?.value
    private fun Map<String, JsonField>.long(name: String): Long? = this[name]?.takeIf { !it.quoted }?.value?.toLongOrNull()

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class JsonField(val value: String, val quoted: Boolean)

    private class JsonObjectParser(private val text: String) {
        private var index = 0

        fun parse(): Map<String, JsonField>? = try {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val fields = linkedMapOf<String, JsonField>()
            if (consume('}')) return fields
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = if (peek() == '"') JsonField(string(), quoted = true) else JsonField(number(), quoted = false)
                if (fields.put(key, value) != null) return null
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            skipWhitespace()
            if (index != text.length) null else fields
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun string(): String {
            expect('"')
            return buildString {
                while (true) {
                    val character = next()
                    when (character) {
                        '"' -> return@buildString
                        '\\' -> append(escapedCharacter())
                        else -> {
                            require(character.code >= 0x20) { "Unescaped control character" }
                            append(character)
                        }
                    }
                }
            }
        }

        private fun escapedCharacter(): Char = when (val escape = next()) {
            '"', '\\', '/' -> escape
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicodeCharacter()
            else -> throw IllegalArgumentException("Invalid JSON escape")
        }

        private fun unicodeCharacter(): Char {
            val digits = (1..4).map { next() }.joinToString("")
            return digits.toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("Invalid unicode escape")
        }

        private fun number(): String {
            val start = index
            if (peek() == '-') index++
            if (peek() == '0') {
                index++
                require(peek()?.isDigit() != true) { "Leading zero in JSON number" }
            } else {
                val digitsStart = index
                while (peek()?.isDigit() == true) index++
                require(index > digitsStart) { "Expected JSON number" }
            }
            return text.substring(start, index)
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) index++
        }

        private fun consume(character: Char): Boolean = if (peek() == character) {
            index++
            true
        } else false

        private fun expect(character: Char) {
            require(consume(character)) { "Expected $character" }
        }

        private fun next(): Char = peek()?.also { index++ } ?: throw IllegalArgumentException("Unexpected end of JSON")
        private fun peek(): Char? = text.getOrNull(index)
    }

    private companion object {
        const val V2_SCHEMA_VERSION = 2
        const val LEGACY_SCHEMA_VERSION = 1
        const val LEGACY_FORMAT = "Format: relative_path | original_bytes | optimized_bytes | backup_path | note"
        const val LEGACY_SEPARATOR = " | "
        val LEGACY_HEADER = Regex("FileForge Undo Log (.+)")
    }
}
