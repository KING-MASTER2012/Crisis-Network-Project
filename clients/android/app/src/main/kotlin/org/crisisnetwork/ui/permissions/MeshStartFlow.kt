package org.crisisnetwork.android.ui.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ble.android.BleReadiness
import org.crisisnetwork.android.core.ble.android.BleSupport
import org.crisisnetwork.android.notifications.NotificationPrefs
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.Tone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

/**
 * Everything that stands between the user and a working Bluetooth mesh, as one object a screen
 * can drive (phase A4; the logic of the A3 test bench, made reusable). [start] checks in order:
 * Bluetooth Low Energy exists, the runtime permissions are granted (rationale first), Bluetooth is
 * on, the location switch (Android 11 and older), and asks once for the optional notification
 * permission (Android 13+). When everything is in place it calls the `onReady` of
 * [rememberMeshStartFlow]. What is still missing is in [blocker]; [BlockerCard] shows it.
 */
class MeshStartFlow internal constructor(
    val blocker: BleReadiness?,
    val permanentlyDenied: Boolean,
    val start: () -> Unit,
    internal val requestPermissions: (List<String>) -> Unit,
    internal val openAppSettings: () -> Unit,
    internal val enableBluetooth: () -> Unit,
    internal val openLocationSettings: () -> Unit,
)

/**
 * Creates the flow. [onReady] runs when [MeshStartFlow.start] finds everything in place: the home
 * screen starts the service there, the introduction only records that the permissions are done.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberMeshStartFlow(onReady: () -> Unit): MeshStartFlow {
    val context = LocalContext.current
    var blocker by remember { mutableStateOf<BleReadiness?>(null) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var notificationAsked by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(false) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Optional permission: whatever the answer, go on. The settings remember that the dialog was shown.
            NotificationPrefs.markAsked(context)
            autoStart = true
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val role = MeshController.state.value.role
            val missing = BleSupport.missingPermissions(context, role)
            if (missing.isEmpty()) {
                permanentlyDenied = false
                autoStart = true
            } else {
                // After a refusal the system says "show a rationale" only while it will still ask:
                // once it does not, the only way forward is the app settings.
                val activity = context.findActivity()
                permanentlyDenied = activity != null && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            }
        }

    val bluetoothLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) autoStart = true
        }

    val currentOnReady by rememberUpdatedState(onReady)

    fun start() {
        val readiness = BleSupport.readiness(context, MeshController.state.value.role)
        if (readiness != BleReadiness.Ready) {
            blocker = readiness
            return
        }
        blocker = null
        if (needsNotificationPermission(context) && !notificationAsked) {
            notificationAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        currentOnReady()
    }

    // A permission dialog, the Bluetooth switch or the notification dialog finished: try again from the top.
    LaunchedEffect(autoStart) {
        if (autoStart) {
            autoStart = false
            start()
        }
    }

    return MeshStartFlow(
        blocker = blocker,
        permanentlyDenied = permanentlyDenied,
        start = ::start,
        requestPermissions = { permissions ->
            val all = permissions.toMutableList()
            if (needsNotificationPermission(context)) all += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(all.toTypedArray())
        },
        openAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)),
            )
        },
        enableBluetooth = { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        openLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
    )
}

/** What is missing and what to do about it; nothing is drawn while [MeshStartFlow.blocker] is `null`. */
@Composable
fun BlockerCard(flow: MeshStartFlow, modifier: Modifier = Modifier) {
    val blocker = flow.blocker ?: return
    when (blocker) {
        is BleReadiness.MissingPermissions ->
            if (flow.permanentlyDenied) {
                InfoCard(modifier = modifier, title = stringResource(R.string.perm_denied_title), tone = Tone.Warning) {
                    Text(stringResource(R.string.perm_denied_body), style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton(text = stringResource(R.string.perm_open_settings), onClick = flow.openAppSettings)
                }
            } else {
                InfoCard(modifier = modifier, title = stringResource(R.string.perm_rationale_title)) {
                    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.perm_rationale_body else R.string.perm_rationale_body_legacy
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(stringResource(R.string.perm_notifications_note), style = MaterialTheme.typography.bodySmall)
                    }
                    PrimaryButton(text = stringResource(R.string.perm_continue), onClick = { flow.requestPermissions(blocker.permissions) })
                }
            }
        BleReadiness.BluetoothOff ->
            InfoCard(modifier = modifier, tone = Tone.Warning) {
                Text(stringResource(R.string.bluetooth_off_body), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(text = stringResource(R.string.bluetooth_turn_on), onClick = flow.enableBluetooth)
            }
        BleReadiness.LocationSwitchOff ->
            InfoCard(modifier = modifier, tone = Tone.Warning) {
                Text(stringResource(R.string.location_off_body), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(text = stringResource(R.string.location_open_settings), onClick = flow.openLocationSettings)
            }
        BleReadiness.NoBle ->
            InfoCard(modifier = modifier, tone = Tone.Error) {
                Text(stringResource(R.string.no_ble_body), style = MaterialTheme.typography.bodyMedium)
            }
        BleReadiness.Ready -> Unit
    }
}

/** Android 13 and newer: the notification of the service is hidden until the user allows notifications. */
internal fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
