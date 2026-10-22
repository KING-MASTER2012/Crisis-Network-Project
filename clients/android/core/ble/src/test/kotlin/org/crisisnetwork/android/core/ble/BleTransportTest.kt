@file:OptIn(ExperimentalCoroutinesApi::class)

package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.crisisnetwork.android.core.runtime.transport.TransportEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BLE transport's state machine against fakes: no radio, no Android, virtual time. */
class BleTransportTest {
    private fun TestScope.harness(config: BleConfig = BleConfig()) = Harness(this, config)

    private fun TestScope.started(config: BleConfig = BleConfig()): Harness =
        harness(config).also {
            it.transport.start()
            it.settle()
        }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun packet(size: Int, fill: Int = 7) = ByteArray(size) { fill.toByte() }

    // ----- discovery and handles ----------------------------------------------------------------

    @Test
    fun aSightingStartsOneConnectionAndTheLinkIsReportedWhenItIsReady() = runTest {
        val h = started()
        h.scanner.sight("AA")
        h.settle()
        h.scanner.sight("AA") // the scanner repeats itself: no second connection
        h.settle()
        assertEquals(listOf("AA"), h.connector.connects)
        assertTrue("nothing is reported before the link is usable", h.events.isEmpty())

        h.connector.created.single().emit(GattLinkEvent.Ready(185))
        h.settle()
        // The core gets the ATT MTU minus the 3 bytes of ATT overhead.
        assertEquals(TransportEvent.PeerSeen(1uL, 182), h.events.single())
        h.transport.stop()
    }

    @Test
    fun anIncomingLinkIsReportedWhenTheCentralSubscribed() = runTest {
        val h = started()
        val link = h.server.accept("BB")
        h.settle()
        assertTrue(h.events.isEmpty())
        link.emit(GattLinkEvent.Ready(247))
        h.settle()
        assertEquals(TransportEvent.PeerSeen(1uL, 244), h.events.single())
        h.transport.stop()
    }

    @Test
    fun handlesAreNeverReusedAcrossReconnects() = runTest {
        val h = started()
        val first = h.connectClient("AA")
        assertEquals(TransportEvent.PeerSeen(1uL, 182), h.events.last())

        h.transport.disconnect(1uL)
        h.settle()
        assertEquals(TransportEvent.PeerLost(1uL), h.events.last())
        assertEquals(1, first.closeCount)

        advance(BleConfig().reconnectCooldownMs + 1)
        h.connectClient("AA")
        assertEquals(TransportEvent.PeerSeen(2uL, 182), h.events.last())
        h.transport.stop()
    }

    @Test
    fun aFailedConnectionIsLeftAloneForTheCooldown() = runTest {
        val config = BleConfig()
        val h = started(config)
        h.scanner.sight("AA")
        h.settle()
        h.connector.created.single().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
        h.settle()
        assertTrue(h.diagnostics.any { it is BleDiagnostic.ConnectFailed && it.status == 133 })
        assertTrue("a link that never was ready is never reported", h.events.isEmpty())

        h.scanner.sight("AA")
        h.settle()
        assertEquals("still cooling down", 1, h.connector.connects.size)

        advance(config.reconnectCooldownMs + 1)
        h.scanner.sight("AA")
        h.settle()
        assertEquals(2, h.connector.connects.size)
        h.transport.stop()
    }

