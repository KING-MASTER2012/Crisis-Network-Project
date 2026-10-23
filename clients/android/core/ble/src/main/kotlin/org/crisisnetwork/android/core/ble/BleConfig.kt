package org.crisisnetwork.android.core.ble

/** Which sides of the link this device runs. */
enum class BleRole(val central: Boolean, val peripheral: Boolean) {
    /** Scan and connect only. Does not advertise, so nobody can connect to it. */
    Central(central = true, peripheral = false),

    /** Advertise and accept connections only. */
    Peripheral(central = false, peripheral = true),

    /**
     * Both, which is what the product does. Two phones that run `Both` decide with the rotating
     * token of their advertisements which one initiates (see [BleAdvertisement]), so they share one
     * link instead of two. For topology experiments (a chain A - B - C) run the end phones as
     * `Central` and the middle one as `Both`.
     */
    Both(central = true, peripheral = true),
}

/**
 * Tunables of [BleTransport]. The defaults are conservative; [profile] selects the radio settings
 * and link limits (see [PowerProfile]) and can be changed while the transport runs.
 *
 * @property minLinkMtu The smallest packet size the core accepts (`core_info().min_link_mtu`,
 *   64 in protocol v1). A link that cannot carry it is closed instead of being handed to the core.
 *   Pass the value reported by the core.
 * @property requestedAttMtu The ATT MTU the central asks for. Android caps it at 517.
 * @property maxLinks A hard ceiling on links (connected or still connecting, both directions
 *   together), whatever the profile says; the effective limit is the smaller of the two. Links
 *   above it are refused.
 * @property reconnectCooldownMs After a link ended, or a connection attempt failed once, the same
 *   address is left alone for this long. Every further failure in a row doubles it, up to
 *   [maxBackoffMs].
 * @property maxConcurrentConnects Outgoing connections that may be in progress at once. Android
 *   stacks are unreliable with parallel `connectGatt` calls (status 133), so the default is one.
 * @property connectSpacingMs Pause between the end of one connection attempt and the start of the
 *   next one.
 * @property setupFailedBackoffMs Minimum back-off for a device that answered but does not offer
 *   our service (it is not a Crisis Network node) or whose setup failed.
 * @property stackRecoveryThreshold This many failed attempts in a row (any address) pause all new
 *   connections for [stackRecoveryPauseMs]: repeated 133 usually means the stack is confused.
 * @property candidateTtlMs A device that was not seen for this long is no longer a candidate.
 * @property tieBreakFallbackMs A device we left to connect to us (smaller token on its side) is
 *   connected to anyway when we have had no link at all for this long: protects against a peer
 *   that cannot see us.
 * @property tokenRotationMs How often the advertised token is replaced.
 * @property maxScanStartsPer30s Android refuses more than 5 scan starts per 30 s per app; the
 *   transport stays below this number and delays a restart instead.
 * @property scanRefreshMs A scan that has run this long is restarted, because Android demotes a
 *   long-running scan to an opportunistic one.
 * @property scanFailureBackoffMs First delay before a failed scan start is retried (doubles).
 * @property stableLinkMs A link that lived at least this long counts as healthy: its address
 *   starts from a clean slate when it ends.
 * @property minLinkAgeForRotationMs Only links older than this are rotated away.
 * @property rotationPauseMs After a link was rotated away its address is left alone for this long.
 * @property tickMs Period of the housekeeping tick (candidates, rotation, token, scan refresh).
 */
data class BleConfig(
    val role: BleRole = BleRole.Both,
    val minLinkMtu: Int = 64,
    val requestedAttMtu: Int = 517,
    val profile: PowerProfile = PowerProfile.Balanced,
    val maxLinks: Int = 8,
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
    val reconnectCooldownMs: Long = 5_000L,
    val maxBackoffMs: Long = 60_000L,
    val maxConcurrentConnects: Int = 1,
    val connectSpacingMs: Long = 0L,
    val setupFailedBackoffMs: Long = 30_000L,
    val stackRecoveryThreshold: Int = 3,
    val stackRecoveryPauseMs: Long = 5_000L,
    val candidateTtlMs: Long = 20_000L,
    val tieBreakFallbackMs: Long = 30_000L,
    val tokenRotationMs: Long = 10 * 60_000L,
    val maxScanStartsPer30s: Int = 4,
    val scanRefreshMs: Long = 25 * 60_000L,
    val scanFailureBackoffMs: Long = 5_000L,
    val stableLinkMs: Long = 20_000L,
    val minLinkAgeForRotationMs: Long = 60_000L,
    val rotationPauseMs: Long = 60_000L,
    val tickMs: Long = 2_000L,
) {
    init {
        require(minLinkMtu >= 20) { "minLinkMtu must be at least 20" }
        require(requestedAttMtu in 23..517) { "requestedAttMtu must be within 23..517" }
        require(maxLinks >= 1) { "maxLinks must be positive" }
        require(maxQueuedBytes >= MAX_ATT_VALUE) { "maxQueuedBytes must hold at least one maximum-size packet" }
        require(maxSendAttempts >= 1 && maxConsecutiveSendFailures >= 1) { "send limits must be positive" }
        require(maxConcurrentConnects >= 1) { "maxConcurrentConnects must be positive" }
        require(stackRecoveryThreshold >= 1) { "stackRecoveryThreshold must be positive" }
        require(maxScanStartsPer30s in 1..5) { "maxScanStartsPer30s must be within 1..5 (the platform allows 5)" }
        require(tickMs >= 1L) { "tickMs must be positive" }
        require(tokenRotationMs >= 1L) { "tokenRotationMs must be positive" }
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
