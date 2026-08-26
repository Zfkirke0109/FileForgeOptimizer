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
    val schemaVersion: Int = 2,
    val mode: OptimizeMode = OptimizeMode.SAFE,
    val apkLabMode: Boolean = false,
    val textMinify: Boolean = false,
    val dryRun: Boolean = false,
    val appVersion: String = "unknown",
    val buildVariant: String = "unknown"
)

data class UndoEntry(
    val relativePath: String,
    val originalBytes: Long,
    val optimizedBytes: Long,
    val backupPath: String,
    val originalSha256: String?,
    val note: String,
    val verificationLevel: UndoVerificationLevel = UndoVerificationLevel.SHA_256,
    val optimizedSha256: String?,
    val fileKind: FileKind = FileKind.UNSUPPORTED,
    val toolId: String = "unknown",
    val completedAt: String = "unknown",
    val originalDocumentId: String? = null
) {
    val isRestoreEligible: Boolean
        get() = verificationLevel == UndoVerificationLevel.SHA_256 && originalDocumentId != null

    init {
        require(originalBytes >= 0) { "originalBytes must be nonnegative" }
        require(optimizedBytes >= 0) { "optimizedBytes must be nonnegative" }
        if (verificationLevel == UndoVerificationLevel.SHA_256) {
            require(originalSha256.isValidSha256() && optimizedSha256.isValidSha256()) {
                "SHA-256 undo entries require 64-character hexadecimal digests"
            }
        } else {
            require(originalSha256 == null && optimizedSha256 == null) { "Legacy entries cannot claim SHA-256 verification" }
        }
        require(originalDocumentId == null || originalDocumentId.isNotBlank()) {
            "Original document identity must be nonblank when present"
        }
    }
}

