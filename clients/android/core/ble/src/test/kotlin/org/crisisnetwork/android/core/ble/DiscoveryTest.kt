@file:OptIn(ExperimentalCoroutinesApi::class)

package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.crisisnetwork.android.core.runtime.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase A3: discovery, tie-break, back-off, scan budget, rotation and power, against fakes in virtual time. */
class DiscoveryTest {
    private fun TestScope.started(config: BleConfig = BleConfig(), tokens: List<Long> = listOf(100L)): Harness =
        Harness(this, config, tokens).also {
            it.transport.start()
            it.settle()
        }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun ad(token: Long, canInitiate: Boolean = true) = BleAdvertisement(token, canInitiate)

    // ----- tie-break and token ------------------------------------------------------------------

    @Test
    fun theTokenIsAdvertisedWithTheServiceAndTheRoleFlag() = runTest {
        val h = started(tokens = listOf(1234L))
        val (mode, advertisement) = h.advertiser.starts.single()
        assertEquals(AdvertiseMode.Balanced, mode)
        assertEquals(BleAdvertisement(1234L, canInitiate = true), advertisement)
        h.transport.stop()
    }

    @Test
    fun aPeripheralOnlyDeviceAdvertisesThatItCannotInitiate() = runTest {
        val h = started(BleConfig(role = BleRole.Peripheral))
        assertFalse(h.advertiser.starts.single().second!!.canInitiate)
        h.transport.stop()
    }

    @Test
    fun onlyTheSmallerTokenConnects() = runTest {
        val h = started(tokens = listOf(100L))
        h.scanner.sight("BIG", advertisement = ad(500L))
        h.scanner.sight("SMALL", advertisement = ad(50L))
        h.settle()
        assertEquals("we connect to the larger token and leave the smaller one to connect to us", listOf("BIG"), h.connector.connects)
        assertEquals(1, h.transport.stats()!!.heldByTieBreak)
        h.transport.stop()
    }

