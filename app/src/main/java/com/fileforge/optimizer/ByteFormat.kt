package com.fileforge.optimizer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Powers of 1024 with IEC labels. FileForge accounts in raw bytes and compares exact sizes, so
 * 1024-based units label that arithmetic honestly instead of implying decimal megabytes.
 */
private val BYTE_UNITS = listOf("KiB", "MiB", "GiB", "TiB", "PiB")

/**
 * Renders a byte count for display: exact below 1 KiB, one decimal place above it.
 *
 * Pure Kotlin on purpose. Notification and screen text are rendered by plain functions that unit
 * tests exercise without Android, so this cannot reach for `android.text.format.Formatter`.
 */
fun formatByteSize(bytes: Long): String {
    val magnitude = abs(bytes.toDouble())
    if (magnitude < 1024.0) return "$bytes B"

    val sign = if (bytes < 0) "-" else ""
    var value = magnitude / 1024.0
    var unitIndex = 0
    // Rounding before the comparison keeps 1048575 B at "1.0 MiB" rather than "1024.0 KiB".
    while (round(value * 10.0) / 10.0 >= 1024.0 && unitIndex < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return sign + String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unitIndex])
}
