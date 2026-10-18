package org.crisisnetwork.android.core.runtime.clock

/**
 * The clock the runtime hands to the core (`now_ms` of every call).
 *
 * The core stores receive times in its database and counts message expiry from them, so the
 * clock must (a) be comparable across restarts and (b) never run backwards. See
 * [MonotonicWallClock] for the policy used on Android.
 */
fun interface CoreClock {
    /** Milliseconds since the Unix epoch, never decreasing for one clock instance. */
    fun nowMs(): Long

    /** Persists whatever the clock needs to survive a restart. Called when the runtime stops. */
    fun flush() {}
}
