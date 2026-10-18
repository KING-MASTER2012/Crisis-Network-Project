package org.crisisnetwork.android.core.runtime.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicWallClockTest {
    private class MemoryStore(var value: Long? = null) : HighWaterStore {
        var writes = 0

        override fun read(): Long? = value

        override fun write(valueMs: Long) {
            writes += 1
            value = valueMs
        }
    }

    private class Sources(var wall: Long, var elapsed: Long = 0L)

    private fun clockOf(sources: Sources, store: HighWaterStore = MemoryStore()) =
        MonotonicWallClock(
            wallMs = { sources.wall },
            elapsedMs = { sources.elapsed },
            store = store,
            forwardToleranceMs = 2_000L,
            persistEveryMs = 15_000L,
        )

    @Test
    fun timeAdvancesWithTheMonotonicClock() {
        val sources = Sources(wall = T0)
        val clock = clockOf(sources)
        assertEquals(T0, clock.nowMs())
        sources.elapsed += 1_000L
        sources.wall += 1_000L
        assertEquals(T0 + 1_000L, clock.nowMs())
    }

    @Test
    fun aWallClockThatJumpsBackwardsCannotMakeTimeGoBackwards() {
        val sources = Sources(wall = T0)
        val clock = clockOf(sources)
        clock.nowMs()
        sources.elapsed += 1_000L
        sources.wall = T0 - 5_000_000L
        assertEquals(T0 + 1_000L, clock.nowMs())
        sources.elapsed += 500L
        assertEquals(T0 + 1_500L, clock.nowMs())
    }

    @Test
    fun aForwardCorrectionOfTheWallClockIsAdopted() {
        val sources = Sources(wall = T0)
        val clock = clockOf(sources)
        clock.nowMs()
        sources.elapsed += 1_000L
        sources.wall = T0 + 3_600_000L
        assertEquals(T0 + 3_600_000L, clock.nowMs())
        sources.elapsed += 250L
        sources.wall += 250L
        assertEquals(T0 + 3_600_250L, clock.nowMs())
    }

    @Test
    fun ordinaryDriftBelowTheToleranceIsIgnored() {
        val sources = Sources(wall = T0)
        val clock = clockOf(sources)
        clock.nowMs()
        sources.elapsed += 10_000L
        sources.wall += 11_500L
        assertEquals(T0 + 10_000L, clock.nowMs())
    }

    @Test
    fun theReturnedTimeNeverDecreases() {
        val sources = Sources(wall = T0)
        val clock = clockOf(sources)
        var previous = clock.nowMs()
        val wallSteps = longArrayOf(500, -90_000, 3_000, 40_000_000, -40_000_000, 10)
        for (step in wallSteps) {
            sources.elapsed += 100L
            sources.wall += step
            val now = clock.nowMs()
            assertTrue("time went backwards: $previous -> $now", now >= previous)
            previous = now
        }
    }

    @Test
    fun anUnsetWallClockAfterRebootUsesTheStoredMark() {
        val store = MemoryStore(T0)
        val sources = Sources(wall = 1_000L)
        val clock = clockOf(sources, store)
        assertEquals(T0, clock.nowMs())
        // The network sets the clock later, ahead of the mark: it is adopted.
        sources.elapsed += 60_000L
        sources.wall = T0 + 90_000L
        assertEquals(T0 + 90_000L, clock.nowMs())
    }

    @Test
    fun aSmallBackwardAdjustmentBeforeARestartKeepsTimeMonotonic() {
        val store = MemoryStore(T0)
        val clock = clockOf(Sources(wall = T0 - 60_000L), store)
        assertEquals(T0, clock.nowMs())
    }

    @Test
    fun aPlausibleWallClockFarBehindTheMarkIsTrustedOverIt() {
        val store = MemoryStore(T0)
        val threeDays = 3L * 24 * 60 * 60 * 1000
        val clock = clockOf(Sources(wall = T0 - threeDays), store)
        assertEquals(T0 - threeDays, clock.nowMs())
    }

    @Test
    fun withoutAMarkTheWallClockIsTheStart() {
        assertEquals(T0, clockOf(Sources(wall = T0)).nowMs())
    }

    @Test
    fun theMarkIsPersistedThrottledAndOnFlush() {
        val store = MemoryStore()
        val sources = Sources(wall = T0)
        val clock = clockOf(sources, store)
        clock.nowMs()
        assertEquals(1, store.writes)
        sources.elapsed += 5_000L
        sources.wall += 5_000L
        clock.nowMs()
        assertEquals(1, store.writes)
        sources.elapsed += 11_000L
        sources.wall += 11_000L
        clock.nowMs()
        assertEquals(2, store.writes)
        sources.elapsed += 1_000L
        sources.wall += 1_000L
        val last = clock.nowMs()
        clock.flush()
        assertEquals(3, store.writes)
        assertEquals(last, store.value)
    }

    @Test
    fun aFailingStoreNeverBreaksTheClock() {
        val failing =
            object : HighWaterStore {
                override fun read(): Long? = null

                override fun write(valueMs: Long) = throw java.io.IOException("disk full")
            }
        val sources = Sources(wall = T0)
        val clock = clockOf(sources, failing)
        sources.elapsed += 1_000L
        assertEquals(T0 + 1_000L, clock.nowMs())
        clock.flush()
    }

    @Test
    fun theFileStoreRoundTripsAndToleratesGarbage() {
        val dir = java.nio.file.Files.createTempDirectory("clock-store").toFile()
        try {
            val file = java.io.File(dir, "nested/clock.mark")
            val store = FileHighWaterStore(file)
            assertNull(store.read())
            store.write(T0)
            assertEquals(T0, store.read())
            store.write(T0 + 1)
            assertEquals(T0 + 1, store.read())
            file.writeText("not a number")
            assertNull(store.read())
            assertNotNull(file.parentFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** 2027-01-15, a plausible wall-clock time. */
        const val T0 = 1_800_000_000_000L
    }
}
