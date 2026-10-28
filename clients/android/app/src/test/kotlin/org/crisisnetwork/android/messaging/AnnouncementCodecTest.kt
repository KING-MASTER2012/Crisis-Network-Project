package org.crisisnetwork.android.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementCodecTest {
    @Test
    fun aNormalAnnouncementIsPlainText() {
        val payload = AnnouncementCodec.encode("Water at the school", Severity.Normal)
        assertArrayEquals("Water at the school".toByteArray(), payload)
        val decoded = AnnouncementCodec.decode(payload)
        assertEquals("Water at the school", decoded.text)
        assertEquals(Severity.Normal, decoded.severity)
    }

    @Test
    fun importantAndEmergencyAnnouncementsCarryAMarker() {
        for (severity in listOf(Severity.Important, Severity.Emergency)) {
            val payload = AnnouncementCodec.encode("Gas leak, leave the building", severity)
            assertEquals(0x1E.toByte(), payload[0])
            assertEquals(severity.code.code.toByte(), payload[1])
            assertEquals(2, AnnouncementCodec.overhead(severity))
            val decoded = AnnouncementCodec.decode(payload)
            assertEquals("Gas leak, leave the building", decoded.text)
            assertEquals(severity, decoded.severity)
        }
        assertEquals(0, AnnouncementCodec.overhead(Severity.Normal))
    }

    @Test
    fun theTextKeepsItsNonAsciiCharacters() {
        val text = "Su dağıtımı: şimdi, okulda 🚰"
        val decoded = AnnouncementCodec.decode(AnnouncementCodec.encode(text, Severity.Emergency))
        assertEquals(text, decoded.text)
    }

    @Test
    fun strangePayloadsAreReadSafely() {
        // A level above 2 counts as an emergency, 0 as normal.
        assertEquals(Severity.Emergency, AnnouncementCodec.decode(byteArrayOf(0x1E, '7'.code.toByte(), 'x'.code.toByte())).severity)
        assertEquals(Severity.Normal, AnnouncementCodec.decode(byteArrayOf(0x1E, '0'.code.toByte(), 'x'.code.toByte())).severity)
        // A marker without a digit is no marker: the control byte is simply dropped from the text.
        val odd = AnnouncementCodec.decode(byteArrayOf(0x1E, 'x'.code.toByte()))
        assertEquals("x", odd.text)
        assertEquals(Severity.Normal, odd.severity)
        // Empty and one-byte payloads, and invalid UTF-8, do not throw.
        assertEquals("", AnnouncementCodec.decode(ByteArray(0)).text)
        assertEquals("a", AnnouncementCodec.decode(byteArrayOf('a'.code.toByte())).text)
        AnnouncementCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xC3.toByte()))
    }

    @Test
    fun aTypedTextCannotForgeTheMarker() {
        val forged = AnnouncementCodec.encode("\u001E2fake emergency", Severity.Normal)
        val decoded = AnnouncementCodec.decode(forged)
        assertEquals(Severity.Normal, decoded.severity)
        assertEquals("2fake emergency", decoded.text)
    }

    @Test
    fun sanitizingRemovesControlAndDirectionTricks() {
        assertEquals("a\nb\tc", TextSanitizer.clean("a\nb\tc"))
        assertEquals("abc", TextSanitizer.clean("a\u0000b\u0007c\r"))
        assertEquals("evil.txt", TextSanitizer.clean("evil\u202E.txt"))
        assertEquals("ab", TextSanitizer.clean("a\u2066b\u2069"))
        // Ordinary right-to-left text is kept.
        assertEquals("שלום", TextSanitizer.clean("שלום"))
    }
}
