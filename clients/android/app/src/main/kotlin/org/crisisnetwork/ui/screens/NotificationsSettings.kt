package org.crisisnetwork.android.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import org.crisisnetwork.android.AppVisibility
import org.crisisnetwork.android.R
import org.crisisnetwork.android.notifications.AccessAction
import org.crisisnetwork.android.notifications.AppNotifications
import org.crisisnetwork.android.notifications.NotificationAccessPlan
import org.crisisnetwork.android.notifications.NotificationPrefs
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.permissions.findActivity
import org.crisisnetwork.android.ui.permissions.needsNotificationPermission

/**
 * Whether notifications work, and what to do about it (phase A6). [request] does the right thing for
 * the situation (see [NotificationAccessPlan]): the system dialog while the system still shows it,
 * the notification settings of the app otherwise. [openSettings] opens those settings, where the
 * sound and the importance of each channel are chosen.
 */
class NotificationAccess internal constructor(
    val enabled: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/**
 * The state is read again whenever the app comes back to the front: the person may have used the
 * system settings in between.
 */
@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val resumes by AppVisibility.resumes.collectAsState()
    var enabled by remember { mutableStateOf(AppNotifications.areEnabled(context)) }
    LaunchedEffect(resumes) { enabled = AppNotifications.areEnabled(context) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationPrefs.markAsked(context)
            enabled = AppNotifications.areEnabled(context)
        }

    val request: () -> Unit = {
        val activity = context.findActivity()
        val rationale =
            activity != null &&
                Build.VERSION.SDK_INT >= NotificationAccessPlan.RUNTIME_PERMISSION_SDK &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        val action =
            NotificationAccessPlan.decide(
                enabled = AppNotifications.areEnabled(context),
                sdk = Build.VERSION.SDK_INT,
                permissionGranted = !needsNotificationPermission(context),
                asked = NotificationPrefs.asked(context),
                showRationale = rationale,
            )
        when (action) {
            AccessAction.AskInApp -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            AccessAction.OpenSettings -> openNotificationSettings(context)
            AccessAction.None -> Unit
        }
    }
    return NotificationAccess(enabled = enabled, request = request, openSettings = { openNotificationSettings(context) })
}

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(AppNotifications.settingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        // A device without the notification settings screen: the details of the app have the switch too.
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))
    }
}

/** The notification part of the settings: the state, a button to allow, and the three kinds of notification. */
@Composable
fun NotificationsSettings(access: NotificationAccess) {
    Text(stringResource(R.string.settings_notifications_hint), style = MaterialTheme.typography.bodyMedium)
    InfoCard(tone = if (access.enabled) Tone.Success else Tone.Warning) {
        // The words carry the state; the colour only supports them.
        Text(
            stringResource(if (access.enabled) R.string.settings_notifications_on else R.string.settings_notifications_off),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!access.enabled) {
            PrimaryButton(text = stringResource(R.string.notif_allow), onClick = access.request)
        }
    }
    ChannelDescription(R.string.notif_channel_direct_name, R.string.notif_channel_direct_description)
    ChannelDescription(R.string.notif_channel_broadcast_name, R.string.notif_channel_broadcast_description)
    ChannelDescription(R.string.mesh_channel_name, R.string.mesh_channel_description)
    SecondaryButton(text = stringResource(R.string.settings_notifications_channels), onClick = access.openSettings)
}

@Composable
private fun ChannelDescription(name: Int, description: Int) {
    Column {
        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
    }
}
