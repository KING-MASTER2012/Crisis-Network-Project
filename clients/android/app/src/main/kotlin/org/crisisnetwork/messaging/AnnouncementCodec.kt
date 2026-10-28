package org.crisisnetwork.android.messaging

/** How urgent an announcement is. It travels inside the payload, so every phone shows the same style. */
enum class Severity(val code: Char) {
    Normal('0'),
    Important('1'),
    Emergency('2'),
}

/**
 * What an announcement's payload looks like (a convention of the apps, not of the protocol).
 *
 * * A **normal** announcement is the plain UTF-8 text. Nothing is added, so phones and tools that
 *   know nothing about severities show it correctly.
 * * An **important** or **emergency** announcement starts with two bytes: the control byte 0x1E
 *   (record separator) and the ASCII digit of the level (`1` or `2`), then the UTF-8 text.
 *
 * When reading, a digit above 2 counts as an emergency, `0` as normal, and a payload that does not
 * start with the marker is plain text. Text typed by a person never contains control characters
 * ([TextSanitizer]), so nobody can forge the marker from inside a text.
 */
object AnnouncementCodec {
    private const val MARKER: Byte = 0x1E

    /** Bytes the marker takes from the payload. */
    fun overhead(severity: Severity): Int = if (severity == Severity.Normal) 0 else 2

    class Decoded(val text: String, val severity: Severity)

    fun encode(text: String, severity: Severity): ByteArray {
        val body = TextSanitizer.clean(text).toByteArray(Charsets.UTF_8)
        if (severity == Severity.Normal) return body
        return byteArrayOf(MARKER, severity.code.code.toByte()) + body
    }

    fun decode(payload: ByteArray): Decoded {
        if (payload.size >= 2 && payload[0] == MARKER) {
            val digit = payload[1].toInt() - '0'.code
            if (digit in 0..9) {
                val severity =
                    when (digit) {
                        0 -> Severity.Normal
                        1 -> Severity.Important
                        else -> Severity.Emergency
                    }
                return Decoded(TextSanitizer.clean(String(payload, 2, payload.size - 2, Charsets.UTF_8)), severity)
            }
        }
        return Decoded(TextSanitizer.clean(String(payload, Charsets.UTF_8)), Severity.Normal)
    }
}

/** Makes text from other phones safe to show: no control characters and no text-direction tricks. */
object TextSanitizer {
    /**
     * Removes control characters (except new lines and tabs) and the bidirectional embedding,
     * override and isolate characters, which can be used to make a text read as something else.
     */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\n' || ch == '\t' -> out.append(ch)
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                ch in '\u202A'..'\u202E' -> Unit
                ch in '\u2066'..'\u2069' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
