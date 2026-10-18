package org.crisisnetwork.android.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerPolicyTest {
    @Test
    fun theVeryFirstWaitIsZeroSoTheActorTicksAtStart() {
        assertEquals(0L, TimerPolicy().waitMs(1_000L))
    }

    @Test
    fun withoutADeadlineTheIdleTickApplies() {
        val timer = TimerPolicy(idleTickMs = 30_000L, minGapMs = 10L)
        timer.onTick(1_000L)
        assertEquals(30_000L, timer.waitMs(1_000L))
        assertEquals(20_000L, timer.waitMs(11_000L))
    }

    @Test
    fun theCoresDeadlineWinsWhenItIsSooner() {
        val timer = TimerPolicy(idleTickMs = 30_000L, minGapMs = 10L)
        timer.onTick(1_000L)
        timer.onOutput(1_100L)
        assertEquals(100L, timer.waitMs(1_000L))
        assertEquals(0L, timer.waitMs(1_100L))
        assertEquals(0L, timer.waitMs(5_000L))
    }

    @Test
    fun aNullDeadlineKeepsThePreviousOne() {
        val timer = TimerPolicy(idleTickMs = 30_000L, minGapMs = 10L)
        timer.onTick(1_000L)
        timer.onOutput(1_500L)
        timer.onOutput(null)
        assertEquals(500L, timer.waitMs(1_000L))
    }

    @Test
    fun aTickConsumesTheDeadline() {
        val timer = TimerPolicy(idleTickMs = 30_000L, minGapMs = 10L)
        timer.onTick(1_000L)
        timer.onOutput(1_500L)
        timer.onTick(1_500L)
        assertEquals(30_000L, timer.waitMs(1_500L))
    }

    @Test
    fun aDeadlineInThePastCannotSpinTheActor() {
        val timer = TimerPolicy(idleTickMs = 30_000L, minGapMs = 10L)
        timer.onTick(1_000L)
        timer.onOutput(500L)
        // Due immediately by the deadline, but never closer than minGapMs to the last tick.
        assertEquals(10L, timer.waitMs(1_000L))
        assertEquals(0L, timer.waitMs(1_010L))
    }

    @Test
    fun theIdleTickKeepsTheActorHousekeepingEvenWhenTheCoreAsksForNothing() {
        val timer = TimerPolicy(idleTickMs = 1_000L, minGapMs = 10L)
        timer.onTick(0L)
        assertEquals(0L, timer.waitMs(1_000L))
    }
}
