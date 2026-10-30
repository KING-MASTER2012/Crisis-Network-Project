package org.crisisnetwork.android.settings

import org.crisisnetwork.android.messaging.Lifetimes
import org.crisisnetwork.android.messaging.Limits

/**
 * The sizes of the message store the user can choose (phase A6). The core takes the size when the
 * node is opened, so a new choice is remembered at once and applies the next time the mesh starts.
 *
 * The share of the store that other nodes may pin by addressing messages to this phone stays the
 * same as in the core's defaults (8 of 32 MB, a quarter), whatever size is chosen.
 */
object StorageLimits {
    /** The choices, in megabytes. The smallest is far above the core's minimum (one maximum-size envelope). */
    val presetsMb: List<Int> = listOf(8, 16, 32, 64, 128, 256)

    /** Equals the core's default store size. */
    const val DEFAULT_MB = 32

    private const val BYTES_PER_MB = 1024L * 1024L
    private const val INBOUND_PINNED_DIVISOR = 4L

    fun bytesOf(mb: Int): Long = mb.toLong() * BYTES_PER_MB

    /** Bytes of the store that other nodes may fill with messages addressed to this phone. */
    fun inboundPinnedBytes(mb: Int): Long = bytesOf(mb) / INBOUND_PINNED_DIVISOR

    /** A stored value that is not one of the choices (an old or damaged setting) becomes the default. */
    fun coerce(mb: Int): Int = if (mb in presetsMb) mb else DEFAULT_MB
}

/**
 * The lifetime a new announcement gets unless its author picks another one. The choices are the
 * presets of the composer, so the preselected row always exists there.
 */
object DefaultLifetimes {
    fun options(maxSecs: Long = Limits.DEFAULT_MAX_LIFETIME): List<Long> = Lifetimes.presets(maxSecs)

    /** A stored value that is not a choice (or exceeds the protocol's maximum) becomes one day. */
    fun coerce(secs: Long, maxSecs: Long = Limits.DEFAULT_MAX_LIFETIME): Long = if (secs in options(maxSecs)) secs else Lifetimes.DEFAULT_SECS
}