    @Test
    fun aPeerWithoutTokenOrThatCannotInitiateIsConnectedTo() = runTest {
        val h = started(BleConfig(maxConcurrentConnects = 2), tokens = listOf(900L))
        h.scanner.sight("OLD") // no token: an older build
        h.scanner.sight("ADV", advertisement = ad(1L, canInitiate = false))
        h.settle()
        assertEquals(listOf("OLD", "ADV"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun aCentralOnlyDeviceAlwaysInitiates() = runTest {
        val h = started(BleConfig(role = BleRole.Central), tokens = listOf(900L))
        h.scanner.sight("AA", advertisement = ad(1L))
        h.settle()
        assertEquals(listOf("AA"), h.connector.connects)
        assertNull("it advertises nothing", h.transport.stats()!!.ownToken)
        h.transport.stop()
    }

    @Test
    fun anIsolatedDeviceEventuallyConnectsToWhoeverItWasLeaving() = runTest {
        val config = BleConfig(candidateTtlMs = 10 * 60_000L, tieBreakFallbackMs = 30_000L)
        val h = started(config, tokens = listOf(100L))
        h.scanner.sight("SMALL", advertisement = ad(50L))
        h.settle()
        assertTrue(h.connector.connects.isEmpty())
        advance(29_000)
        assertTrue(h.connector.connects.isEmpty())
        advance(3_000)
        assertEquals(listOf("SMALL"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun theFallbackDoesNotApplyWhileWeHaveALink() = runTest {
        val config = BleConfig(candidateTtlMs = 10 * 60_000L, tieBreakFallbackMs = 30_000L)
        val h = started(config, tokens = listOf(100L))
        h.connectClient("BIG", attMtu = 185)
        h.scanner.sight("SMALL", advertisement = ad(50L))
        h.settle()
        advance(60_000)
        assertEquals(listOf("BIG"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun theTokenRotatesAndAdvertisingRestartsWithTheNewOne() = runTest {
        val config = BleConfig(tokenRotationMs = 60_000L)
        val h = started(config, tokens = listOf(1L, 2L, 3L))
        assertEquals(1, h.advertiser.starts.size)
        advance(61_000)
        assertEquals(2, h.advertiser.starts.size)
        assertEquals(2L, h.advertiser.starts.last().second!!.token)
        assertTrue(h.diagnostics.contains(BleDiagnostic.TokenRotated))
        assertEquals(1L, h.transport.stats()!!.tokenRotations)
        h.transport.stop()
    }

    @Test
    fun withoutRoomForTheTokenWeAdvertiseWithoutItAndInitiate() = runTest {
        val h = started(tokens = listOf(900L))
        h.advertiser.fail(1) // DATA_TOO_LARGE
        h.settle()
        assertNull(h.advertiser.starts.last().second)
        h.scanner.sight("AA", advertisement = ad(1L))
        h.settle()
        assertEquals(listOf("AA"), h.connector.connects)
        h.transport.stop()
    }

    // ----- connecting politely --------------------------------------------------------------------

    @Test
    fun connectionsAreOpenedOneAtATime() = runTest {
        val h = started()
        h.scanner.sight("AA")
        h.scanner.sight("BB")
        h.settle()
        assertEquals(listOf("AA"), h.connector.connects)
        h.connector.created.single().emit(GattLinkEvent.Ready(185))
        h.settle()
        assertEquals(listOf("AA", "BB"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun theNextConnectionWaitsForTheSpacing() = runTest {
        val h = started(BleConfig(connectSpacingMs = 300L))
        h.scanner.sight("AA")
        h.scanner.sight("BB")
        h.settle()
        h.connector.created.single().emit(GattLinkEvent.Ready(185))
        h.settle()
        assertEquals(listOf("AA"), h.connector.connects)
        advance(301)
        assertEquals(listOf("AA", "BB"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun anAddressThatKeepsFailingWaitsLongerEveryTime() = runTest {
        val config = BleConfig(reconnectCooldownMs = 1_000L, stackRecoveryThreshold = 100)
        val h = started(config)

        fun failOnce() {
            h.scanner.sight("AA")
            h.settle()
            h.connector.created.last().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
            h.settle()
        }

        failOnce() // 1st failure: 1 s
        advance(1_001)
        failOnce() // 2nd failure: 2 s
        advance(1_500)
        h.scanner.sight("AA")
        h.settle()
        assertEquals("still backing off", 2, h.connector.connects.size)
        advance(600)
        h.scanner.sight("AA")
        h.settle()
        assertEquals(3, h.connector.connects.size)

        val delays = h.diagnostics.filterIsInstance<BleDiagnostic.ConnectBackoff>().map { it.delayMs }
        assertEquals(listOf(1_000L, 2_000L), delays)
        h.transport.stop()
    }

    @Test
    fun theBackOffIsCapped() = runTest {
        val config = BleConfig(reconnectCooldownMs = 10_000L, maxBackoffMs = 25_000L, stackRecoveryThreshold = 100)
        val h = started(config)
        repeat(4) {
            advance(30_000)
            h.scanner.sight("AA")
            h.settle()
            h.connector.created.last().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
            h.settle()
        }
        val delays = h.diagnostics.filterIsInstance<BleDiagnostic.ConnectBackoff>().map { it.delayMs }
        assertEquals(listOf(10_000L, 20_000L, 25_000L, 25_000L), delays)
        h.transport.stop()
    }

    @Test
    fun aLinkThatLivedLongEnoughStartsFromACleanSlate() = runTest {
        val config = BleConfig(reconnectCooldownMs = 1_000L, stableLinkMs = 20_000L, stackRecoveryThreshold = 100)
        val h = started(config)
        val link = h.connectClient("AA")
        advance(25_000)
        link.emit(GattLinkEvent.Closed(CloseReason.Remote, status = 8))
        h.settle()
        advance(1_001)
        h.scanner.sight("AA")
        h.settle()
        assertEquals(2, h.connector.connects.size)
        assertTrue(h.diagnostics.none { it is BleDiagnostic.ConnectBackoff })
        h.transport.stop()
    }

    @Test
    fun aLinkThatFlapsCountsAsAFailure() = runTest {
        val config = BleConfig(reconnectCooldownMs = 1_000L, stableLinkMs = 20_000L, stackRecoveryThreshold = 100)
        val h = started(config)
        val link = h.connectClient("AA")
        advance(2_000)
        link.emit(GattLinkEvent.Closed(CloseReason.Remote, status = 8))
        h.settle()
        val backoff = h.diagnostics.filterIsInstance<BleDiagnostic.ConnectBackoff>().single()
        assertEquals("AA", backoff.address)
        assertEquals(1, backoff.failures)
        h.transport.stop()
    }

    @Test
    fun aDeviceWithoutOurServiceIsLeftAloneForLong() = runTest {
        val config = BleConfig(reconnectCooldownMs = 1_000L, setupFailedBackoffMs = 30_000L)
        val h = started(config)
        h.scanner.sight("AA")
        h.settle()
        h.connector.created.single().emit(GattLinkEvent.Closed(CloseReason.SetupFailed))
        h.settle()
        advance(20_000)
        h.scanner.sight("AA")
        h.settle()
        assertEquals(1, h.connector.connects.size)
        advance(11_000)
        h.scanner.sight("AA")
        h.settle()
        assertEquals(2, h.connector.connects.size)
        h.transport.stop()
    }

    @Test
    fun severalFailuresInARowPauseAllNewConnections() = runTest {
        val config = BleConfig(stackRecoveryThreshold = 2, stackRecoveryPauseMs = 5_000L)
        val h = started(config)
        for (address in listOf("AA", "BB")) {
            h.scanner.sight(address)
            h.settle()
            h.connector.created.last().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
            h.settle()
        }
        assertTrue(h.diagnostics.contains(BleDiagnostic.ConnectionsPaused(5_000L)))
        h.scanner.sight("CC")
        h.settle()
        assertEquals(listOf("AA", "BB"), h.connector.connects)
        advance(5_100)
        assertEquals("the waiting device is connected to when the pause ends", listOf("AA", "BB", "CC"), h.connector.connects)
        h.transport.stop()
    }

    @Test
    fun aSuccessfulConnectionClearsTheFailureStreak() = runTest {
        val config = BleConfig(stackRecoveryThreshold = 2, stackRecoveryPauseMs = 5_000L)
        val h = started(config)
        h.scanner.sight("AA")
        h.settle()
        h.connector.created.last().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
        h.settle()
        h.connectClient("BB")
        h.scanner.sight("CC")
        h.settle()
        h.connector.created.last { it.address == "CC" }.emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
        h.settle()
        assertTrue(h.diagnostics.none { it is BleDiagnostic.ConnectionsPaused })
        h.transport.stop()
    }

    // ----- scan budget and power ----------------------------------------------------------------------

    @Test
    fun theScanModeFollowsTheScreenAndTheProfile() = runTest {
        val h = started()
        assertEquals(listOf(ScanMode.Balanced), h.scanner.modes)

        h.transport.setScreenOn(false)
        h.settle()
        assertEquals(ScanMode.LowPower, h.scanner.modes.last())

        h.transport.setPowerProfile(PowerProfile.Aggressive)
        h.settle()
        assertEquals("screen off: aggressive scans in the middle mode", ScanMode.Balanced, h.scanner.modes.last())

        h.transport.setScreenOn(true)
        h.settle()
        assertEquals(ScanMode.LowLatency, h.scanner.modes.last())
        val stats = h.transport.stats()!!
        assertEquals(PowerProfile.Aggressive, stats.profile)
        assertTrue(stats.screenOn)
        h.transport.stop()
    }

    @Test
    fun anUnchangedModeDoesNotRestartTheScan() = runTest {
        val h = started()
        h.transport.setScreenOn(true)
        h.transport.setPowerProfile(PowerProfile.Balanced)
        h.settle()
        assertEquals(1, h.scanner.startCount)
        h.transport.stop()
    }

    @Test
    fun scanRestartsStayWithinThePlatformBudget() = runTest {
        val h = started(BleConfig(maxScanStartsPer30s = 2))
        h.transport.setScreenOn(false) // 2nd start
        h.settle()
        h.transport.setScreenOn(true) // would be the 3rd within 30 s: held back
        h.settle()
        assertEquals(2, h.scanner.startCount)
        assertTrue(h.diagnostics.any { it is BleDiagnostic.ScanBlocked })

        advance(34_000)
        assertEquals("the restart happens once the window has passed", 3, h.scanner.startCount)
        assertEquals(ScanMode.Balanced, h.scanner.modes.last())
        h.transport.stop()
    }

    @Test
    fun aScanThatFailedForRunningTooOftenIsRestartedAfterThirtySeconds() = runTest {
        val h = started()
        h.scanner.fail(6) // SCAN_FAILED_SCANNING_TOO_FREQUENTLY
        h.settle()
        advance(20_000)
        assertEquals(1, h.scanner.startCount)
        advance(11_000)
        assertEquals(2, h.scanner.startCount)
        assertTrue(h.transport.stats()!!.scanning)
        h.transport.stop()
    }

    @Test
    fun otherScanFailuresBackOffAndRetry() = runTest {
        val h = started(BleConfig(scanFailureBackoffMs = 5_000L))
        h.scanner.fail(2)
        h.settle()
        advance(3_000)
        assertEquals(1, h.scanner.startCount)
        advance(3_000)
        assertEquals(2, h.scanner.startCount)
        h.transport.stop()
    }

    @Test
    fun aScanThatRanForTooLongIsRefreshed() = runTest {
        val h = started(BleConfig(scanRefreshMs = 60_000L))
        advance(59_000)
        assertEquals(1, h.scanner.startCount)
        advance(3_000)
        assertEquals(2, h.scanner.startCount)
        h.transport.stop()
    }

    @Test
    fun theLinkPriorityFollowsTheProfileAndTheScreen() = runTest {
        val h = started()
        val link = h.connectClient("AA")
        assertEquals(listOf(LinkPriority.Balanced), link.priorities)

        h.transport.setScreenOn(false)
        h.settle()
        assertEquals(LinkPriority.LowPower, link.priorities.last())

        h.transport.setPowerProfile(PowerProfile.Aggressive)
        h.settle()
        assertEquals(LinkPriority.Balanced, link.priorities.last())
        h.transport.stop()
    }

    @Test
    fun theAdvertiseModeFollowsTheProfile() = runTest {
        val h = started()
        h.transport.setPowerProfile(PowerProfile.Saver)
        h.settle()
        assertEquals(AdvertiseMode.LowPower, h.advertiser.starts.last().first)
        assertEquals("the token does not change with the profile", h.advertiser.starts.first().second, h.advertiser.starts.last().second)
        h.transport.stop()
    }

    @Test
    fun theProfileLimitsTheLinksAndOneSlotStaysFreeForIncomingOnes() = runTest {
        val h = started(BleConfig(profile = PowerProfile.Saver)) // 3 links, 2 of them outgoing
        h.connectClient("AA")
        h.connectClient("BB")
        h.scanner.sight("CC")
        h.settle()
        assertEquals(listOf("AA", "BB"), h.connector.connects)

        val incoming = h.server.accept("DD")
        h.settle()
        incoming.emit(GattLinkEvent.Ready(185))
        h.settle()
        assertEquals(TransportEvent.PeerSeen(3uL, 182), h.events.last())

        h.server.accept("EE")
        h.settle()
        assertTrue(h.diagnostics.any { it is BleDiagnostic.LinkRefused && it.address == "EE" })
        h.transport.stop()
    }

    // ----- rotation ------------------------------------------------------------------------------------------

    @Test
    fun aSaturatedNodeRotatesItsOldestOutgoingLinkForAWaitingNeighbour() = runTest {
        val config = BleConfig(maxLinks = 2, candidateTtlMs = 60 * 60_000L)
        val h = started(config)
        val first = h.connectClient("AA")
        advance(1_000)
        val second = h.connectClient("BB")
        h.scanner.sight("CC")
        h.settle()
        assertEquals(listOf("AA", "BB"), h.connector.connects)

        advance(PowerProfile.Balanced.tuning.rotationIntervalMs)
        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)
        assertEquals(listOf("AA", "BB", "CC"), h.connector.connects)
        assertTrue(h.diagnostics.contains(BleDiagnostic.LinkRotated("AA")))
        assertEquals(1L, h.transport.stats()!!.linkRotations)
        h.transport.stop()
    }

    @Test
    fun withFreeCapacityNothingIsRotated() = runTest {
        val h = started()
        val link = h.connectClient("AA")
        advance(PowerProfile.Balanced.tuning.rotationIntervalMs * 2)
        assertEquals(0, link.closeCount)
        assertEquals(0L, h.transport.stats()!!.linkRotations)
        h.transport.stop()
    }

    @Test
    fun withNobodyWaitingNothingIsRotated() = runTest {
        val h = started(BleConfig(maxLinks = 2))
        val first = h.connectClient("AA")
        h.connectClient("BB")
        advance(PowerProfile.Balanced.tuning.rotationIntervalMs * 2)
        assertEquals(0, first.closeCount)
        h.transport.stop()
    }

    @Test
    fun aLinkThatTheOtherSideOpenedIsNeverRotatedAway() = runTest {
        val config = BleConfig(maxLinks = 2, candidateTtlMs = 60 * 60_000L)
        val h = started(config)
        val incoming = h.server.accept("IN")
        h.settle()
        incoming.emit(GattLinkEvent.Ready(185))
        h.settle()
        val outgoing = h.connectClient("OUT")
        h.scanner.sight("WAITING")
        h.settle()
        advance(PowerProfile.Balanced.tuning.rotationIntervalMs)
        assertEquals(0, incoming.closeCount)
        assertEquals(1, outgoing.closeCount)
        h.transport.stop()
    }

    @Test
    fun aRotatedAwayAddressIsNotReconnectedAtOnce() = runTest {
        val config = BleConfig(maxLinks = 1, candidateTtlMs = 60 * 60_000L, rotationPauseMs = 120_000L)
        val h = started(config)
        h.connectClient("AA")
        h.scanner.sight("BB")
        h.settle()
        advance(PowerProfile.Balanced.tuning.rotationIntervalMs)
        assertEquals(listOf("AA", "BB"), h.connector.connects)
        h.scanner.sight("AA")
        h.settle()
        assertEquals("AA waits for its pause", 2, h.connector.connects.size)
        h.transport.stop()
    }

    @Test
    fun theStatsCountWhatHappened() = runTest {
        val h = started()
        h.scanner.sight("AA")
        h.settle()
        h.connector.created.single().emit(GattLinkEvent.Closed(CloseReason.ConnectFailed, status = 133))
        h.settle()
        val stats = h.transport.stats()!!
        assertEquals(1L, stats.connectAttempts)
        assertEquals(1L, stats.connectFailures)
        assertEquals(1, stats.backedOff)
        assertEquals(1L, stats.scanStarts)
        assertTrue(stats.scanning)
        assertEquals(ScanMode.Balanced, stats.scanMode)
        assertEquals(Math.min(PowerProfile.Balanced.tuning.maxLinks, BleConfig().maxLinks), stats.effectiveMaxLinks)
        h.transport.stop()
        assertNull("no snapshots once stopped", h.transport.stats())
    }
}
