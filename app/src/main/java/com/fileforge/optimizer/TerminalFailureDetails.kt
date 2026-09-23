package com.fileforge.optimizer

internal object TerminalFailureDetails {
    const val MAX_DETAILS = 20
    const val MAX_DETAIL_CHARS = 512

    fun append(existing: List<String>, rawDetail: String, priority: Boolean = false): List<String> {
        val detail = sanitize(rawDetail)
        if (detail.isEmpty()) return existing.take(MAX_DETAILS)
        if (existing.size < MAX_DETAILS) return existing + detail
        if (!priority) return existing.take(MAX_DETAILS)
        return existing.take(MAX_DETAILS - 1) + detail
    }

    fun sanitize(rawDetail: String): String {
        val bounded = StringBuilder(minOf(rawDetail.length, MAX_DETAIL_CHARS))
        for (character in rawDetail) {
            if (bounded.length == MAX_DETAIL_CHARS) break
            bounded.append(
                when (character) {
                    '\r', '\n', '\t' -> ' '
                    else -> character
                }
            )
        }
        return bounded.toString().trim()
    }
}
