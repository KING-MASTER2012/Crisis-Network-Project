package org.crisisnetwork.android.notifications

/** What the user has to do to get notifications (phase A6). */
enum class AccessAction {
    /** Notifications are on already. */
    None,

    /** Show the system's permission dialog (Android 13 and newer). */
    AskInApp,

    /** The system will not ask (again): the only way is the notification settings of the app. */
    OpenSettings,
}

/**
 * Decides how to get the notification permission, kept apart from the user interface so that it
 * can be tested. The facts: whether notifications work now (the runtime permission and the switch
 * in the system settings together), the Android version, whether the runtime permission is held,
 * whether the system dialog was shown before, and whether the system says that it will show it again.
 */
object NotificationAccessPlan {
    /** The first Android version with the runtime permission `POST_NOTIFICATIONS`. */
    const val RUNTIME_PERMISSION_SDK = 33

    fun decide(
        enabled: Boolean,
        sdk: Int,
        permissionGranted: Boolean,
        asked: Boolean,
        showRationale: Boolean,
    ): AccessAction =
        when {
            enabled -> AccessAction.None
            // Without the runtime permission the dialog is the way, as long as the system still shows it: the first
            // time, and after one refusal ("show a rationale"). After that it answers "no" at once, without a dialog.
            sdk >= RUNTIME_PERMISSION_SDK && !permissionGranted && (!asked || showRationale) -> AccessAction.AskInApp
            // The permission is held but the switch is off, or the system stopped asking, or Android is older.
            else -> AccessAction.OpenSettings
        }
}

/**
 * Keeps a flood of messages from becoming a flood of sounds: after a notification made a sound for
 * a [key] (a person, a kind of announcement), further ones for the same key stay silent until the
 * cooldown has passed. They still update the notification, so nothing is hidden.
 */
class AlertGate {
    private val lastAlertMs = HashMap<String, Long>()

    /** `true` when a notification for [key] may make a sound now; remembers it when it does. */
    @Synchronized
    fun shouldAlert(key: String, nowMs: Long, cooldownMs: Long): Boolean {
        val previous = lastAlertMs[key]
        // A clock that went backwards must not silence the key for longer than the cooldown.
        if (previous != null && nowMs >= previous && nowMs - previous < cooldownMs) return false
        if (lastAlertMs.size >= MAX_KEYS) lastAlertMs.clear()
        lastAlertMs[key] = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        lastAlertMs.clear()
    }

    private companion object {
        /** More keys than a phone has conversations; the table is emptied instead of growing without bound. */
        const val MAX_KEYS = 512
    }
}
