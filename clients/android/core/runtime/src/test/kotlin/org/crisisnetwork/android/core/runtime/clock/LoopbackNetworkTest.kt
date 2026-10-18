package org.crisisnetwork.android.core.runtime.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackNetworkTest {
    @Test
    fun connectingAnnouncesBothSidesWithTheLinkMtu() = runTest {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        val b = network.endpoint()
        val link = network.connect(a, b, linkMtu = 120)

        assertEquals(TransportEvent.PeerSeen(link.handleA, 120), a.events.first())
        assertEquals(TransportEvent.PeerSeen(link.handleB, 120), b.events.first())
    }

    @Test
    fun packetsArriveWithTheReceiversHandleAndAreCopied() = runTest {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        val b = network.endpoint()
        val link = network.connect(a, b, linkMtu = 100)

        val sent = byteArrayOf(1, 2, 3)
        a.send(link.handleA, sent)
        sent[0] = 99

        val received = b.events.first { it is TransportEvent.PacketReceived } as TransportEvent.PacketReceived
        assertEquals(link.handleB, received.peer)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.bytes)
    }

    @Test
    fun aPacketAboveTheMtuIsDroppedAndCounted() = runTest {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        val b = network.endpoint()
        val link = network.connect(a, b, linkMtu = 100)

        a.send(link.handleA, ByteArray(101))
        a.send(link.handleA, ByteArray(100) { 7 })

        val received = b.events.first { it is TransportEvent.PacketReceived } as TransportEvent.PacketReceived
        assertEquals(100, received.bytes.size)
        assertEquals(1, a.oversizedPacketsDropped)
    }

    @Test
    fun handlesAreNeverReusedAfterAReconnect() = runTest {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        val b = network.endpoint()

        val first = network.connect(a, b)
        network.disconnect(first)
        val second = network.connect(a, b)

        assertNotEquals(first.handleA, second.handleA)
        assertNotEquals(first.handleB, second.handleB)
        val events = a.events.take(3).toList()
        assertEquals(TransportEvent.PeerSeen(first.handleA, LoopbackNetwork.DEFAULT_LINK_MTU), events[0])
        assertEquals(TransportEvent.PeerLost(first.handleA), events[1])
        assertEquals(TransportEvent.PeerSeen(second.handleA, LoopbackNetwork.DEFAULT_LINK_MTU), events[2])
    }

    @Test
    fun disconnectingFromOneSideReportsBothSidesAndStopsDelivery() = runTest {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        val b = network.endpoint()
        val link = network.connect(a, b)

        a.disconnect(link.handleA)
        a.send(link.handleA, byteArrayOf(1))

        val eventsB = b.events.take(2).toList()
        assertTrue(eventsB[0] is TransportEvent.PeerSeen)
        assertEquals(TransportEvent.PeerLost(link.handleB), eventsB[1])
        val eventsA = a.events.take(2).toList()
        assertEquals(TransportEvent.PeerLost(link.handleA), eventsA[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEndpointCannotConnectToItself() {
        val network = LoopbackNetwork()
        val a = network.endpoint()
        network.connect(a, a)
    }
}
