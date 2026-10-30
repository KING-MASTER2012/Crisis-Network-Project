package org.crisisnetwork.android.ui.network

import java.text.NumberFormat
import java.util.Locale

/** Numbers of the network screen as text: sizes in bytes and counters. Pure, so that it can be tested. */
object ByteSizes {
    private val units = arrayOf("KB", "MB", "GB", "TB")

    /** Just below 1024: a value that would be shown as "1024.0" moves up to the next unit. */
    private const val UNIT_STEP = 1023.95

    /** `512 B`, `1.5 KB`, `32 MB`: one decimal below 100, none above. Negative sizes count as zero. */
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val value = bytes.coerceAtLeast(0L)
        if (value < 1024L) return "$value B"
        var size = value / 1024.0
        var unit = 0
        while (size >= UNIT_STEP && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        val pattern = if (size >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(locale, pattern, size, units[unit])
    }

    /** How full the store is, from 0 to 1. A limit of zero counts as empty instead of dividing by it. */
    fun fraction(used: Long, limit: Long): Float = if (limit <= 0L) 0f else (used.coerceAtLeast(0L).toDouble() / limit).coerceIn(0.0, 1.0).toFloat()

    /** A counter with the digit grouping of the locale. */
    fun count(value: ULong, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(value.toLongSaturated())

    fun count(value: Long, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(value)

    private fun ULong.toLongSaturated(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
}
