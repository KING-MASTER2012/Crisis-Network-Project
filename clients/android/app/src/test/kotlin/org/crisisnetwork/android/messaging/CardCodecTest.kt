package org.crisisnetwork.android.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCodecTest {
    private val card = ByteArray(CardCodec.CARD_LEN) { (it * 7 + 3).toByte() }

    @Test
    fun aCardSurvivesTheRoundTripAndHasAKnownLength() {
        val text = CardCodec.encode(card)
        assertTrue(text.startsWith("crisis:"))
        assertEquals(7 + 171, text.length)
        assertArrayEquals(card, CardCodec.decode(text))
    }

    @Test
    fun decodingIsForgiving() {
        val text = CardCodec.encode(card)
        val body = text.removePrefix("crisis:")
        assertArrayEquals(card, CardCodec.decode(body))
        assertArrayEquals(card, CardCodec.decode(text.uppercase().replace(body.uppercase(), body)))
        assertArrayEquals(card, CardCodec.decode("  " + text.substring(0, 40) + "\n" + text.substring(40) + " \n"))
        // Standard base64 (with + and /) is accepted too.
        val standard = java.util.Base64.getEncoder().encodeToString(card)
        assertArrayEquals(card, CardCodec.decode(standard))
    }

    @Test
    fun textThatIsNotACardIsRefused() {
        assertNull(CardCodec.decode(""))
        assertNull(CardCodec.decode("crisis:"))
        assertNull(CardCodec.decode("hello world"))
        assertNull(CardCodec.decode("crisis:" + "A".repeat(10)))
        // Right alphabet, wrong length.
        assertNull(CardCodec.decode(CardCodec.encode(ByteArray(127))))
        assertNull(CardCodec.decode(CardCodec.encode(ByteArray(129))))
    }

    @Test
    fun theTextOfACardFitsInAQrCodeThatScansBack() {
        val text = CardCodec.encode(card)
        val matrix = QrCodes.encode(text)
        // Version 8 is 49 modules wide: the card must not need a denser code than that.
        assertTrue("QR code is ${matrix.size} modules wide", matrix.size <= 49)

        val scale = 6
        val quiet = 4
        val width = (matrix.size + 2 * quiet) * scale
        val luma = ByteArray(width * width) { 255.toByte() }
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        luma[((y + quiet) * scale + dy) * width + (x + quiet) * scale + dx] = 0
                    }
                }
            }
        }
        val scanned = QrDecoder().decode(luma, width, width, width)
        assertNotNull(scanned)
        assertArrayEquals(card, CardCodec.decode(scanned!!))
    }

    @Test
    fun aFrameWithoutACodeGivesNothing() {
        val blank = ByteArray(200 * 200) { 200.toByte() }
        assertNull(QrDecoder().decode(blank, 200, 200, 200))
    }
}
