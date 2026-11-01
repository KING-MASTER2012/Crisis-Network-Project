package org.crisisnetwork.android.messaging

import uniffi.crisis_core.announcementByteLimit
import uniffi.crisis_core.budgetStatus
import uniffi.crisis_core.directByteLimit
import uniffi.crisis_core.lifetimeIsValid
import uniffi.crisis_core.lifetimePresets
import uniffi.crisis_core.lifetimeToSeconds
import uniffi.crisis_core.splitLifetime
import uniffi.crisis_core.LifetimeUnit as CoreLifetimeUnit

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

    fun directLimit(maxPayloadBytes: Int): Int = directByteLimit(maxPayloadBytes.coerceAtLeast(0).toUInt()).toInt()

    fun announcementLimit(maxPayloadBytes: Int, severity: Severity): Int =
        announcementByteLimit(maxPayloadBytes.coerceAtLeast(0).toUInt(), severity.toCore()).toInt()

    /** Bytes of [text] as UTF-8: what counts, not the number of characters. */
    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** How much of [limit] [used] bytes take. Whether it is over or near the limit is decided by the core. */
    class Count(val used: Int, val limit: Int) {
        private val status by lazy(LazyThreadSafetyMode.NONE) {
            budgetStatus(used.coerceAtLeast(0).toUInt(), limit.coerceAtLeast(0).toUInt())
        }

        val remaining: Int get() = limit - used
        val over: Boolean get() = status.over

        /** Within the last tenth of the room: time to warn. */
        val nearLimit: Boolean get() = status.nearLimit
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

    /** The presets: 15 minutes, 1 hour, 6 hours, 1 day, 3 days, 7 days. Read from the core on first use. */
    val presetSeconds: List<Long> by lazy { lifetimePresets(Long.MAX_VALUE) }

    /** The preset that is selected when the screen opens: one day. */
    const val DEFAULT_SECS = 86_400L

    /** The presets that do not exceed [maxSecs]. */
    fun presets(maxSecs: Long): List<Long> = lifetimePresets(maxSecs)

    /** [value] times [unit] in seconds; `0` for a value that is not positive, saturated instead of overflowing. */
    fun toSeconds(value: Long, unit: LifetimeUnit): Long = lifetimeToSeconds(value, unit.toCore())

    fun isValid(secs: Long, maxSecs: Long): Boolean = lifetimeIsValid(secs, maxSecs)

    /** The largest unit that divides [secs] evenly, for showing "15 minutes" instead of "900 seconds". */
    fun split(secs: Long): Pair<Long, LifetimeUnit> {
        val parts = splitLifetime(secs)
        return parts.value to parts.unit.toApp()
    }
}

private fun LifetimeUnit.toCore(): CoreLifetimeUnit =
    when (this) {
        LifetimeUnit.Seconds -> CoreLifetimeUnit.SECONDS
        LifetimeUnit.Minutes -> CoreLifetimeUnit.MINUTES
        LifetimeUnit.Hours -> CoreLifetimeUnit.HOURS
        LifetimeUnit.Days -> CoreLifetimeUnit.DAYS
    }

private fun CoreLifetimeUnit.toApp(): LifetimeUnit =
    when (this) {
        CoreLifetimeUnit.SECONDS -> LifetimeUnit.Seconds
        CoreLifetimeUnit.MINUTES -> LifetimeUnit.Minutes
        CoreLifetimeUnit.HOURS -> LifetimeUnit.Hours
        CoreLifetimeUnit.DAYS -> LifetimeUnit.Days
    }
