package org.crisisnetwork.android.core.ble

import java.security.SecureRandom

/**
 * What a Crisis Network node adds to its advertisement besides the service UUID: a **rotating
 * random token** and one flag, carried as manufacturer specific data (company id [COMPANY_ID],
 * the Bluetooth SIG value reserved for testing and private use).
 *
 * Layout of the 5 payload bytes (big endian):
 *
 * ```
 * byte 0     bits 0..3  format version (1)
 *            bit  4     "can initiate": this device also scans and connects to others
 *            bits 5..7  reserved, ignored
 * bytes 1..4 token, an unsigned 32-bit random number
 * ```
 *
 * Why it exists: two phones that both scan and advertise would otherwise connect to each other
 * twice, once per direction. With the token both sides can make the same decision without talking:
 * **the device with the smaller token initiates, the other one waits** ([TieBreak]). The token
 * carries no identity: it is random, unrelated to the node's keys, and replaced every
 * [BleConfig.tokenRotationMs], so it cannot be used to follow a person around (the radio address
 * rotates as well on current phones).
 *
 * The advertisement budget is 31 bytes: flags (3) + 128-bit service UUID (18) + this structure
 * (2 header + 2 company id + 5 payload = 9) = 30.
 */
data class BleAdvertisement(val token: Long, val canInitiate: Boolean) {
    init {
        require(token in 0L..MAX_TOKEN) { "the token is an unsigned 32-bit number" }
    }

    /** The [SIZE] payload bytes. */
    fun encode(): ByteArray {
        val flags = VERSION or (if (canInitiate) FLAG_CAN_INITIATE else 0)
        return byteArrayOf(
            flags.toByte(),
            (token shr 24).toByte(),
            (token shr 16).toByte(),
            (token shr 8).toByte(),
            token.toByte(),
        )
    }

    companion object {
        /** Bluetooth SIG company identifier 0xFFFF: reserved for testing, no owner. */
        const val COMPANY_ID = 0xFFFF

        const val VERSION = 1
        const val SIZE = 5
        const val MAX_TOKEN = 0xFFFF_FFFFL

        private const val VERSION_MASK = 0x0F
        private const val FLAG_CAN_INITIATE = 0x10

        /** Parses the payload of the manufacturer data; `null` when it is absent, short or of another version. */
        fun decode(bytes: ByteArray?): BleAdvertisement? {
            if (bytes == null || bytes.size < SIZE) return null
            val flags = bytes[0].toInt() and 0xFF
            if ((flags and VERSION_MASK) != VERSION) return null
            var token = 0L
            for (i in 1..4) token = (token shl 8) or (bytes[i].toLong() and 0xFFL)
            return BleAdvertisement(token, (flags and FLAG_CAN_INITIATE) != 0)
        }
    }
}

/** Decides which of two phones that see each other opens the connection. */
internal object TieBreak {
    /**
     * Whether we initiate a connection to [peer].
     *
     * * We do **not** advertise a token ([ownToken] is `null`): the peer cannot see us, so it will
     *   never connect to us: we must.
     * * The peer sent no usable token (an older build, another platform), or it cannot initiate
     *   (it only advertises): we must.
     * * Otherwise the smaller token initiates. Equal tokens (1 in 4 billion) make both wait until
     *   the next rotation.
     */
    fun shouldInitiate(ownToken: Long?, peer: BleAdvertisement?): Boolean {
        if (ownToken == null || peer == null || !peer.canInitiate) return true
        return ownToken < peer.token
    }
}

/** Where the transport gets its tokens from. */
internal object TokenSources {
    private val random = SecureRandom()

    fun secure(): Long = random.nextInt().toLong() and BleAdvertisement.MAX_TOKEN
}
