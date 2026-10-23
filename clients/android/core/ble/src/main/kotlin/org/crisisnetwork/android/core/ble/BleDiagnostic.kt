package org.crisisnetwork.android.core.ble

/** Things that went wrong or are worth knowing, for the log and for the diagnostics screen. */
sealed interface BleDiagnostic {
    data class ScanFailed(val code: Int) : BleDiagnostic

    data object AdvertiseStarted : BleDiagnostic

    data class AdvertiseFailed(val code: Int) : BleDiagnostic

    data object ServerReady : BleDiagnostic

    data class ServerFailed(val status: Int) : BleDiagnostic

    /** An outgoing connection did not come up. */
    data class ConnectFailed(val address: String, val reason: CloseReason, val status: Int?) : BleDiagnostic

    /** A link was refused because [BleConfig.maxLinks] was reached. */
    data class LinkRefused(val address: String, val role: LinkRole) : BleDiagnostic

    /** A link never became usable within [BleConfig.handshakeTimeoutMs]. */
    data class HandshakeTimeout(val address: String, val role: LinkRole, val payloadMtu: Int) : BleDiagnostic

    /** A link was closed because it cannot carry the smallest packet the core accepts. */
    data class MtuTooSmall(val address: String, val payloadMtu: Int) : BleDiagnostic

    /** A usable link (reported to the core under [handle]). */
    data class LinkUp(val address: String, val role: LinkRole, val handle: ULong, val payloadMtu: Int) : BleDiagnostic

    data class LinkDown(val address: String, val role: LinkRole, val handle: ULong?, val reason: String) : BleDiagnostic

    /** A packet was dropped. */
    data class PacketDropped(val handle: ULong, val reason: DropReason) : BleDiagnostic

    /** The scan was (re)started in [mode]. */
    data class ScanStarted(val mode: ScanMode) : BleDiagnostic

    /** A scan start was held back for [retryInMs] (platform limit of 5 starts per 30 s, or a failure). */
    data class ScanBlocked(val retryInMs: Long) : BleDiagnostic

    /** An address that failed [failures] times in a row is left alone for [delayMs]. */
    data class ConnectBackoff(val address: String, val delayMs: Long, val failures: Int) : BleDiagnostic

    /** Several connection attempts in a row failed: no new attempt for [forMs], to let the stack recover. */
    data class ConnectionsPaused(val forMs: Long) : BleDiagnostic

    /** The oldest outgoing link was closed to make room for a waiting neighbour. */
    data class LinkRotated(val address: String) : BleDiagnostic

    /** The advertised token was replaced. */
    data object TokenRotated : BleDiagnostic

    /** The power profile or the screen state changed. */
    data class PowerChanged(val profile: PowerProfile, val screenOn: Boolean) : BleDiagnostic

    /** A bug or an unexpected platform exception inside the transport; the transport carries on. */
    data class InternalError(val message: String) : BleDiagnostic
}

enum class DropReason { QueueFull, TooLarge, SendFailed }

/** Counters of one link. */
data class LinkMetrics(
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesReceived: Long = 0,
    val packetsDropped: Long = 0,
    /** Time the link spent with a send in progress; bytesSent / txBusyMs is the sending throughput. */
    val txBusyMs: Long = 0,
    /** Receiving window: first and last packet, in transport clock milliseconds. */
    val firstReceiveMs: Long? = null,
    val lastReceiveMs: Long? = null,
) {
    /** Bytes per second while sending, or `null` before anything was sent. */
    val txBytesPerSecond: Double?
        get() = if (txBusyMs > 0) bytesSent * 1000.0 / txBusyMs else null

    /** Bytes per second between the first and the last received packet, or `null` with fewer than two packets. */
    val rxBytesPerSecond: Double?
        get() {
            val first = firstReceiveMs ?: return null
            val last = lastReceiveMs ?: return null
            return if (packetsReceived >= 2 && last > first) bytesReceived * 1000.0 / (last - first) else null
        }
}

/** A snapshot of one link, for the diagnostics screen. */
data class LinkInfo(
    val handle: ULong?,
    val role: LinkRole,
    val address: String,
    val active: Boolean,
    /** Largest packet the link carries (ATT MTU - 3), 0 while unknown. */
    val payloadMtu: Int,
    val queuedBytes: Int,
    val metrics: LinkMetrics,
)

/** A snapshot of the discovery and power state, for the diagnostics screen and the battery measurements. */
data class BleStats(
    val profile: PowerProfile,
    val screenOn: Boolean,
    val scanning: Boolean,
    val scanMode: ScanMode?,
    val advertising: Boolean,
    /** The token being advertised, `null` when this device does not advertise one. Random, not an identity. */
    val ownToken: Long?,
    /** Links allowed right now: the smaller of the profile's limit and [BleConfig.maxLinks]. */
    val effectiveMaxLinks: Int,
    /** Devices seen recently that are not linked. */
    val candidates: Int,
    /** Of those, devices we leave to connect to us (they have the smaller token). */
    val heldByTieBreak: Int,
    /** Addresses currently in back-off. */
    val backedOff: Int,
    val connectAttempts: Long,
    val connectFailures: Long,
    val linkRotations: Long,
    val tokenRotations: Long,
    val scanStarts: Long,
)