data class UndoTerminalSummary(
    val status: RunStatus,
    val completedAt: String,
    val entriesCommitted: Int,
    val scanned: Int = 0,
    val optimized: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val savedBytes: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    val potentialSavingsBytes: Long = 0
) {
    init {
        require(status != RunStatus.RUNNING) { "Terminal status cannot be RUNNING" }
        require(entriesCommitted >= 0 && scanned >= 0 && optimized >= 0 && skipped >= 0 && errors >= 0) {
            "Terminal counts must be nonnegative"
        }
        require(savedBytes >= 0 && bytesRead >= 0 && bytesWritten >= 0 && potentialSavingsBytes >= 0) {
            "Terminal byte totals must be nonnegative"
        }
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
            "schemaVersion" to header.schemaVersion, "recordType" to "header", "runId" to header.runId,
            "startedAt" to header.startedAt, "mode" to header.mode.name, "apkLabMode" to header.apkLabMode,
            "textMinify" to header.textMinify, "dryRun" to header.dryRun, "appVersion" to header.appVersion,
            "buildVariant" to header.buildVariant, "status" to RunStatus.RUNNING.name
        ))
    }

    fun appendEntry(writer: Writer, entry: UndoEntry) {
        require(entry.verificationLevel == UndoVerificationLevel.SHA_256) { "v2 entries require SHA-256 verification" }
        val fields = mutableListOf<Pair<String, Any>>(
            "schemaVersion" to V2_SCHEMA_VERSION, "recordType" to "entry", "relativePath" to entry.relativePath,
            "originalBytes" to entry.originalBytes, "optimizedBytes" to entry.optimizedBytes, "backupPath" to entry.backupPath,
            "originalSha256" to entry.originalSha256!!, "optimizedSha256" to entry.optimizedSha256!!,
            "fileKind" to entry.fileKind.name, "toolId" to entry.toolId,
            "verificationLevel" to entry.verificationLevel.name, "note" to entry.note, "completedAt" to entry.completedAt
        )
        entry.originalDocumentId?.let { fields += "originalDocumentId" to it }
        writeLine(writer, jsonObject(*fields.toTypedArray()))
    }

    fun appendTerminal(writer: Writer, terminal: UndoTerminalSummary) {
        writeLine(writer, jsonObject(
            "schemaVersion" to V2_SCHEMA_VERSION, "recordType" to "terminal", "status" to terminal.status.name,
            "completedAt" to terminal.completedAt, "entriesCommitted" to terminal.entriesCommitted, "scanned" to terminal.scanned,
            "optimized" to terminal.optimized, "skipped" to terminal.skipped, "errors" to terminal.errors,
            "savedBytes" to terminal.savedBytes, "bytesRead" to terminal.bytesRead,
            "bytesWritten" to terminal.bytesWritten, "potentialSavingsBytes" to terminal.potentialSavingsBytes
        ))
    }

    fun read(reader: Reader): UndoRun {
        val lines = BufferedReader(reader).readLines()
        val firstContent = lines.firstOrNull { it.isNotBlank() } ?: return emptyLegacyRun()
        val v2Header = parseV2Header(firstContent)
        return if (v2Header != null) readV2(lines, v2Header) else readLegacy(lines)
    }

    /**
     * Bounded incremental reader for restore discovery and service-selected logs. It preserves the
     * long-standing permissive committed-entry semantics of [read], while never materializing the
     * complete provider document and checking cancellation between every consumed character.
     */
    fun readStreamingForRestore(
        reader: Reader,
        cancellation: CancellationToken = NeverCancelled
    ): UndoRun {
        val lines = RestoreBoundedLineReader(reader, cancellation)
        val first = lines.nextNonBlank() ?: return emptyLegacyRun()
        val header = parseV2Header(first)
        return if (header != null) readV2Streaming(lines, header) else readLegacyStreaming(lines, first)
    }

    private fun readV2Streaming(lines: RestoreBoundedLineReader, header: UndoHeader): UndoRun {
        val entries = mutableListOf<UndoEntry>()
        var terminal: UndoTerminalSummary? = null
        var terminalInvalidated = false
        while (true) {
            val line = lines.nextLine() ?: break
            if (line.isBlank()) continue
            val fields = parseJsonObject(line) ?: continue
            if (fields.long("schemaVersion") != V2_SCHEMA_VERSION.toLong()) continue
            when (fields.string("recordType")) {
                "entry" -> {
                    if (terminal != null) terminalInvalidated = true
                    parseV2Entry(fields)?.let(entries::add)
                }
                "terminal" -> {
                    if (terminal != null) terminalInvalidated = true
                    else parseTerminal(fields)?.let { terminal = it }
                }
            }
            if (entries.size > RESTORE_MAX_RECORDS) throw IllegalArgumentException("Too many undo records")
        }
        val validTerminal = terminal?.takeIf { !terminalInvalidated && it.entriesCommitted == entries.size }
        return UndoRun(header, entries, validTerminal?.status ?: RunStatus.RUNNING, validTerminal)
    }

    private fun readLegacyStreaming(lines: RestoreBoundedLineReader, first: String): UndoRun {
        val stamp = LEGACY_HEADER.matchEntire(first.trim())?.groupValues?.get(1) ?: return emptyLegacyRun()
        val mode = lines.nextLine()?.takeIf { it.startsWith("Mode=") } ?: return emptyLegacyRun()
        val format = lines.nextLine()?.takeIf { it.trim() == LEGACY_FORMAT } ?: return emptyLegacyRun()
        @Suppress("UNUSED_VARIABLE") val recognizedLegacyHeader = mode to format
        val entries = mutableListOf<UndoEntry>()
        while (true) {
            val line = lines.nextLine() ?: break
            if (line.isBlank()) continue
            parseLegacyEntry(line, stamp)?.let(entries::add)
            if (entries.size > RESTORE_MAX_RECORDS) throw IllegalArgumentException("Too many undo records")
        }
        return UndoRun(UndoHeader(stamp, "", schemaVersion = LEGACY_SCHEMA_VERSION), entries, RunStatus.COMPLETED)
    }

    private fun readV2(lines: List<String>, header: UndoHeader): UndoRun {
        val entries = mutableListOf<UndoEntry>()
        var terminal: UndoTerminalSummary? = null
        var terminalInvalidated = false
        lines.dropWhile { it.isBlank() }.drop(1).forEach { line ->
            val fields = parseJsonObject(line) ?: return@forEach
            if (fields.long("schemaVersion") != V2_SCHEMA_VERSION.toLong()) return@forEach
            when (fields.string("recordType")) {
                "entry" -> {
                    if (terminal != null) terminalInvalidated = true
                    parseV2Entry(fields)?.let(entries::add)
                }
                "terminal" -> {
                    if (terminal != null) terminalInvalidated = true
                    else parseTerminal(fields)?.let { terminal = it }
                }
            }
        }
        val validTerminal = terminal?.takeIf { !terminalInvalidated && it.entriesCommitted == entries.size }
        return UndoRun(header, entries, validTerminal?.status ?: RunStatus.RUNNING, validTerminal)
    }

    private fun readLegacy(lines: List<String>): UndoRun {
        val contentLines = lines.dropWhile { it.isBlank() }
        val stamp = LEGACY_HEADER.matchEntire(contentLines.firstOrNull()?.trim().orEmpty())?.groupValues?.get(1)
        if (stamp == null || contentLines.getOrNull(1)?.startsWith("Mode=") != true ||
            contentLines.getOrNull(2)?.trim() != LEGACY_FORMAT
        ) return emptyLegacyRun()
        val entries = contentLines.drop(3).mapNotNull { parseLegacyEntry(it, stamp) }
        return UndoRun(UndoHeader(stamp, "", schemaVersion = LEGACY_SCHEMA_VERSION), entries, RunStatus.COMPLETED)
    }

    private fun parseV2Header(line: String): UndoHeader? {
        val fields = parseJsonObject(line) ?: return null
        if (fields.long("schemaVersion") != V2_SCHEMA_VERSION.toLong() ||
            fields.string("recordType") != "header" || fields.string("status") != RunStatus.RUNNING.name
        ) return null
        val mode = fields.string("mode")?.let { value -> OptimizeMode.entries.firstOrNull { it.name == value } } ?: return null
        val runId = fields.string("runId") ?: return null
        val startedAt = fields.string("startedAt") ?: return null
        val apkLabMode = fields.boolean("apkLabMode") ?: return null
        val textMinify = fields.boolean("textMinify") ?: return null
        val dryRun = fields.boolean("dryRun") ?: return null
        val appVersion = fields.requiredText("appVersion") ?: return null
        val buildVariant = fields.requiredText("buildVariant") ?: return null
        return UndoHeader(
            runId = runId,
            startedAt = startedAt,
            mode = mode,
            apkLabMode = apkLabMode,
            textMinify = textMinify,
            dryRun = dryRun,
            appVersion = appVersion,
            buildVariant = buildVariant
        )
    }

    private fun parseV2Entry(fields: Map<String, JsonField>): UndoEntry? = safely {
        if (fields.string("verificationLevel") != UndoVerificationLevel.SHA_256.name) return@safely null
        val fileKind = fields.string("fileKind")?.let { value -> FileKind.entries.firstOrNull { it.name == value } }
            ?: return@safely null
        UndoEntry(
            relativePath = fields.string("relativePath") ?: return@safely null,
            originalBytes = fields.long("originalBytes") ?: return@safely null,
            optimizedBytes = fields.long("optimizedBytes") ?: return@safely null,
            backupPath = fields.string("backupPath") ?: return@safely null,
            originalSha256 = fields.string("originalSha256") ?: return@safely null,
            note = fields.string("note") ?: return@safely null,
            optimizedSha256 = fields.string("optimizedSha256") ?: return@safely null,
            fileKind = fileKind,
            toolId = fields.requiredText("toolId") ?: return@safely null,
            completedAt = fields.requiredText("completedAt") ?: return@safely null,
            originalDocumentId = fields.string("originalDocumentId")?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseTerminal(fields: Map<String, JsonField>): UndoTerminalSummary? = safely {
        val status = fields.string("status")?.let { value -> RunStatus.entries.firstOrNull { it.name == value } }
            ?: return@safely null
        UndoTerminalSummary(
            status = status, completedAt = fields.requiredText("completedAt") ?: return@safely null,
            entriesCommitted = fields.int("entriesCommitted") ?: return@safely null,
            scanned = fields.int("scanned") ?: return@safely null, optimized = fields.int("optimized") ?: return@safely null,
            skipped = fields.int("skipped") ?: return@safely null, errors = fields.int("errors") ?: return@safely null,
            savedBytes = fields.long("savedBytes") ?: return@safely null,
            bytesRead = fields.long("bytesRead") ?: return@safely null,
            bytesWritten = fields.long("bytesWritten") ?: return@safely null,
            potentialSavingsBytes = fields.long("potentialSavingsBytes") ?: return@safely null
        )
    }

    private fun parseLegacyEntry(line: String, stamp: String): UndoEntry? {
        if (line.length > MAX_LEGACY_LINE_LENGTH) return null
        val boundaries = separatorBoundaries(line) ?: return null
        val backupPrefix = "FileForge_Backups_$stamp/"
        var candidate: UndoEntry? = null
        for (thirdIndex in 2 until boundaries.size) {
            val first = boundaries[thirdIndex - 2]
            val second = boundaries[thirdIndex - 1]
            val third = boundaries[thirdIndex]
            val backupStart = third + LEGACY_SEPARATOR.length
            if (!line.regionMatches(backupStart, backupPrefix, 0, backupPrefix.length)) continue

            val relativePath = line.substring(0, first)
            if (relativePath.isEmpty()) continue
            val backupEnd = backupStart + backupPrefix.length + relativePath.length
            if (backupEnd + LEGACY_SEPARATOR.length > line.length ||
                !line.regionMatches(backupStart + backupPrefix.length, relativePath, 0, relativePath.length) ||
                !line.regionMatches(backupEnd, LEGACY_SEPARATOR, 0, LEGACY_SEPARATOR.length)
            ) continue

            val originalBytes = line.substring(first + LEGACY_SEPARATOR.length, second).trim().toLongOrNull()
            val optimizedBytes = line.substring(second + LEGACY_SEPARATOR.length, third).trim().toLongOrNull()
            if (originalBytes == null || optimizedBytes == null || originalBytes < 0 || optimizedBytes < 0) continue
            if (candidate != null) return null
            candidate = UndoEntry(
                relativePath = relativePath,
                originalBytes = originalBytes,
                optimizedBytes = optimizedBytes,
                backupPath = line.substring(backupStart, backupEnd),
                originalSha256 = null,
                note = line.substring(backupEnd + LEGACY_SEPARATOR.length),
                verificationLevel = UndoVerificationLevel.LEGACY_SIZE_ONLY,
                optimizedSha256 = null,
                fileKind = FileKind.UNSUPPORTED,
                toolId = "legacy",
                completedAt = ""
            )
        }
        return candidate
    }

    private fun separatorBoundaries(line: String): List<Int>? {
        val boundaries = mutableListOf<Int>()
        var start = 0
        while (true) {
            val index = line.indexOf(LEGACY_SEPARATOR, start)
            if (index < 0) return boundaries
            if (boundaries.size == MAX_LEGACY_SEPARATORS) return null
            boundaries += index
            start = index + LEGACY_SEPARATOR.length
        }
    }

    private fun emptyLegacyRun() = UndoRun(
        UndoHeader("", "", schemaVersion = LEGACY_SCHEMA_VERSION), emptyList(), RunStatus.RUNNING
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
    ) { (name, value) ->
        "\"${escapeJson(name)}\":${if (value is String) "\"${escapeJson(value)}\"" else value}"
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        requireWellFormedUtf16(value)
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character in HIGH_SURROGATE..LOW_SURROGATE) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    private fun requireWellFormedUtf16(value: String) {
        var index = 0
        while (index < value.length) when (val character = value[index]) {
            in HIGH_SURROGATE..'\uDBFF' -> {
                require(value.getOrNull(index + 1) in '\uDC00'..LOW_SURROGATE) { "Unpaired high surrogate" }
                index += 2
            }
            in '\uDC00'..LOW_SURROGATE -> throw IllegalArgumentException("Unpaired low surrogate")
            else -> index++
        }
    }

    private fun parseJsonObject(line: String): Map<String, JsonField>? = JsonObjectParser(line).parse()
    private fun Map<String, JsonField>.string(name: String): String? = this[name]?.takeIf { it.kind == JsonKind.STRING }?.value
    private fun Map<String, JsonField>.requiredText(name: String): String? = string(name)?.takeIf { it.isNotEmpty() }
    private fun Map<String, JsonField>.long(name: String): Long? = this[name]?.takeIf { it.kind == JsonKind.NUMBER }?.value?.toLongOrNull()
    private fun Map<String, JsonField>.int(name: String): Int? = long(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    private fun Map<String, JsonField>.boolean(name: String): Boolean? = this[name]?.takeIf { it.kind == JsonKind.BOOLEAN }?.value?.toBooleanStrictOrNull()
    private inline fun <T> safely(block: () -> T?): T? = try { block() } catch (_: IllegalArgumentException) { null }

    private enum class JsonKind { STRING, NUMBER, BOOLEAN }
    private data class JsonField(val value: String, val kind: JsonKind)

    private class JsonObjectParser(private val text: String) {
        private var index = 0

        fun parse(): Map<String, JsonField>? {
            return try {
                skipWhitespace()
                expect('{')
                skipWhitespace()
                val fields = linkedMapOf<String, JsonField>()
                if (!consume('}')) {
                    while (true) {
                        skipWhitespace()
                        val key = string()
                        skipWhitespace()
                        expect(':')
                        skipWhitespace()
                        val value = when (peek()) {
                            '"' -> JsonField(string(), JsonKind.STRING)
                            't', 'f' -> JsonField(boolean(), JsonKind.BOOLEAN)
                            else -> JsonField(number(), JsonKind.NUMBER)
                        }
                        if (fields.put(key, value) != null) return null
                        skipWhitespace()
                        if (consume('}')) break
                        expect(',')
                    }
                }
                skipWhitespace()
                if (index == text.length) fields else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun string(): String {
            expect('"')
            return buildString {
                while (true) when (val character = next()) {
                    '"' -> return@buildString
                    '\\' -> appendEscaped(this)
                    in HIGH_SURROGATE..'\uDBFF' -> {
                        require(peek() in '\uDC00'..LOW_SURROGATE) { "Unpaired raw high surrogate" }
                        append(character)
                        append(next())
                    }
                    in '\uDC00'..LOW_SURROGATE -> throw IllegalArgumentException("Unpaired raw low surrogate")
                    else -> {
                        require(character.code >= 0x20) { "Unescaped control character" }
                        append(character)
                    }
                }
            }
        }

        private fun appendEscaped(output: StringBuilder) {
            when (val escape = next()) {
                '"', '\\', '/' -> output.append(escape)
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> appendUnicode(output)
                else -> throw IllegalArgumentException("Invalid JSON escape")
            }
        }

        private fun appendUnicode(output: StringBuilder) {
            val first = unicodeCharacter()
            when (first) {
                in HIGH_SURROGATE..'\uDBFF' -> {
                    expect('\\')
                    expect('u')
                    val second = unicodeCharacter()
                    require(second in '\uDC00'..LOW_SURROGATE) { "Unpaired escaped high surrogate" }
                    output.append(first)
                    output.append(second)
                }
                in '\uDC00'..LOW_SURROGATE -> throw IllegalArgumentException("Unpaired escaped low surrogate")
                else -> output.append(first)
            }
        }

        private fun unicodeCharacter(): Char {
            val digits = (1..4).map { next() }.joinToString("")
            return digits.toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("Invalid unicode escape")
        }

        private fun boolean(): String = when {
            text.startsWith("true", index) -> { index += 4; "true" }
            text.startsWith("false", index) -> { index += 5; "false" }
            else -> throw IllegalArgumentException("Expected JSON boolean")
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

        private fun skipWhitespace() { while (peek() in JSON_WHITESPACE) index++ }
        private fun consume(character: Char): Boolean = if (peek() == character) { index++; true } else false
        private fun expect(character: Char) { require(consume(character)) { "Expected $character" } }
        private fun next(): Char = peek()?.also { index++ } ?: throw IllegalArgumentException("Unexpected end of JSON")
        private fun peek(): Char? = text.getOrNull(index)
    }

    companion object {
        const val RESTORE_MAX_LINE_CHARS = 256 * 1024
        const val RESTORE_MAX_TOTAL_CHARS = 16 * 1024 * 1024
        const val RESTORE_MAX_RECORDS = 100_000
        const val V2_SCHEMA_VERSION = 2
        const val LEGACY_SCHEMA_VERSION = 1
        const val LEGACY_FORMAT = "Format: relative_path | original_bytes | optimized_bytes | backup_path | note"
        const val LEGACY_SEPARATOR = " | "
        const val MAX_LEGACY_LINE_LENGTH = 64 * 1024
        const val MAX_LEGACY_SEPARATORS = 32
        const val HIGH_SURROGATE = '\uD800'
        const val LOW_SURROGATE = '\uDFFF'
        val LEGACY_HEADER = Regex("FileForge Undo Log (.+)")
        val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
    }
}

private class RestoreBoundedLineReader(
    private val reader: Reader,
    private val cancellation: CancellationToken
) {
    private var totalChars = 0

    fun nextNonBlank(): String? {
        while (true) {
            val next = nextLine() ?: return null
            if (next.isNotBlank()) return next
        }
    }

    fun nextLine(): String? {
        val line = StringBuilder()
        var sawAny = false
        while (true) {
            cancellation.throwIfCancelled()
            val next = reader.read()
            if (next < 0) return line.takeIf { sawAny }?.toString()
            sawAny = true
            totalChars++
            if (totalChars > UndoLogRepository.RESTORE_MAX_TOTAL_CHARS) {
                throw IllegalArgumentException("Undo log exceeds restore size limit")
            }
            val character = next.toChar()
            if (character == '\n') return line.toString()
            if (character != '\r') {
                if (line.length == UndoLogRepository.RESTORE_MAX_LINE_CHARS) {
                    throw IllegalArgumentException("Undo log record exceeds restore line limit")
                }
                line.append(character)
            }
        }
    }
}
