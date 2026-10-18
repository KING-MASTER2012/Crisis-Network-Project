package org.crisisnetwork.android.core.runtime.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory "air" for tests and previews: endpoints are connected pairwise, packets are
 * delivered immediately and in order. It enforces the link MTU like a real radio would.
 */
class LoopbackNetwork {
    fun endpoint(): LoopbackTransport = LoopbackTransport()

    /** Connects two endpoints with a link of [linkMtu] bytes. Both sides get a fresh handle and a `PeerSeen`. */
    fun connect(a: LoopbackTransport, b: LoopbackTransport, linkMtu: Int = DEFAULT_LINK_MTU): LoopbackLink {
        require(a !== b) { "an endpoint cannot connect to itself" }
        val handleA = a.newHandle()
        val handleB = b.newHandle()
        a.links[handleA] = LoopbackTransport.End(b, handleB, linkMtu)
        b.links[handleB] = LoopbackTransport.End(a, handleA, linkMtu)
        a.emit(TransportEvent.PeerSeen(handleA, linkMtu))
        b.emit(TransportEvent.PeerSeen(handleB, linkMtu))
        return LoopbackLink(a, handleA, b, handleB)
    }

    /** Cuts a link from outside (the radio went out of range). Both sides get a `PeerLost`. */
    fun disconnect(link: LoopbackLink) {
        link.a.dropLink(link.handleA)
        link.b.dropLink(link.handleB)
    }

    companion object {
        const val DEFAULT_LINK_MTU: Int = 185
    }
}

/** One connection between two endpoints, with the handle each side knows it by. */
class LoopbackLink internal constructor(
    internal val a: LoopbackTransport,
    val handleA: ULong,
    internal val b: LoopbackTransport,
    val handleB: ULong,
)

/** An endpoint of a [LoopbackNetwork]. */
class LoopbackTransport internal constructor() : Transport {
    internal class End(val remote: LoopbackTransport, val remoteHandle: ULong, val mtu: Int)

    internal val links = ConcurrentHashMap<ULong, End>()

    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    private val handles = AtomicLong(0)
    private val oversized = AtomicInteger(0)

    override val events: Flow<TransportEvent> = channel.receiveAsFlow()

    /** Packets that were dropped because they exceeded the link MTU. A correct core never causes any. */
    val oversizedPacketsDropped: Int get() = oversized.get()

    override fun start() = Unit

    override fun stop() = Unit

    override fun send(peer: ULong, bytes: ByteArray) {
        val end = links[peer] ?: return
        if (bytes.size > end.mtu) {
            oversized.incrementAndGet()
            return
        }
        end.remote.emit(TransportEvent.PacketReceived(end.remoteHandle, bytes.copyOf()))
    }

    override fun disconnect(peer: ULong) {
        dropLink(peer)
    }

    internal fun newHandle(): ULong = handles.incrementAndGet().toULong()

    internal fun emit(event: TransportEvent) {
        channel.trySend(event)
    }

    /** Removes our end and the remote end of a link; both sides report `PeerLost` once. */
    internal fun dropLink(handle: ULong) {
        val end = links.remove(handle) ?: return
        emit(TransportEvent.PeerLost(handle))
        if (end.remote.links.remove(end.remoteHandle) != null) {
            end.remote.emit(TransportEvent.PeerLost(end.remoteHandle))
        }
    }
}
