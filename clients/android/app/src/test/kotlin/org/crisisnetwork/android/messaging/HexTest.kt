package org.crisisnetwork.android.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexTest {
    @Test
    fun hexRoundTrips() {
        val bytes = byteArrayOf(0, 1, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("00017f80ff", Hex.encode(bytes))
        assertEquals(bytes.toList(), Hex.decode("00017F80FF")!!.toList())
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
    }

    @Test
    fun theDefaultLimitsEqualTheProtocol() {
        assertEquals(1024, Limits().maxPayloadBytes)
        assertEquals(604_800L, Limits().maxLifetimeSecs)
    }
}
