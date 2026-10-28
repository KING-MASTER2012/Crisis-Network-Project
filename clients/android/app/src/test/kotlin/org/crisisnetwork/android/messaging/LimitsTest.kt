package org.crisisnetwork.android.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    @Test
    fun theDefaultsMatchTheProtocol() {
        assertEquals(1024, Limits().maxPayloadBytes)
        assertEquals(604_800L, Limits().maxLifetimeSecs)
        // A direct message loses 48 bytes to the sealed box: 976 are left for the text.
        assertEquals(976, ByteBudget.directLimit(1024))
        assertEquals(1024, ByteBudget.announcementLimit(1024, Severity.Normal))
        assertEquals(1022, ByteBudget.announcementLimit(1024, Severity.Emergency))
    }

    @Test
    fun theCounterCountsBytesNotCharacters() {
        assertEquals(5, ByteBudget.utf8Length("hello"))
        assertEquals(10, ByteBudget.utf8Length("şğüçö"))
        assertEquals(4, ByteBudget.utf8Length("🚰"))
        val count = ByteBudget.count("şğüçö", 976)
        assertEquals(10, count.used)
        assertEquals(966, count.remaining)
        assertFalse(count.over)
    }

    @Test
    fun theCounterWarnsNearTheLimitAndRefusesAbove() {
        assertFalse(ByteBudget.Count(800, 976).nearLimit)
        assertTrue(ByteBudget.Count(900, 976).nearLimit)
        assertTrue(ByteBudget.Count(976, 976).nearLimit)
        assertFalse(ByteBudget.Count(976, 976).over)
        assertTrue(ByteBudget.Count(977, 976).over)
        assertFalse(ByteBudget.Count(977, 976).nearLimit)
    }

    @Test
    fun lifetimesAreChosenBetweenOneSecondAndTheMaximum() {
        val max = 604_800L
        assertEquals(listOf(900L, 3_600L, 21_600L, 86_400L, 259_200L, 604_800L), Lifetimes.presets(max))
        assertEquals(listOf(900L, 3_600L), Lifetimes.presets(7_200L))
        assertTrue(Lifetimes.DEFAULT_SECS in Lifetimes.presetSeconds)

        assertTrue(Lifetimes.isValid(1, max))
        assertTrue(Lifetimes.isValid(max, max))
        assertFalse(Lifetimes.isValid(0, max))
        assertFalse(Lifetimes.isValid(max + 1, max))

        assertEquals(90L, Lifetimes.toSeconds(90, LifetimeUnit.Seconds))
        assertEquals(7_200L, Lifetimes.toSeconds(2, LifetimeUnit.Hours))
        assertEquals(604_800L, Lifetimes.toSeconds(7, LifetimeUnit.Days))
        assertEquals(0L, Lifetimes.toSeconds(0, LifetimeUnit.Days))
        assertEquals(0L, Lifetimes.toSeconds(-5, LifetimeUnit.Minutes))
        assertEquals(Long.MAX_VALUE, Lifetimes.toSeconds(Long.MAX_VALUE, LifetimeUnit.Days))
    }

    @Test
    fun aLifetimeIsShownInTheLargestFittingUnit() {
        assertEquals(15L to LifetimeUnit.Minutes, Lifetimes.split(900))
        assertEquals(1L to LifetimeUnit.Hours, Lifetimes.split(3_600))
        assertEquals(3L to LifetimeUnit.Days, Lifetimes.split(259_200))
        assertEquals(90L to LifetimeUnit.Minutes, Lifetimes.split(5_400))
        assertEquals(1L to LifetimeUnit.Seconds, Lifetimes.split(1))
        assertEquals(61L to LifetimeUnit.Seconds, Lifetimes.split(61))
    }
}
