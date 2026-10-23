package org.crisisnetwork.android

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
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.crisisnetwork.android.core.ble.BleRole
import org.crisisnetwork.android.core.ble.BleStats
import org.crisisnetwork.android.core.ble.LinkInfo
import org.crisisnetwork.android.core.ble.PowerProfile
import org.crisisnetwork.android.core.ble.android.BleReadiness
import org.crisisnetwork.android.core.ble.android.BleSupport
import java.util.Locale

/**
 * Test bench of phases A2 and A3: starts the mesh (through the foreground service), shows the
 * links, the discovery and power state and the throughput counters, and lets phones exchange
 * announcements. It also exercises the permission flow that A4's onboarding will reuse: rationale
 * first, then the system dialog, then a shortcut to the app settings when the user said "don't ask
 * again". Development tool; the bench's own labels are English only.
 *
 * Procedures for the measurements: see `clients/android/README.md`.
 */
class BleLinkTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshController.load(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BleLinkTestScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BleLinkTestScreen() {
    val context = LocalContext.current
    val mesh by MeshController.state.collectAsState()
    val counters by BleLinkTestController.counters.collectAsState()
    var text by remember { mutableStateOf("hello from this phone") }

    // What still stands between the user and a running mesh (null: nothing known to be wrong).
    var blocker by remember { mutableStateOf<BleReadiness?>(null) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var notificationAsked by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(false) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Optional permission: whatever the answer, go on.
            autoStart = true
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val missing = BleSupport.missingPermissions(context, mesh.role)
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

    fun startClicked() {
        val readiness = BleSupport.readiness(context, mesh.role)
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
        MeshService.start(context)
    }

    // A permission, the Bluetooth switch or the notification dialog finished: try again from the top.
    LaunchedEffect(autoStart) {
        if (autoStart) {
            autoStart = false
            startClicked()
        }
    }

    val busy = mesh.status == MeshController.Status.Starting || mesh.status == MeshController.Status.Stopping

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("BLE link test (phase A3)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Both is the product setting: two phones agree on one link. For a chain A - B - C run the end phones as Central and the middle one as Both.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Role", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (role in BleRole.entries) {
                FilterChip(
                    selected = mesh.role == role,
                    onClick = { MeshController.setRole(context, role) },
                    enabled = mesh.status == MeshController.Status.Stopped,
                    label = { Text(role.name) },
                )
            }
        }

        Text("Power profile (applies at once)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (profile in PowerProfile.entries) {
                FilterChip(
                    selected = mesh.profile == profile,
                    onClick = { MeshController.setProfile(context, profile) },
                    label = { Text(profile.name) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mesh.status == MeshController.Status.Stopped) {
                Button(onClick = ::startClicked) { Text("Start") }
            } else {
                Button(onClick = { MeshService.stop(context) }, enabled = !busy) {
                    Text(if (mesh.status == MeshController.Status.Starting) "Starting…" else "Stop")
                }
            }
            OutlinedButton(
                onClick = { BleLinkTestController.resetCounters() },
                enabled = mesh.status == MeshController.Status.Running,
            ) { Text("Reset counters") }
        }

        blocker?.let { b ->
            BlockerCard(
                blocker = b,
                permanentlyDenied = permanentlyDenied,
                onRequestPermissions = { permissions ->
                    val all = permissions.toMutableList()
                    if (needsNotificationPermission(context)) all += Manifest.permission.POST_NOTIFICATIONS
                    permissionLauncher.launch(all.toTypedArray())
                },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onEnableBluetooth = { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        }

        mesh.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        mesh.safetyNumber?.let { Text("This node: $it", style = MaterialTheme.typography.bodySmall) }

        if (mesh.status == MeshController.Status.Running) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Announcement") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { BleLinkTestController.sendBroadcast(text) }) { Text("Send") }
                OutlinedButton(onClick = { BleLinkTestController.sendBurst() }) {
                    Text("Burst ${BleLinkTestController.BURST_COUNT} x ${BleLinkTestController.BURST_PAYLOAD_BYTES} B")
                }
            }
        }

        Text("Status: ${mesh.status} · screen ${if (mesh.screenOn) "on" else "off"}", style = MaterialTheme.typography.titleMedium)
        mesh.stats?.let { StatsBlock(it, mesh.activeLinks) }

        Text("Links", style = MaterialTheme.typography.titleMedium)
        if (mesh.links.isEmpty()) Text("none", style = MaterialTheme.typography.bodySmall)
        mesh.links.forEach { LinkRow(it) }

        Text("Received", style = MaterialTheme.typography.titleMedium)
        val receiveRate = counters.receiveBytesPerSecond?.let { " · ${format(it)} B/s between first and last" }.orEmpty()
        Text("${counters.receivedMessages} messages, ${counters.receivedBytes} B payload$receiveRate", style = MaterialTheme.typography.bodySmall)

        Text("Log (newest first)", style = MaterialTheme.typography.titleMedium)
        mesh.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

/** The permission flow in one place: what is wrong, why we need it, and the next step. */
@Composable
private fun BlockerCard(
    blocker: BleReadiness,
    permanentlyDenied: Boolean,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (blocker) {
            is BleReadiness.MissingPermissions -> {
                if (permanentlyDenied) {
                    Text(stringResource(R.string.perm_denied_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.perm_denied_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onOpenAppSettings) { Text(stringResource(R.string.perm_open_settings)) }
                } else {
                    Text(stringResource(R.string.perm_rationale_title), style = MaterialTheme.typography.titleMedium)
                    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.perm_rationale_body else R.string.perm_rationale_body_legacy
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(stringResource(R.string.perm_notifications_note), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onRequestPermissions(blocker.permissions) }) { Text(stringResource(R.string.perm_continue)) }
                }
            }
            BleReadiness.BluetoothOff -> {
                Text(stringResource(R.string.bluetooth_off_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onEnableBluetooth) { Text(stringResource(R.string.bluetooth_turn_on)) }
            }
            BleReadiness.LocationSwitchOff -> {
                Text(stringResource(R.string.location_off_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenLocationSettings) { Text(stringResource(R.string.location_open_settings)) }
            }
            BleReadiness.NoBle -> Text(stringResource(R.string.no_ble_body), style = MaterialTheme.typography.bodyMedium)
            BleReadiness.Ready -> Unit
        }
    }
}

@Composable
private fun StatsBlock(stats: BleStats, activeLinks: Int) {
    val token = stats.ownToken?.let { String.format(Locale.US, "%08x", it) } ?: "none"
    Column {
        Text(
            "scan ${if (stats.scanning) stats.scanMode.toString() else "off"} · advertising ${if (stats.advertising) "on" else "off"} · token $token",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "links $activeLinks/${stats.effectiveMaxLinks} (${stats.profile}) · candidates ${stats.candidates} (${stats.heldByTieBreak} left to the peer) · in back-off ${stats.backedOff}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "connects ${stats.connectAttempts} (failed ${stats.connectFailures}) · link rotations ${stats.linkRotations} · token rotations ${stats.tokenRotations} · scan starts ${stats.scanStarts}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LinkRow(link: LinkInfo) {
    val m = link.metrics
    val title = if (link.active) "handle ${link.handle}" else "connecting"
    val attMtu = if (link.payloadMtu > 0) (link.payloadMtu + 3).toString() else "?"
    val txRate = m.txBytesPerSecond?.let { " · ${format(it)} B/s while sending" }.orEmpty()
    val rxRate = m.rxBytesPerSecond?.let { " · ${format(it)} B/s" }.orEmpty()
    Column {
        Text("$title · ${link.role} · ${link.address}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "packet ≤ ${link.payloadMtu} B (ATT MTU $attMtu) · queued ${link.queuedBytes} B",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "sent ${m.packetsSent} pkts / ${m.bytesSent} B$txRate · dropped ${m.packetsDropped}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("received ${m.packetsReceived} pkts / ${m.bytesReceived} B$rxRate", style = MaterialTheme.typography.bodySmall)
    }
}

/** Android 13 and newer: the notification of the service is hidden until the user allows notifications. */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun format(value: Double): String = String.format(Locale.US, "%.0f", value)
