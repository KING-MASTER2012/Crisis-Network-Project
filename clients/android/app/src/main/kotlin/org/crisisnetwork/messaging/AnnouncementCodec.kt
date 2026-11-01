package org.crisisnetwork.android.messaging

import uniffi.crisis_core.AnnouncementSeverity
import uniffi.crisis_core.announcementOverhead
import uniffi.crisis_core.decodeAnnouncement
import uniffi.crisis_core.encodeAnnouncement
import uniffi.crisis_core.sanitizeText

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
 *
 * The rules live in the Rust core (`appcodec`), so that every client applies the same ones; this object
 * only translates between the app's types and the core's.
 */
object AnnouncementCodec {
    /** Bytes the marker takes from the payload. */
    fun overhead(severity: Severity): Int = announcementOverhead(severity.toCore()).toInt()

    class Decoded(val text: String, val severity: Severity)

    fun encode(text: String, severity: Severity): ByteArray = encodeAnnouncement(text, severity.toCore())

    fun decode(payload: ByteArray): Decoded {
        val decoded = decodeAnnouncement(payload)
        return Decoded(decoded.text, decoded.severity.toApp())
    }
}

/** Makes text from other phones safe to show: no control characters and no text-direction tricks. */
object TextSanitizer {
    /**
     * Removes control characters (except new lines and tabs) and the bidirectional embedding,
     * override and isolate characters, which can be used to make a text read as something else.
     */
    fun clean(text: String): String = sanitizeText(text)
}

internal fun Severity.toCore(): AnnouncementSeverity =
    when (this) {
        Severity.Normal -> AnnouncementSeverity.NORMAL
        Severity.Important -> AnnouncementSeverity.IMPORTANT
        Severity.Emergency -> AnnouncementSeverity.EMERGENCY
    }

private fun AnnouncementSeverity.toApp(): Severity =
    when (this) {
        AnnouncementSeverity.NORMAL -> Severity.Normal
        AnnouncementSeverity.IMPORTANT -> Severity.Important
        AnnouncementSeverity.EMERGENCY -> Severity.Emergency
    }
