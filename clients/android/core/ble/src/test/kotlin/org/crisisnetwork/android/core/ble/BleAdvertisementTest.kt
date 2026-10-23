package org.crisisnetwork.android.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvertisementTest {
    @Test
    fun theLayoutIsOneFlagByteAndAFourByteBigEndianToken() {
        val bytes = BleAdvertisement(token = 0x01020304L, canInitiate = true).encode()
        assertArrayEquals(byteArrayOf(0x11, 0x01, 0x02, 0x03, 0x04), bytes)
        assertEquals(BleAdvertisement.SIZE, bytes.size)
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0), BleAdvertisement(0L, canInitiate = false).encode())
    }

    @Test
    fun encodingAndDecodingAreInverse() {
        for (token in listOf(0L, 1L, 0x7FFFFFFFL, 0x80000000L, BleAdvertisement.MAX_TOKEN)) {
            for (canInitiate in listOf(true, false)) {
                val original = BleAdvertisement(token, canInitiate)
                assertEquals(original, BleAdvertisement.decode(original.encode()))
            }
        }
    }

    @Test
    fun tokensAboveThirtyTwoBitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BleAdvertisement(BleAdvertisement.MAX_TOKEN + 1, true) }
        assertThrows(IllegalArgumentException::class.java) { BleAdvertisement(-1L, true) }
    }

    @Test
    fun anythingThatIsNotOurFormatDecodesToNull() {
        assertNull(BleAdvertisement.decode(null))
        assertNull(BleAdvertisement.decode(byteArrayOf()))
        assertNull(BleAdvertisement.decode(byteArrayOf(0x11, 1, 2, 3))) // too short
        assertNull(BleAdvertisement.decode(byteArrayOf(0x12, 1, 2, 3, 4))) // another version
    }

    @Test
    fun reservedBitsAndTrailingBytesAreIgnored() {
        val decoded = BleAdvertisement.decode(byteArrayOf(0xF1.toByte(), 0, 0, 0, 9, 42, 42))
        assertEquals(BleAdvertisement(9L, canInitiate = true), decoded)
    }

    @Test
    fun theSmallerTokenInitiates() {
        val peer = BleAdvertisement(500L, canInitiate = true)
        assertTrue(TieBreak.shouldInitiate(ownToken = 100L, peer = peer))
        assertFalse(TieBreak.shouldInitiate(ownToken = 900L, peer = peer))
        assertFalse("equal tokens: both wait for the next rotation", TieBreak.shouldInitiate(ownToken = 500L, peer = peer))
    }

    @Test
    fun theComparisonIsUnsigned() {
        val peer = BleAdvertisement(BleAdvertisement.MAX_TOKEN, canInitiate = true)
        assertTrue(TieBreak.shouldInitiate(ownToken = 0x80000000L, peer = peer))
        assertFalse(TieBreak.shouldInitiate(ownToken = BleAdvertisement.MAX_TOKEN, peer = BleAdvertisement(0x80000000L, true)))
    }

    @Test
    fun whenThePeerCannotDecideWeInitiate() {
        assertTrue("no token seen", TieBreak.shouldInitiate(ownToken = 900L, peer = null))
        assertTrue("the peer only advertises", TieBreak.shouldInitiate(ownToken = 900L, peer = BleAdvertisement(1L, canInitiate = false)))
        assertTrue("we advertise no token: the peer cannot see us", TieBreak.shouldInitiate(ownToken = null, peer = BleAdvertisement(1L, true)))
    }

    @Test
    fun theSecureTokenSourceStaysWithinThirtyTwoBits() {
        repeat(200) {
            val token = TokenSources.secure()
            assertTrue(token in 0L..BleAdvertisement.MAX_TOKEN)
        }
    }

    @Test
    fun theProfilesAreOrderedByAppetite() {
        val saver = PowerProfile.Saver.tuning
        val balanced = PowerProfile.Balanced.tuning
        val aggressive = PowerProfile.Aggressive.tuning
        assertTrue(saver.maxLinks < balanced.maxLinks && balanced.maxLinks < aggressive.maxLinks)
        assertEquals(ScanMode.LowPower, saver.scanMode)
        assertEquals(ScanMode.LowLatency, aggressive.scanMode)
        for (profile in PowerProfile.entries) {
            assertTrue("a relay needs two links at least", profile.tuning.maxLinks >= 3)
            assertTrue("the screen off never scans harder than the screen on", profile.tuning.screenOffScanMode.ordinal <= profile.tuning.scanMode.ordinal)
        }
    }
}
