package org.crisisnetwork.android.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BleConfigTest {
    @Test
    fun theCarriedPacketIsTheAttMtuMinusThree() {
        assertEquals(20, BleConfig.payloadForAttMtu(23))
        assertEquals(182, BleConfig.payloadForAttMtu(185))
        assertEquals(244, BleConfig.payloadForAttMtu(247))
    }

    @Test
    fun theCarriedPacketNeverExceedsTheLargestAttributeValue() {
        assertEquals(512, BleConfig.payloadForAttMtu(517))
        assertEquals(512, BleConfig.payloadForAttMtu(1_000))
        assertEquals(0, BleConfig.payloadForAttMtu(2))
    }

    @Test
    fun theQueueMustHoldAtLeastOneMaximumPacket() {
        assertThrows(IllegalArgumentException::class.java) { BleConfig(maxQueuedBytes = 100) }
    }

    @Test
    fun metricsDeriveTheirThroughput() {
        val tx = LinkMetrics(bytesSent = 1_000, txBusyMs = 500)
        assertEquals(2_000.0, tx.txBytesPerSecond!!, 0.001)
        assertNull(LinkMetrics().txBytesPerSecond)

        val rx = LinkMetrics(packetsReceived = 3, bytesReceived = 600, firstReceiveMs = 1_000, lastReceiveMs = 1_300)
        assertEquals(2_000.0, rx.rxBytesPerSecond!!, 0.001)
        assertNull(LinkMetrics(packetsReceived = 1, bytesReceived = 100, firstReceiveMs = 5, lastReceiveMs = 5).rxBytesPerSecond)
    }
}
