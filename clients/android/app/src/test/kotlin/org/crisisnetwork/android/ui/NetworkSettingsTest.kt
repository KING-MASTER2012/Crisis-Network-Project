package org.crisisnetwork.android.ui

import org.crisisnetwork.android.messaging.Lifetimes
import org.crisisnetwork.android.settings.DefaultLifetimes
import org.crisisnetwork.android.settings.StorageLimits
import org.crisisnetwork.android.ui.network.ByteSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NetworkSettingsTest {
    @Test
    fun theDefaultStoreEqualsTheCoreDefault() {
        assertEquals(32L * 1024 * 1024, StorageLimits.bytesOf(StorageLimits.DEFAULT_MB))
        // The core's default pins 8 of 32 MB for others: a quarter, whatever size is chosen.
        assertEquals(8L * 1024 * 1024, StorageLimits.inboundPinnedBytes(StorageLimits.DEFAULT_MB))
        for (mb in StorageLimits.presetsMb) assertEquals(StorageLimits.bytesOf(mb) / 4, StorageLimits.inboundPinnedBytes(mb))
    }

    @Test
    fun aStoredSizeThatIsNotAChoiceBecomesTheDefault() {
        assertEquals(64, StorageLimits.coerce(64))
        assertEquals(StorageLimits.DEFAULT_MB, StorageLimits.coerce(0))
        assertEquals(StorageLimits.DEFAULT_MB, StorageLimits.coerce(-5))
        assertEquals(StorageLimits.DEFAULT_MB, StorageLimits.coerce(33))
        assertTrue(StorageLimits.DEFAULT_MB in StorageLimits.presetsMb)
    }

    @Test
    fun theDefaultLifetimeIsAlwaysOneOfTheComposerPresets() {
        assertEquals(3_600L, DefaultLifetimes.coerce(3_600L))
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(1L))
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(Long.MAX_VALUE))
        // A protocol maximum below a preset removes that preset from the choices.
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(604_800L, maxSecs = 86_400L))
        assertTrue(DefaultLifetimes.options().all { it in Lifetimes.presetSeconds })
    }

    @Test
    fun sizesAreShownWithTheRightUnit() {
        val l = Locale.ROOT
        assertEquals("0 B", ByteSizes.format(-3, l))
        assertEquals("512 B", ByteSizes.format(512, l))
        assertEquals("1.0 KB", ByteSizes.format(1024, l))
        assertEquals("1.5 KB", ByteSizes.format(1536, l))
        assertEquals("32.0 MB", ByteSizes.format(32L * 1024 * 1024, l))
        assertEquals("256 MB", ByteSizes.format(256L * 1024 * 1024, l))
        // Never "1024.0 KB": that moves up to the next unit.
        assertEquals("1.0 MB", ByteSizes.format(1024L * 1024 - 1, l))
    }

    @Test
    fun theFillOfTheStoreStaysBetweenZeroAndOne() {
        assertEquals(0f, ByteSizes.fraction(10, 0), 0f)
        assertEquals(0f, ByteSizes.fraction(-10, 100), 0f)
        assertEquals(0.5f, ByteSizes.fraction(50, 100), 0.0001f)
        assertEquals(1f, ByteSizes.fraction(500, 100), 0f)
    }

    @Test
    fun countersUseTheDigitGroupingOfTheLocale() {
        assertEquals("1,234", ByteSizes.count(1234L, Locale.US))
        assertEquals("1.234", ByteSizes.count(1234UL, Locale.GERMANY))
        // A counter beyond the range of a Long is saturated, not wrapped to a negative number.
        assertEquals(ByteSizes.count(Long.MAX_VALUE, Locale.US), ByteSizes.count(ULong.MAX_VALUE, Locale.US))
    }
}
