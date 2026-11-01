package org.crisisnetwork.android.core.ble

import java.security.SecureRandom

/**
 * The value of the HELLO characteristic (BLE link profile 1.1): one version byte, then a 64-bit identifier in
 * big-endian order. Nine bytes; a longer value from a later version is read by its first nine bytes.
 *
 * The identifier is random and changes with the advertised token (every few minutes). It is only visible to a
 * device that connected, never in an advertisement. Two phones that connected to each other, one in each
 * direction, use it to notice that both links lead to the same neighbour and keep only one of them.
 */
object HelloValue {
    const val VERSION = 1
    const val SIZE = 9

    fun encode(id: Long): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = VERSION.toByte()
        for (i in 0 until 8) out[1 + i] = (id ushr (56 - 8 * i)).toByte()
        return out
    }

    /** The identifier in [bytes], or `null` when the value is missing, too short or of another version. */
    fun decode(bytes: ByteArray?): Long? {
        if (bytes == null || bytes.size < SIZE || (bytes[0].toInt() and 0xFF) != VERSION) return null
        var id = 0L
        for (i in 1..8) id = (id shl 8) or (bytes[i].toLong() and 0xFFL)
        return id
    }

    /** Identifiers are compared as unsigned 64-bit numbers, so that every platform orders them the same way. */
    fun compare(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)
}

internal object HelloSources {
    private val random = SecureRandom()

    fun secure(): Long = random.nextLong()
}
