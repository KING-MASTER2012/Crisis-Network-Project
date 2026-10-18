package org.crisisnetwork.android.core.runtime

/**
 * Decides when the actor must call `tick` next. Pure logic, no clock of its own.
 *
 * * The core's `next_timer_ms` replaces the deadline; `null` keeps the previous one.
 * * A tick consumes the deadline: the core must name a new one if it needs it.
 * * There is always an idle tick every [idleTickMs] ("it is cheap"), so purging and housekeeping
 *   happen even if the core asks for nothing.
 * * Two ticks are at least [minGapMs] apart, so a core that keeps returning a deadline in the
 *   past cannot spin the actor.
 */
internal class TimerPolicy(
    private val idleTickMs: Long = DEFAULT_IDLE_TICK_MS,
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
) {
    private var deadline: Long? = null
    private var lastTickAt: Long? = null

    fun onOutput(nextTimerMs: Long?) {
        if (nextTimerMs != null) deadline = nextTimerMs
    }

    fun onTick(nowMs: Long) {
        deadline = null
        lastTickAt = nowMs
    }

    /** Milliseconds to wait for the next message before ticking. Zero means "tick now". */
    fun waitMs(nowMs: Long): Long {
        val last = lastTickAt
        var due = if (last == null) nowMs else last + idleTickMs
        deadline?.let { due = minOf(due, it) }
        if (last != null) due = maxOf(due, last + minGapMs)
        return (due - nowMs).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_IDLE_TICK_MS: Long = 30_000L
        const val DEFAULT_MIN_GAP_MS: Long = 10L
    }
}
