package org.crisisnetwork.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import org.crisisnetwork.android.AppVisibility
import org.crisisnetwork.android.MainActivity
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.AnnouncementCodec
import org.crisisnetwork.android.messaging.Hex
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.Names
import org.crisisnetwork.android.messaging.Severity
import org.crisisnetwork.android.messaging.TextSanitizer
import org.crisisnetwork.android.ui.shell.LaunchTarget
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.MessageKind
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns the core's "a message arrived" into a system notification (phase A6), for the time the app
 * is not on screen. [MeshService] feeds it while the mesh runs.
 *
 * * **Private messages**: one notification per person, in messaging style, showing the last few
 *   messages. A new message from the same person updates it.
 * * **Announcements**: one notification for all of them, showing the newest and how many came.
 *   Emergency announcements are marked as such in the title.
 * * **Privacy**: the lock screen shows only that a message came (public version), never the text
 *   or the name; the full notification appears once the phone is unlocked.
 * * **No flood**: only the first notification of a burst makes a sound ([AlertGate]); the others
 *   update it silently. Android limits the notification rate as well.
 * * **Nothing while the app is on screen**: the badges of the navigation show new messages then.
 *   Opening a chat (or the announcements) removes its notification.
 */
object MessageNotifier {
    private const val TAG_DIRECT = "direct"
    private const val TAG_BROADCAST = "broadcast"
    private const val BROADCAST_ID = 1
    private const val MAX_LINES = 5
    private const val MAX_TEXT_CHARS = 300
    private const val DIRECT_COOLDOWN_MS = 3_000L
    private const val NORMAL_COOLDOWN_MS = 60_000L
    private const val IMPORTANT_COOLDOWN_MS = 30_000L
    private const val EMERGENCY_COOLDOWN_MS = 5_000L

    private class Line(val text: String, val timeMs: Long)

    private val gate = AlertGate()
    private val recentByPeer = HashMap<String, ArrayDeque<Line>>()
    private val unseenAnnouncements = AtomicInteger(0)

    /** Handles one notification of the core. Never throws: a notification must not stop the service. */
    fun onNotification(context: Context, note: CoreNotification) {
        if (note !is CoreNotification.MessageReceived) return
        try {
            val app = context.applicationContext
            if (AppVisibility.visible.value || !AppNotifications.areEnabled(app)) return
            when (note.kind) {
                MessageKind.DIRECT -> direct(app, note)
                MessageKind.BROADCAST -> broadcast(app, note)
                else -> Unit
            }
        } catch (_: RuntimeException) {
            // A refused or failed notification is lost; the message itself is safe in the core.
        }
    }

    /** The chat with [peerHex] was opened: its notification and the lines it showed are no longer needed. */
    fun cancelChat(context: Context, peerHex: String) {
        synchronized(recentByPeer) { recentByPeer.remove(peerHex) }
        NotificationManagerCompat.from(context.applicationContext).cancel(TAG_DIRECT, peerHex.hashCode())
    }

    /** The announcements were opened. */
    fun cancelAnnouncements(context: Context) {
        unseenAnnouncements.set(0)
        NotificationManagerCompat.from(context.applicationContext).cancel(TAG_BROADCAST, BROADCAST_ID)
    }

    // ----- private messages ----------------------------------------------------------------------

    private fun direct(context: Context, note: CoreNotification.MessageReceived) {
        val peerHex = Hex.encode(note.sender)
        val now = System.currentTimeMillis()
        val text = cleanText(note.body).ifEmpty { context.getString(R.string.notif_direct_no_text) }
        val name = Messaging.names.value[peerHex] ?: context.getString(R.string.name_unknown_node, Names.shortId(peerHex))
        val lines =
            synchronized(recentByPeer) {
                val list = recentByPeer.getOrPut(peerHex) { ArrayDeque() }
                list.addLast(Line(text, now))
                while (list.size > MAX_LINES) list.removeFirst()
                list.toList()
            }
        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val sender = Person.Builder().setName(name).build()
        val style = NotificationCompat.MessagingStyle(me)
        for (line in lines) style.addMessage(line.text, line.timeMs, sender)
        val loud = gate.shouldAlert("direct:$peerHex", now, DIRECT_COOLDOWN_MS)
        val notification =
            NotificationCompat.Builder(context, AppNotifications.CHANNEL_DIRECT)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(name)
                .setContentText(lines.last().text)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, AppNotifications.CHANNEL_DIRECT, context.getString(R.string.notif_direct_public)))
                .setAutoCancel(true)
                .setSilent(!loud)
                .setContentIntent(openIntent(context, LaunchTarget.ACTION_OPEN_CHAT, peerHex))
                .build()
        post(context, TAG_DIRECT, peerHex.hashCode(), notification)
    }

    // ----- announcements ---------------------------------------------------------------------------

    private fun broadcast(context: Context, note: CoreNotification.MessageReceived) {
        val decoded = AnnouncementCodec.decode(note.body)
        val severity = decoded.severity
        val now = System.currentTimeMillis()
        val count = unseenAnnouncements.incrementAndGet()
        val text = decoded.text.trim().take(MAX_TEXT_CHARS)
        val title =
            if (count > 1) {
                context.resources.getQuantityString(R.plurals.notif_broadcast_many, count, count)
            } else {
                context.getString(
                    when (severity) {
                        Severity.Normal -> R.string.notif_broadcast_title
                        Severity.Important -> R.string.notif_broadcast_important
                        Severity.Emergency -> R.string.notif_broadcast_emergency
                    },
                )
            }
        val cooldown =
            when (severity) {
                Severity.Normal -> NORMAL_COOLDOWN_MS
                Severity.Important -> IMPORTANT_COOLDOWN_MS
                Severity.Emergency -> EMERGENCY_COOLDOWN_MS
            }
        val loud = gate.shouldAlert("broadcast:${severity.name}", now, cooldown)
        val notification =
            NotificationCompat.Builder(context, AppNotifications.CHANNEL_BROADCAST)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setNumber(count)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (severity == Severity.Emergency) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, AppNotifications.CHANNEL_BROADCAST, context.getString(R.string.notif_broadcast_public)))
                .setAutoCancel(true)
                .setSilent(!loud)
                .setContentIntent(openIntent(context, LaunchTarget.ACTION_OPEN_ANNOUNCEMENTS, null))
                .build()
        post(context, TAG_BROADCAST, BROADCAST_ID, notification)
    }

    // ----- helpers ---------------------------------------------------------------------------------------

    /** Text from another phone: control and direction-override characters removed, bounded in length. */
    private fun cleanText(body: ByteArray): String = TextSanitizer.clean(String(body, Charsets.UTF_8)).trim().take(MAX_TEXT_CHARS)

    /** What the lock screen shows: that something came, nothing of its content. */
    private fun publicVersion(context: Context, channel: String, title: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .build()

    private fun openIntent(context: Context, action: String, peerHex: String?): PendingIntent {
        val key = peerHex ?: "announcements"
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(action)
                // The data makes the intents of different chats differ, so that they get their own PendingIntent.
                .setData(Uri.parse("crisisnetwork://open/$key"))
                .putExtra(LaunchTarget.EXTRA_PEER, peerHex)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // onNotification() checked areNotificationsEnabled(), which includes the runtime permission.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, tag: String, id: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }
}
