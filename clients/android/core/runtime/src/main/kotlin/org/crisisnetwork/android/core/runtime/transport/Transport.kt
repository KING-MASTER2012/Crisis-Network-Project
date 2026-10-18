package org.crisisnetwork.android.core.runtime.transport

import kotlinx.coroutines.flow.Flow

/** What a transport tells the runtime. Handles are chosen by the transport. */
sealed interface TransportEvent {
    /** A neighbour is connected, or its link MTU changed. [linkMtu] is the largest packet the link carries. */
    data class PeerSeen(val peer: ULong, val linkMtu: Int) : TransportEvent

    /** A neighbour is gone. Reporting it twice, or for a link the core closed itself, is harmless. */
    data class PeerLost(val peer: ULong) : TransportEvent

    /** One packet arrived from a neighbour. */
    class PacketReceived(val peer: ULong, val bytes: ByteArray) : TransportEvent
}

/**
 * The radio, as the runtime sees it. The BLE transport (phases A2 and A3) and the in-memory
 * [LoopbackNetwork] implement it.
 *
 * Contract:
 *
 * * **Handles** identify one connection and are never reused during the life of the transport:
 *   a reconnect gets a new handle. The core keys all per-neighbour state by handle.
 * * [send] and [disconnect] **must not block**: they enqueue (a per-link send queue, see A2) and
 *   return. A packet that no longer fits the link or a link that is gone is dropped silently;
 *   the mesh repairs losses by itself.
 * * Every packet handed to [send] already fits the MTU last reported with [TransportEvent.PeerSeen].
 * * [events] is collected by exactly one collector, the runtime.
 */
interface Transport {
    val events: Flow<TransportEvent>

    /** Starts scanning, advertising and so on. Called once by the runtime before it processes events. */
    fun start()

    /** Stops the radio and closes all links. */
    fun stop()

    fun send(peer: ULong, bytes: ByteArray)

    /** Closes the connection. The transport reports [TransportEvent.PeerLost] afterwards. */
    fun disconnect(peer: ULong)
}
