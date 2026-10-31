package org.crisisnetwork.android.ui

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Keeps the content of the app out of screenshots, screen recordings and the thumbnail in the list of
 * recent apps (phase A7). It is a window flag (`FLAG_SECURE`), so it covers every screen of the
 * activity, the contact card and the safety number included. It does not protect against a person
 * who looks at the screen or against a rooted phone; it protects against the things that copy a
 * screen without anyone looking: other apps with screen capture, casting, "recent apps" previews.
 */
object ScreenProtection {
    fun apply(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Android 13 and newer: the system's own screenshot of the app for the recents list is a separate switch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enabled)
        }
    }
}
