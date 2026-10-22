package org.crisisnetwork.android.core.ble

/** Which sides of the link this device runs. */
enum class BleRole(val central: Boolean, val peripheral: Boolean) {
    /** Scan and connect only. */
    Central(central = true, peripheral = false),

    /** Advertise and accept connections only. */
    Peripheral(central = false, peripheral = true),

    /**
     * Both, which is what the product does. Until the tie-break of phase A3 two phones that run
     * `Both` connect to each other twice (once per direction): that works, it only wastes a link.
     * For the A2 measurements run `Central` on one phone and `Peripheral` on the other.
     */
    Both(central = true, peripheral = true),
}

/**
 * Tunables of [BleTransport]. The defaults are conservative; phase A3 adds power profiles on top.
 *
 * @property minLinkMtu The smallest packet size the core accepts (`core_info().min_link_mtu`,
 *   64 in protocol v1). A link that cannot carry it is closed instead of being handed to the core.
 *   Pass the value reported by the core.
 * @property requestedAttMtu The ATT MTU the central asks for. Android caps it at 517.
 */
data class BleConfig(
    val role: BleRole = BleRole.Both,
    val minLinkMtu: Int = 64,
    val requestedAttMtu: Int = 517,
    /** Links (connected or still connecting, both directions together) above this are refused. */
    val maxLinks: Int = 4,
    /** From the start of a connection until it is usable (services, MTU, subscription). */
    val handshakeTimeoutMs: Long = 15_000L,
    /** A single write or notification that is not completed within this time closes the link. */
    val sendTimeoutMs: Long = 5_000L,
    /** The platform briefly refused a send (busy): ask again after this long ... */
    val sendRetryDelayMs: Long = 25L,
    /** ... at most this many times for one packet, then the link is considered broken. */
    val maxSendAttempts: Int = 40,
    /** This many sends in a row that the platform reported as failed close the link. */
    val maxConsecutiveSendFailures: Int = 3,
    /** Bytes waiting for one link. A packet that does not fit is dropped (the mesh repairs losses). */
    val maxQueuedBytes: Int = 32 * 1024,
    /** After a failed outgoing connection the same address is left alone for this long. */
    val reconnectCooldownMs: Long = 5_000L,
) {
    init {
        require(minLinkMtu >= 20) { "minLinkMtu must be at least 20" }
        require(requestedAttMtu in 23..517) { "requestedAttMtu must be within 23..517" }
        require(maxLinks >= 1) { "maxLinks must be positive" }
        require(maxQueuedBytes >= MAX_ATT_VALUE) { "maxQueuedBytes must hold at least one maximum-size packet" }
        require(maxSendAttempts >= 1 && maxConsecutiveSendFailures >= 1) { "send limits must be positive" }
    }

    companion object {
        /** ATT bytes that surround a value in a write or a notification (opcode + handle). */
        const val ATT_OVERHEAD = 3

        /** The largest attribute value ATT allows. */
        const val MAX_ATT_VALUE = 512

        /** The packet size a link with this ATT MTU carries. */
        fun payloadForAttMtu(attMtu: Int): Int = (attMtu - ATT_OVERHEAD).coerceIn(0, MAX_ATT_VALUE)
    }
}