    @Test
    fun aConnectorThatThrowsDoesNotBreakTheTransport() = runTest {
        val h = started()
        h.connector.failNext = IllegalArgumentException("bad address")
        h.scanner.sight("AA")
        h.settle()
        assertTrue(h.diagnostics.any { it is BleDiagnostic.ConnectFailed })
        h.scanner.sight("CC")
        h.settle()
        assertEquals(listOf("AA", "CC"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun linksBeyondTheLimitAreRefused() = runTest {
        val h = started(BleConfig(maxLinks = 1))
        h.scanner.sight("AA") // a pending outgoing link counts
        h.settle()
        h.scanner.sight("CC")
        h.settle()
        assertEquals("no second connection", listOf("AA"), h.connector.connects)

        val incoming = h.server.accept("BB")
        h.settle()
        assertEquals(1, incoming.closeCount)
        assertTrue(h.diagnostics.any { it is BleDiagnostic.LinkRefused && it.address == "BB" })
        h.transport.stop()
    }

    // ----- MTU and the handshake --------------------------------------------------------------------

    @Test
    fun aLinkWithATooSmallMtuWaitsUntilTheMtuGrows() = runTest {
        val h = started()
        val link = h.server.accept("BB")
        h.settle()
        link.emit(GattLinkEvent.Ready(23)) // 20 bytes: below the core's minimum of 64
        h.settle()
        assertTrue(h.events.isEmpty())

        link.emit(GattLinkEvent.MtuChanged(247))
        h.settle()
        assertEquals(TransportEvent.PeerSeen(1uL, 244), h.events.single())
        h.transport.stop()
    }

    @Test
    fun aLinkThatNeverGetsABigEnoughMtuIsClosedAtTheHandshakeTimeout() = runTest {
        val config = BleConfig()
        val h = started(config)
        val link = h.server.accept("BB")
        h.settle()
        link.emit(GattLinkEvent.Ready(23))
        h.settle()

        advance(config.handshakeTimeoutMs + 1)
        assertEquals(1, link.closeCount)
        assertTrue(h.events.isEmpty())
        assertTrue(h.diagnostics.any { it is BleDiagnostic.MtuTooSmall && it.payloadMtu == 20 })
        h.transport.stop()
    }

    @Test
    fun aLinkThatNeverBecomesReadyIsClosedAtTheHandshakeTimeout() = runTest {
        val config = BleConfig()
        val h = started(config)
        h.scanner.sight("AA")
        h.settle()
        val link = h.connector.created.single()

        advance(config.handshakeTimeoutMs + 1)
        assertEquals(1, link.closeCount)
        assertTrue(h.diagnostics.any { it is BleDiagnostic.HandshakeTimeout })
        assertTrue(h.events.isEmpty())
        h.transport.stop()
    }

    @Test
    fun anMtuChangeAfterwardsIsReportedAgainForTheSameHandle() = runTest {
        val h = started()
        val link = h.connectClient("AA", attMtu = 185)
        link.emit(GattLinkEvent.MtuChanged(247))
        h.settle()
        assertEquals(TransportEvent.PeerSeen(1uL, 244), h.events.last())

        val count = h.events.size
        link.emit(GattLinkEvent.MtuChanged(247)) // unchanged: nothing to tell
        h.settle()
        assertEquals(count, h.events.size)
        h.transport.stop()
    }

    // ----- receiving ------------------------------------------------------------------------------------

    @Test
    fun receivedPacketsReachTheCoreOnceTheLinkIsActive() = runTest {
        val h = started()
        h.scanner.sight("AA")
        h.settle()
        val link = h.connector.created.single()
        link.emit(GattLinkEvent.Received(packet(10))) // before ready: ignored
        h.settle()
        assertTrue(h.events.isEmpty())

        link.emit(GattLinkEvent.Ready(185))
        link.emit(GattLinkEvent.Received(byteArrayOf(1, 2, 3)))
        link.emit(GattLinkEvent.Received(ByteArray(0))) // empty: ignored
        h.settle()
        val received = h.events.filterIsInstance<TransportEvent.PacketReceived>().single()
        assertEquals(1uL, received.peer)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.bytes)
        h.transport.stop()
    }

    @Test
    fun aRemoteDisconnectIsReportedOnce() = runTest {
        val h = started()
        val link = h.connectClient("AA")
        link.emit(GattLinkEvent.Closed(CloseReason.Remote, status = 8))
        link.emit(GattLinkEvent.Closed(CloseReason.Remote, status = 8))
        h.settle()
        assertEquals(1, h.events.count { it == TransportEvent.PeerLost(1uL) })
        assertEquals("the platform already closed it", 0, link.closeCount)
        h.transport.stop()
    }

    // ----- sending -----------------------------------------------------------------------------------------

    @Test
    fun oneSendIsInFlightAtATimeAndOrderIsKept() = runTest {
        val h = started()
        val link = h.connectClient("AA")
        val a = packet(10, 1)
        val b = packet(10, 2)
        val c = packet(10, 3)
        h.transport.send(1uL, a)
        h.transport.send(1uL, b)
        h.transport.send(1uL, c)
        h.settle()
        assertEquals(1, link.sent.size)
        assertArrayEquals(a, link.sent[0])

        link.emit(GattLinkEvent.SendCompleted(true))
        h.settle()
        assertEquals(2, link.sent.size)
        assertArrayEquals(b, link.sent[1])

        link.emit(GattLinkEvent.SendCompleted(true))
        h.settle()
        assertEquals(3, link.sent.size)
        assertArrayEquals(c, link.sent[2])
        h.transport.stop()
    }

    @Test
    fun aBusyPlatformIsAskedAgainShortly() = runTest {
        val config = BleConfig()
        val h = started(config)
        val link = h.connectClient("AA")
        link.refusals = 2
        h.transport.send(1uL, packet(10))
        h.settle()
        assertTrue(link.sent.isEmpty())

        advance(config.sendRetryDelayMs * 2 + 1)
        assertEquals(1, link.sent.size)
        assertEquals(0, link.closeCount)
        h.transport.stop()
    }

    @Test
    fun aPlatformThatKeepsRefusingClosesTheLink() = runTest {
        val config = BleConfig(maxSendAttempts = 3)
        val h = started(config)
        val link = h.connectClient("AA")
        link.refusals = Int.MAX_VALUE
        h.transport.send(1uL, packet(10))
        h.settle()
        advance(config.sendRetryDelayMs * 5)
        assertEquals(1, link.closeCount)
        assertEquals(TransportEvent.PeerLost(1uL), h.events.last())
        h.transport.stop()
    }

    @Test
    fun aSendThatNeverCompletesClosesTheLink() = runTest {
        val config = BleConfig()
        val h = started(config)
        val link = h.connectClient("AA")
        h.transport.send(1uL, packet(10))
        h.settle()
        assertEquals(1, link.sent.size)

        advance(config.sendTimeoutMs + 1)
        assertEquals(1, link.closeCount)
        assertEquals(TransportEvent.PeerLost(1uL), h.events.last())
        h.transport.stop()
    }

    @Test
    fun repeatedSendFailuresCloseTheLink() = runTest {
        val h = started(BleConfig(maxConsecutiveSendFailures = 3))
        val link = h.connectClient("AA")
        repeat(4) { h.transport.send(1uL, packet(10)) }
        h.settle()
        repeat(3) {
            link.emit(GattLinkEvent.SendCompleted(false))
            h.settle()
        }
        assertEquals(1, link.closeCount)
        assertEquals(TransportEvent.PeerLost(1uL), h.events.last())
        assertTrue(h.diagnostics.any { it is BleDiagnostic.PacketDropped && it.reason == DropReason.SendFailed })
        h.transport.stop()
    }

    @Test
    fun aPacketLargerThanTheMtuIsDroppedAndOneThatDoesNotFitTheQueueToo() = runTest {
        val h = started(BleConfig(maxQueuedBytes = 512))
        val link = h.connectClient("AA", attMtu = 185) // carries 182 bytes
        h.transport.send(1uL, packet(183))
        h.settle()
        assertTrue(link.sent.isEmpty())
        assertTrue(h.diagnostics.any { it is BleDiagnostic.PacketDropped && it.reason == DropReason.TooLarge })

        h.transport.send(1uL, packet(182)) // in flight, counts against the limit
        h.transport.send(1uL, packet(182))
        h.transport.send(1uL, packet(182)) // 546 > 512
        h.settle()
        assertTrue(h.diagnostics.any { it is BleDiagnostic.PacketDropped && it.reason == DropReason.QueueFull })
        h.transport.stop()
    }

    @Test
    fun sendingToAnUnknownHandleIsIgnored() = runTest {
        val h = started()
        h.transport.send(99uL, packet(10))
        h.transport.disconnect(99uL)
        h.settle()
        assertTrue(h.events.isEmpty())
        assertTrue(h.diagnostics.none { it is BleDiagnostic.InternalError })
        h.transport.stop()
    }

    @Test
    fun theCountersMeasureTheSendingThroughput() = runTest {
        val h = started()
        val link = h.connectClient("AA")
        h.transport.send(1uL, packet(182))
        h.settle()
        advance(100)
        link.emit(GattLinkEvent.SendCompleted(true))
        h.settle()

        val info = h.transport.linkInfo().single()
        assertEquals(1uL, info.handle)
        assertTrue(info.active)
        assertEquals(182, info.payloadMtu)
        assertEquals(1L, info.metrics.packetsSent)
        assertEquals(182L, info.metrics.bytesSent)
        assertEquals(100L, info.metrics.txBusyMs)
        assertEquals(1_820.0, info.metrics.txBytesPerSecond!!, 0.001)

        h.transport.resetMetrics()
        h.settle()
        assertEquals(0L, h.transport.linkInfo().single().metrics.bytesSent)
        h.transport.stop()
    }

    // ----- lifecycle -----------------------------------------------------------------------------------------

    @Test
    fun theRoleDecidesWhichSidesStart() = runTest {
        val peripheral = started(BleConfig(role = BleRole.Peripheral))
        assertEquals(0, peripheral.scanner.startCount)
        assertEquals(1, peripheral.server.openCount)
        assertEquals(1, peripheral.advertiser.startCount)
        peripheral.transport.stop()

        val central = started(BleConfig(role = BleRole.Central))
        assertEquals(1, central.scanner.startCount)
        assertEquals(0, central.server.openCount)
        assertEquals(0, central.advertiser.startCount)
        central.transport.stop()
    }

    @Test
    fun aPeripheralOnlyTransportNeverConnectsOutwards() = runTest {
        val h = started(BleConfig(role = BleRole.Peripheral))
        h.scanner.sight("AA")
        h.settle()
        assertTrue(h.connector.connects.isEmpty())
        h.transport.stop()
    }

    @Test
    fun aFailedStartCleansUpAndRethrows() = runTest {
        val h = harness()
        h.scanner.failOnStart = IllegalStateException("Bluetooth is off")
        assertThrows(IllegalStateException::class.java) { h.transport.start() }
        h.settle()
        assertEquals(1, h.advertiser.stopCount)
        assertEquals(1, h.server.closeCount)
        assertEquals(1, h.scanner.stopCount)

        // Unusable but harmless afterwards.
        h.transport.send(1uL, packet(10))
        h.transport.stop()
    }

    @Test
    fun theTransportCanBeStartedOnlyOnce() = runTest {
        val h = started()
        assertThrows(IllegalStateException::class.java) { h.transport.start() }
        h.transport.stop()
    }

    @Test
    fun stoppingClosesTheLinksAndReportsThemLost() = runTest {
        val h = started()
        val client = h.connectClient("AA")
        val server = h.server.accept("BB")
        server.emit(GattLinkEvent.Ready(185))
        h.settle()

        h.transport.stop()
        h.settle()
        assertEquals(1, client.closeCount)
        assertEquals(1, server.closeCount)
        assertTrue(h.events.contains(TransportEvent.PeerLost(1uL)))
        assertTrue(h.events.contains(TransportEvent.PeerLost(2uL)))
        assertEquals(1, h.scanner.stopCount)
        assertEquals(1, h.server.closeCount)

        h.transport.stop() // idempotent
        assertEquals(1, h.scanner.stopCount)
        assertTrue(h.transport.linkInfo().isEmpty())
    }

    @Test
    fun platformFailuresAreReportedAsDiagnostics() = runTest {
        val h = started()
        h.scanner.fail(2)
        h.settle()
        assertTrue(h.diagnostics.contains(BleDiagnostic.ScanFailed(2)))
        assertFalse(h.diagnostics.any { it is BleDiagnostic.InternalError })
        h.transport.stop()
    }
}
