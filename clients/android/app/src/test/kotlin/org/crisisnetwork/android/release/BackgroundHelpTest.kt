package org.crisisnetwork.android.release

import org.crisisnetwork.android.settings.PhoneMaker
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundHelpTest {
    @Test
    fun makersAreRecognisedWhateverTheCase() {
        assertEquals(PhoneMaker.Xiaomi, PhoneMaker.of("Xiaomi"))
        assertEquals(PhoneMaker.Xiaomi, PhoneMaker.of("POCO"))
        assertEquals(PhoneMaker.Samsung, PhoneMaker.of("samsung"))
        assertEquals(PhoneMaker.Huawei, PhoneMaker.of("HONOR"))
        assertEquals(PhoneMaker.Oppo, PhoneMaker.of("OnePlus"))
        assertEquals(PhoneMaker.Oppo, PhoneMaker.of(" vivo "))
    }

    @Test
    fun everyoneElseGetsTheGeneralAdvice() {
        assertEquals(PhoneMaker.Other, PhoneMaker.of("Google"))
        assertEquals(PhoneMaker.Other, PhoneMaker.of("motorola"))
        assertEquals(PhoneMaker.Other, PhoneMaker.of(""))
        assertEquals(PhoneMaker.Other, PhoneMaker.of(null))
    }
}
