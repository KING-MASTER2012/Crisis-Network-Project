package org.crisisnetwork.android.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.crisisnetwork.android.messaging.Lifetimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: the presets come from the Rust core. */
@RunWith(AndroidJUnit4::class)
class DefaultLifetimesTest {
    @Test
    fun theDefaultLifetimeIsAlwaysOneOfTheComposerPresets() {
        assertEquals(3_600L, DefaultLifetimes.coerce(3_600L))
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(1L))
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(Long.MAX_VALUE))
        // A protocol maximum below a preset removes that preset from the choices.
        assertEquals(Lifetimes.DEFAULT_SECS, DefaultLifetimes.coerce(604_800L, maxSecs = 86_400L))
        assertTrue(DefaultLifetimes.options().all { it in Lifetimes.presetSeconds })
    }
}
