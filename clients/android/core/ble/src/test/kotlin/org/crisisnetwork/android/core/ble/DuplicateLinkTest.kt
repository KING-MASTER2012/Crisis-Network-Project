@file:OptIn(ExperimentalCoroutinesApi::class)

package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.crisisnetwork.android.core.runtime.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE profile 1.1: two phones that connected to each other, one in each direction, notice it through the
 * HELLO identifiers and keep one link (the one whose client has the lower identifier).
 */
class DuplicateLinkTest {
    private companion object {
        const val OWN = 0x20L
        const val PEER = 0x50L
    }

    private fun TestScope.started(config: BleConfig = BleConfig(), hellos: List<Long> = listOf(OWN)): Harness =
        Harness(this, config, hellos = hellos).also {
            it.transport.start()
            it.settle()
        }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    /** We opened a link to [address] and the neighbour told [peer]; then it connected to us as well. */
    private fun TestScope.bothWays(h: Harness, peer: Long): Pair<FakeGattLink, FakeGattLink> {
        val client = h.connectClient("AA")
        client.emit(GattLinkEvent.PeerHello(peer))
        h.settle()
        val server = h.server.accept("BB")
        server.emit(GattLinkEvent.Ready(185))
        server.emit(GattLinkEvent.PeerHello(peer))
        h.settle()
        return client to server
    }

    @Test
    fun theTransportShowsItsIdentifierToBothPorts() = runTest {
        val h = started()
        assertEquals(listOf(OWN), h.connector.helloIds)
        assertEquals(listOf(OWN), h.server.helloIds)
        h.transport.stop()
    }

    @Test
    fun theLinkOfTheLowerClientStays_ours() = runTest {
        val h = started() // our identifier is lower than the neighbour's: our client link stays
        val (client, server) = bothWays(h, PEER)

        assertEquals("the link we opened stays", 0, client.closeCount)
        assertEquals("the link it opened is closed", 1, server.closeCount)
        assertTrue(h.events.contains(TransportEvent.PeerLost(2uL)))
        assertFalse(h.events.contains(TransportEvent.PeerLost(1uL)))
        assertTrue(h.diagnostics.contains(BleDiagnostic.DuplicateLink("BB", LinkRole.Server)))
        assertEquals(1L, h.transport.stats()!!.duplicateLinks)
        h.transport.stop()
    }

    @Test
    fun theLinkOfTheLowerClientStays_theirs() = runTest {
        val h = started(hellos = listOf(PEER)) // the neighbour's identifier (0x20) is the lower one now
        val (client, server) = bothWays(h, OWN)

        assertEquals("the link we opened is closed", 1, client.closeCount)
        assertEquals("the link it opened stays", 0, server.closeCount)
        assertTrue(h.events.contains(TransportEvent.PeerLost(1uL)))
        assertTrue(h.diagnostics.contains(BleDiagnostic.DuplicateLink("AA", LinkRole.Client)))
        h.transport.stop()
    }

    @Test
    fun theDecisionDoesNotDependOnWhichHelloArrivesLast() = runTest {
        val h = started(hellos = listOf(PEER))
        val server = h.server.accept("BB")
        server.emit(GattLinkEvent.Ready(185))
        server.emit(GattLinkEvent.PeerHello(OWN))
        h.settle()
        val client = h.connectClient("AA")
        client.emit(GattLinkEvent.PeerHello(OWN))
        h.settle()

        assertEquals(1, client.closeCount)
        assertEquals(0, server.closeCount)
        h.transport.stop()
    }

    @Test
    fun aClosedClientLinkIsNotOpenedAgainRightAway() = runTest {
        val config = BleConfig(duplicateCooldownMs = 60_000L)
        val h = started(config, hellos = listOf(PEER))
        bothWays(h, OWN)
        assertEquals(listOf("AA"), h.connector.connects)

        h.scanner.sight("AA")
        h.settle()
        advance(30_000)
        h.scanner.sight("AA")
        h.settle()
        assertEquals("still paused", listOf("AA"), h.connector.connects)

        advance(31_000)
        h.scanner.sight("AA")
        h.settle()
        assertEquals("the pause is over", listOf("AA", "AA"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun differentNeighboursAreLeftAlone() = runTest {
        val h = started()
        val client = h.connectClient("AA")
        client.emit(GattLinkEvent.PeerHello(PEER))
        val server = h.server.accept("BB")
        server.emit(GattLinkEvent.Ready(185))
        server.emit(GattLinkEvent.PeerHello(PEER + 1))
        h.settle()

        assertEquals(0, client.closeCount)
        assertEquals(0, server.closeCount)
        assertEquals(0L, h.transport.stats()!!.duplicateLinks)
        h.transport.stop()
    }

    @Test
    fun aNeighbourWithoutAHelloIsLeftAlone() = runTest {
        val h = started()
        val client = h.connectClient("AA")
        val server = h.server.accept("BB")
        server.emit(GattLinkEvent.Ready(185))
        client.emit(GattLinkEvent.PeerHello(PEER))
        h.settle()

        assertEquals(0, client.closeCount)
        assertEquals(0, server.closeCount)
        h.transport.stop()
    }

    @Test
    fun theServerSideNeverClosesTheLinkOfTheSameClient() = runTest {
        // Two links of one client (its address changed in between): the client closes the extra one itself.
        val h = started()
        val first = h.server.accept("BB")
        first.emit(GattLinkEvent.Ready(185))
        first.emit(GattLinkEvent.PeerHello(PEER))
        val second = h.server.accept("CC")
        second.emit(GattLinkEvent.Ready(185))
        second.emit(GattLinkEvent.PeerHello(PEER))
        h.settle()

        assertEquals(0, first.closeCount)
        assertEquals(0, second.closeCount)
        h.transport.stop()
    }

    @Test
    fun theIdentifierChangesWithTheTokenAndBothPortsAreTold() = runTest {
        val config = BleConfig(tokenRotationMs = 10_000L)
        val h = started(config, hellos = listOf(1L, 2L))
        advance(11_000)

        assertEquals(listOf(1L, 2L), h.connector.helloIds)
        assertEquals(listOf(1L, 2L), h.server.helloIds)
        h.transport.stop()
    }
}
