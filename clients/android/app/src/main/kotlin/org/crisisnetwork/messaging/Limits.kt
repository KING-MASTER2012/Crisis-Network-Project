package org.crisisnetwork.android.messaging

/** What the core accepts, as the screens need it. The defaults equal the protocol's limits. */
data class Limits(
    /** Largest payload of a message in bytes. */
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD,
    /** Longest lifetime of a message in seconds. */
    val maxLifetimeSecs: Long = DEFAULT_MAX_LIFETIME,
) {
    companion object {
        const val DEFAULT_MAX_PAYLOAD = 1024
        const val DEFAULT_MAX_LIFETIME = 7L * 24 * 60 * 60
    }
}

/** Counting the bytes of a text against the limit of the message it goes into. */
object ByteBudget {
    /** A direct message is sealed: the box adds a key (32 bytes) and a tag (16 bytes) to the text. */
    const val DIRECT_SEAL_OVERHEAD = 48

    fun directLimit(maxPayloadBytes: Int): Int = maxPayloadBytes - DIRECT_SEAL_OVERHEAD

    fun announcementLimit(maxPayloadBytes: Int, severity: Severity): Int = maxPayloadBytes - AnnouncementCodec.overhead(severity)

    /** Bytes of [text] as UTF-8: what counts, not the number of characters. */
    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    class Count(val used: Int, val limit: Int) {
        val remaining: Int get() = limit - used
        val over: Boolean get() = used > limit

        /** Within the last tenth of the room: time to warn. */
        val nearLimit: Boolean get() = !over && remaining <= limit / 10
    }

    fun count(text: String, limit: Int): Count = Count(utf8Length(text), limit)
}

/** Units of the lifetime of an announcement. */
enum class LifetimeUnit(val seconds: Long) {
    Seconds(1),
    Minutes(60),
    Hours(3_600),
    Days(86_400),
}

/** Choosing how long an announcement stays: a few presets and a custom value from 1 second up to the maximum. */
object Lifetimes {
    const val MIN_SECS = 1L

    val presetSeconds: List<Long> = listOf(900L, 3_600L, 21_600L, 86_400L, 259_200L, 604_800L)

    /** The preset that is selected when the screen opens: one day. */
    const val DEFAULT_SECS = 86_400L

    /** The presets that do not exceed [maxSecs]. */
    fun presets(maxSecs: Long): List<Long> = presetSeconds.filter { it <= maxSecs }

    /** [value] times [unit] in seconds; `0` for a value that is not positive, saturated instead of overflowing. */
    fun toSeconds(value: Long, unit: LifetimeUnit): Long =
        when {
            value <= 0L -> 0L
            value > Long.MAX_VALUE / unit.seconds -> Long.MAX_VALUE
            else -> value * unit.seconds
        }

    fun isValid(secs: Long, maxSecs: Long): Boolean = secs in MIN_SECS..maxSecs

    /** The largest unit that divides [secs] evenly, for showing "15 minutes" instead of "900 seconds". */
    fun split(secs: Long): Pair<Long, LifetimeUnit> {
        for (unit in LifetimeUnit.entries.reversed()) {
            if (secs >= unit.seconds && secs % unit.seconds == 0L) return secs / unit.seconds to unit
        }
        return secs to LifetimeUnit.Seconds
    }
}
