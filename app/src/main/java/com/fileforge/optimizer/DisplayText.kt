package com.fileforge.optimizer

/**
 * Longest undo-log string the Restore screen lays out in full. Real relative paths are far
 * shorter, so only text from a corrupted or crafted undo log is ever shortened.
 */
internal const val MAX_DISPLAY_CHARS = 256

private const val ELLIPSIS = "…"

/**
 * Bounds text read from an undo log before it is shown on screen.
 *
 * Undo logs come from the user-selected folder, so every string in them is untrusted and can
 * be arbitrarily long. Android lays text out in native memory: two unbroken 600,000-character
 * paths on the Restore screen took about 6 GB and got the app killed the moment the tab opened.
 * Text past [maxChars] keeps its start and its end around a middle ellipsis, so the leading
 * directories and the file name both stay readable. The result is never longer than [maxChars],
 * and a surrogate pair is never split into an unpaired half.
 *
 * Only the displayed copy is shortened. Restore itself keeps using the full stored path.
 */
internal fun boundedForDisplay(text: String, maxChars: Int = MAX_DISPLAY_CHARS): String {
    require(maxChars > ELLIPSIS.length) { "maxChars must leave room beside the ellipsis" }
    if (text.length <= maxChars) return text
    val kept = maxChars - ELLIPSIS.length
    var head = text.take((kept + 1) / 2)
    if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
    var tail = text.takeLast(kept / 2)
    if (tail.isNotEmpty() && tail.first().isLowSurrogate()) tail = tail.drop(1)
    return head + ELLIPSIS + tail
}
