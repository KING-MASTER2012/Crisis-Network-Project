package org.crisisnetwork.android.ui

import org.crisisnetwork.android.notifications.AccessAction
import org.crisisnetwork.android.notifications.AlertGate
import org.crisisnetwork.android.notifications.NotificationAccessPlan
import org.crisisnetwork.android.ui.shell.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLogicTest {
    private fun decide(enabled: Boolean = false, sdk: Int = 34, granted: Boolean = false, asked: Boolean = false, rationale: Boolean = false) =
        NotificationAccessPlan.decide(enabled, sdk, granted, asked, rationale)

    @Test
    fun nothingIsNeededWhileNotificationsWork() {
        assertEquals(AccessAction.None, decide(enabled = true, granted = true))
    }

    @Test
    fun theDialogIsShownFirstAndAfterOneRefusal() {
        assertEquals(AccessAction.AskInApp, decide(asked = false))
        assertEquals(AccessAction.AskInApp, decide(asked = true, rationale = true))
    }

    @Test
    fun afterTheSystemStoppedAskingOnlyTheSettingsHelp() {
        assertEquals(AccessAction.OpenSettings, decide(asked = true, rationale = false))
    }

    @Test
    fun aSwitchTurnedOffInTheSystemNeedsTheSettingsNotADialog() {
        // The permission is held, so a dialog would do nothing.
        assertEquals(AccessAction.OpenSettings, decide(granted = true, asked = false))
        // Android 12 and older have no runtime permission at all.
        assertEquals(AccessAction.OpenSettings, decide(sdk = 31, granted = true))
        assertEquals(AccessAction.OpenSettings, decide(sdk = 31, granted = false, asked = false))
    }

    @Test
    fun aBurstMakesOneSoundPerKeyAndCooldown() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("direct:a", 1_000, 3_000))
        assertFalse(gate.shouldAlert("direct:a", 2_000, 3_000))
        assertTrue(gate.shouldAlert("direct:b", 2_000, 3_000))
        assertTrue(gate.shouldAlert("direct:a", 4_000, 3_000))
        assertFalse(gate.shouldAlert("direct:a", 4_500, 3_000))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotSilenceAKey() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("k", 10_000, 60_000))
        assertTrue(gate.shouldAlert("k", 5_000, 60_000))
        gate.reset()
        assertTrue(gate.shouldAlert("k", 5_001, 60_000))
    }

    @Test
    fun aTapOnANotificationOpensOnlyWellFormedTargets() {
        assertEquals(LaunchTarget.Chat("aa11"), LaunchTarget.of(LaunchTarget.ACTION_OPEN_CHAT, "aa11"))
        assertEquals(LaunchTarget.Announcements, LaunchTarget.of(LaunchTarget.ACTION_OPEN_ANNOUNCEMENTS, null))
        assertNull(LaunchTarget.of(LaunchTarget.ACTION_OPEN_CHAT, null))
        assertNull(LaunchTarget.of(LaunchTarget.ACTION_OPEN_CHAT, "zz"))
        assertNull(LaunchTarget.of(LaunchTarget.ACTION_OPEN_CHAT, "AA11"))
        assertNull(LaunchTarget.of("android.intent.action.MAIN", "aa11"))
        assertNull(LaunchTarget.of(null, null))
    }
}
