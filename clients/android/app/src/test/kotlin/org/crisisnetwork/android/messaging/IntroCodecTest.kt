package org.crisisnetwork.android.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroCodecTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun aTextStartingWithTheCardPrefixLooksLikeAnIntroduction() {
        assertTrue(IntroCodec.looksLikeIntro(bytes("crisis:" + "A".repeat(171))))
        assertTrue(IntroCodec.looksLikeIntro(bytes("  CRISIS:" + "A".repeat(171))))
    }

    @Test
    fun ordinaryMessagesAreNotIntroductions() {
        assertFalse(IntroCodec.looksLikeIntro(bytes("")))
        assertFalse(IntroCodec.looksLikeIntro(bytes("hello")))
        assertFalse(IntroCodec.looksLikeIntro(bytes("A".repeat(171))))
        assertFalse(IntroCodec.looksLikeIntro(bytes("see crisis:" + "A".repeat(171))))
    }

    @Test
    fun aLongTextIsNeverAnIntroduction() {
        assertFalse(IntroCodec.looksLikeIntro(bytes("crisis:" + "A".repeat(500))))
    }
}
