package org.crisisnetwork.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.crisisnetwork.android.R

/**
 * The notification channels of the app (phase A6) and what the rest of the app asks about
 * notifications. Three channels, so that the user can choose sound and importance for each kind in
 * the system settings:
 *
 * * [CHANNEL_SERVICE]: the permanent notification of the foreground service (quiet, no badge);
 * * [CHANNEL_DIRECT]: new private messages (loud: someone wrote to this person);
 * * [CHANNEL_BROADCAST]: new public announcements (the default importance: they can be many).
 *
 * The id of the service channel did not change since phase A3, so that an existing installation
 * keeps what the user chose for it. Creating a channel that exists does nothing, so
 * [createChannels] can be called whenever convenient.
 */
object AppNotifications {
    const val CHANNEL_SERVICE = "mesh_service"
    const val CHANNEL_DIRECT = "direct_messages"
    const val CHANNEL_BROADCAST = "announcements"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val service =
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.mesh_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.mesh_channel_description)
                setShowBadge(false)
            }
        val direct =
            NotificationChannel(CHANNEL_DIRECT, context.getString(R.string.notif_channel_direct_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_direct_description)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        val broadcast =
            NotificationChannel(CHANNEL_BROADCAST, context.getString(R.string.notif_channel_broadcast_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_broadcast_description)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        manager.createNotificationChannels(listOf(service, direct, broadcast))
    }

    /** Whether the app may show notifications now: the runtime permission (Android 13+) and the system switch together. */
    fun areEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The system screen that lists the three channels, where the user changes sound and importance of each. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

/**
 * Whether the system's permission dialog was shown before. The system itself gives no way to tell "never
 * asked" from "refused for good", and the difference decides between the dialog and the settings.
 * Not secret: private storage of the app.
 */
object NotificationPrefs {
    private const val PREFS = "notifications"
    private const val KEY_ASKED = "permission_asked"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun asked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }
}
