package org.crisisnetwork.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.crisisnetwork.android.core.ble.PowerProfile
import org.crisisnetwork.android.notifications.AppNotifications
import org.crisisnetwork.android.notifications.MessageNotifier

/**
 * The foreground service that keeps the mesh alive while the app is in the background
 * (phase A3). Type `connectedDevice`: the app talks to nearby devices over Bluetooth. Android
 * requires the persistent notification; its **Stop** action and the app's switch end the service.
 *
 * The service does no networking itself: it starts and stops [MeshController], mirrors the
 * controller's state in the notification (neighbours, power profile), turns arriving messages into
 * notifications while the app is not on screen ([MessageNotifier], phase A6) and ends itself when
 * the controller stops, for whatever reason (the user, a failure, Bluetooth gone).
 *
 * Start it from the UI with [start] (the app must be visible: Android 12 and newer refuse to start
 * a foreground service from the background). If the system kills and restarts the process, the
 * service comes back by itself only when the user had switched the mesh on and Android lets it
 * enter the foreground; otherwise it ends quietly.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Job? = null
    private var notifier: Job? = null
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MeshController.setWanted(this, false)
                MeshController.stop()
                // The observer ends the service when the controller reports Stopped; without one
                // (the service was not running the mesh) there is nothing to wait for.
                if (observer == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else ->
                if (!MeshController.wantsToRun(this)) {
                    // A restart by the system, but the user had switched the mesh off.
                    stopSelf()
                    return START_NOT_STICKY
                }
        }
        begin()
        return START_STICKY
    }

    private fun begin() {
        MeshController.load(this)
        if (!enterForeground(notification(MeshController.state.value))) {
            MeshController.setWanted(this, false)
            stopSelf()
            return
        }
        if (observer != null) return
        MeshController.start(this) // the state is Starting when this returns
        notifier =
            scope.launch {
                MeshController.notifications.collect { MessageNotifier.onNotification(applicationContext, it) }
            }
        observer =
            scope.launch {
                MeshController.state.collect { state ->
                    if (state.status == MeshController.Status.Stopped) {
                        shutDown()
                    } else {
                        refreshNotification(state)
                    }
                }
            }
    }

    private fun shutDown() {
        observer?.cancel()
        observer = null
        notifier?.cancel()
        notifier = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observer = null
        notifier = null
        scope.cancel()
        super.onDestroy()
    }

    // ----- the notification -------------------------------------------------------------------------

    private fun enterForeground(notification: Notification): Boolean =
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: SecurityException) {
            // Android 14 and newer: the Bluetooth permissions that make a connectedDevice service legal are missing.
            MeshController.reportError("Cannot run in the foreground: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            // Android 12 and newer: the app is in the background and may not start a foreground service.
            MeshController.reportError("Cannot run in the foreground: ${e.message}")
            false
        }

    /** Replaces the notification, but only when its text changed (the controller's state changes every second). */
    private fun refreshNotification(state: MeshController.State) {
        val text = text(state)
        if (text == lastText) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun text(state: MeshController.State): String =
        when (state.status) {
            MeshController.Status.Starting -> getString(R.string.mesh_notification_starting)
            MeshController.Status.Stopping -> getString(R.string.mesh_notification_stopping)
            else ->
                resources.getQuantityString(R.plurals.mesh_notification_running, state.activeLinks, state.activeLinks, profileLabel(state.profile))
        }

    private fun profileLabel(profile: PowerProfile): String =
        getString(
            when (profile) {
                PowerProfile.Saver -> R.string.mesh_profile_saver
                PowerProfile.Balanced -> R.string.mesh_profile_balanced
                PowerProfile.Aggressive -> R.string.mesh_profile_aggressive
            },
        )

    private fun notification(state: MeshController.State): Notification {
        val text = text(state)
        lastText = text
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, MeshService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, AppNotifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.mesh_notification_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "org.crisisnetwork.android.action.MESH_START"
        const val ACTION_STOP = "org.crisisnetwork.android.action.MESH_STOP"
        private const val NOTIFICATION_ID = 1

        /** Switches the mesh on. Call it while the app is visible (a button), after the permissions were granted. */
        fun start(context: Context) {
            MeshController.setWanted(context, true)
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java).setAction(ACTION_START))
        }

        /** Switches the mesh off. */
        fun stop(context: Context) {
            MeshController.setWanted(context, false)
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
