package org.crisisnetwork.android.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloValueTest {
    @Test
    fun aValueIsAVersionByteAndABigEndianIdentifier() {
        val bytes = HelloValue.encode(0x0102030405060708L)
        assertArrayEquals(byteArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 8), bytes)
        assertEquals(HelloValue.SIZE, bytes.size)
        assertEquals(0x0102030405060708L, HelloValue.decode(bytes))
    }

    @Test
    fun everyIdentifierSurvivesTheRoundTrip() {
        for (id in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x7FFF_0000_FFFF_0000L)) {
            assertEquals(id, HelloValue.decode(HelloValue.encode(id)))
        }
    }

    @Test
    fun aValueFromALaterVersionIsReadByItsFirstNineBytesOnlyWhenTheVersionIsKnown() {
        assertEquals(5L, HelloValue.decode(HelloValue.encode(5L) + byteArrayOf(9, 9)))
        val other = HelloValue.encode(5L).also { it[0] = 2 }
        assertNull(HelloValue.decode(other))
    }

    @Test
    fun missingOrShortValuesAreNotAHello() {
        assertNull(HelloValue.decode(null))
        assertNull(HelloValue.decode(ByteArray(0)))
        assertNull(HelloValue.decode(HelloValue.encode(5L).copyOf(8)))
    }

    @Test
    fun identifiersAreOrderedAsUnsignedNumbers() {
        assertTrue(HelloValue.compare(1L, 2L) < 0)
        assertTrue(HelloValue.compare(-1L, 2L) > 0) // 0xFFFF... is the largest
        assertEquals(0, HelloValue.compare(7L, 7L))
    }
}
