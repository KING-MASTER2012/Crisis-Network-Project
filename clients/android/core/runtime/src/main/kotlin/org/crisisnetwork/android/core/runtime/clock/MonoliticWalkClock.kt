package org.crisisnetwork.android.core.runtime.clock

/**
 * The clock policy of the runtime.
 *
 * Problem: the core persists `received_at_ms` and counts expiry from it. `elapsedRealtime`
 * alone restarts at every boot, so stored times would become meaningless. The wall clock alone
 * can jump (the user changes it, the network corrects it, a phone boots with a clock at 1970).
 *
 * Policy:
 *
 * 1. **Inside one process** time advances with the monotonic clock only. A wall clock that
 *    jumps backwards, or that is wrong, cannot make the returned time go backwards.
 * 2. **Forward corrections are adopted.** When the wall clock is ahead of the monotonic time by
 *    more than [forwardToleranceMs] (for example the network set the clock after boot), the
 *    clock jumps forward to it. Ordinary drift stays below the tolerance and is ignored.
 * 3. **Across restarts** the latest handed-out time is remembered (the "high-water mark"). At
 *    start-up:
 *    * an implausible wall clock (before 2020) means "not set yet": use the larger of wall clock
 *      and mark;
 *    * a plausible wall clock that is at most 24 hours behind the mark: keep the mark, so time
 *      stays monotonic across a restart despite small adjustments;
 *    * otherwise trust the wall clock. This lets a clock that was wrongly far in the future
 *      heal after a restart instead of staying ahead forever.
 *
 * Known limitation: a wall clock that jumps far *forward* while the app runs is adopted
 * (rule 2), because it cannot be told from a legitimate correction. Messages then expire
 * earlier than intended. The threat model already treats the sender clock as untrusted; the
 * local clock is trusted to be set by the owner of the phone.
 *
 * @param wallMs `System.currentTimeMillis`.
 * @param elapsedMs `SystemClock.elapsedRealtime` (counts deep sleep, resets at boot).
 */
class MonotonicWallClock(
    private val wallMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val store: HighWaterStore,
    private val forwardToleranceMs: Long = DEFAULT_FORWARD_TOLERANCE_MS,
    private val persistEveryMs: Long = DEFAULT_PERSIST_EVERY_MS,
) : CoreClock {
    private var anchorTime: Long
    private var anchorElapsed: Long
    private var lastReturned: Long
    private var lastPersisted: Long = 0L

    init {
        val start = chooseStart(wallMs(), store.read())
        anchorTime = start
        anchorElapsed = elapsedMs()
        lastReturned = start
    }

    @Synchronized
    override fun nowMs(): Long {
        val elapsed = elapsedMs()
        val wall = wallMs()
        var now = anchorTime + (elapsed - anchorElapsed)
        if (wall - now > forwardToleranceMs) {
            anchorTime = wall
            anchorElapsed = elapsed
            now = wall
        }
        if (now < lastReturned) now = lastReturned
        lastReturned = now
        if (now - lastPersisted >= persistEveryMs) persist(now)
        return now
    }

    @Synchronized
    override fun flush() {
        persist(lastReturned)
    }

    private fun persist(value: Long) {
        // Set first: a failing store must not be retried on every call.
        lastPersisted = value
        try {
            store.write(value)
        } catch (e: Exception) {
            // Losing the high-water mark only weakens rule 3; it never breaks the running clock.
        }
    }

    companion object {
        const val DEFAULT_FORWARD_TOLERANCE_MS: Long = 2_000L
        const val DEFAULT_PERSIST_EVERY_MS: Long = 15_000L

        /** 2020-01-01T00:00:00Z: a wall clock before this has not been set. */
        const val PLAUSIBLE_MIN_MS: Long = 1_577_836_800_000L

        /** A plausible wall clock this far behind the mark is trusted over the mark. */
        const val SMALL_BACKWARD_MS: Long = 24L * 60 * 60 * 1000

        /** The start time for a process, from the wall clock and the stored mark (rule 3). */
        internal fun chooseStart(wall: Long, mark: Long?): Long {
            if (mark == null) return wall
            if (wall < PLAUSIBLE_MIN_MS) return maxOf(wall, mark)
            val behind = mark - wall
            return if (behind in 1..SMALL_BACKWARD_MS) mark else wall
        }
    }
}
