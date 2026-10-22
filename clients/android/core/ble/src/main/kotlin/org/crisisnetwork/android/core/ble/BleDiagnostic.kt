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
