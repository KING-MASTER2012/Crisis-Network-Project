package org.crisisnetwork.android.core.ble

/**
 * The packets waiting for one link, in order, bounded by their total size. When a packet does not
 * fit, [offer] refuses it and the caller drops it: the mesh repairs a lost packet by itself, while
 * an unbounded queue behind a slow link would grow without limit.
 *
 * The packet being sent stays at the head until it is completed, so it counts against the limit.
 * Not thread-safe: owned by the transport's actor.
 */
internal class SendQueue(private val maxBytes: Int) {
    private val packets = ArrayDeque<ByteArray>()

    /** Total size of the queued packets. */
    var bytes: Int = 0
        private set

    val isEmpty: Boolean get() = packets.isEmpty()

    val size: Int get() = packets.size

    fun offer(packet: ByteArray): Boolean {
        if (bytes + packet.size > maxBytes) return false
        packets.addLast(packet)
        bytes += packet.size
        return true
    }

    fun peek(): ByteArray? = packets.firstOrNull()

    /** Removes the head. Returns it, or `null` when the queue is empty. */
    fun removeFirst(): ByteArray? {
        val head = packets.removeFirstOrNull() ?: return null
        bytes -= head.size
        return head
    }

    fun clear() {
        packets.clear()
        bytes = 0
    }
}
