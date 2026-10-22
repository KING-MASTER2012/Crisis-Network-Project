package org.crisisnetwork.android

import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.core.ble.BleRole
import org.crisisnetwork.android.core.ble.LinkInfo
import org.crisisnetwork.android.core.ble.android.BleSupport
import java.util.Locale

/**
 * Phase A2 test bench: starts the node over real Bluetooth and shows the links, their MTU and the
 * throughput counters, and lets two phones exchange announcements. Development tool, English only;
 * the permission handling here is the minimum, the real flow is phase A3.
 *
 * Procedure for the A2 measurements: see `clients/android/README.md`.
 */
class BleLinkTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BleLinkTestScreen()
                }
            }
        }
    }
}

@Composable
private fun BleLinkTestScreen() {
    val context = LocalContext.current
    val ui by BleLinkTestController.state.collectAsState()
    var text by remember { mutableStateOf("hello from this phone") }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val denied = granted.filterValues { !it }.keys
            if (denied.isEmpty()) {
                BleLinkTestController.start(context)
            } else {
                BleLinkTestController.reportError("Permission denied: " + denied.joinToString { name -> name.substringAfterLast('.') })
            }
        }

    fun startClicked() {
        val role = ui.role
        val missing = BleSupport.missingPermissions(context, role)
        when {
            !BleSupport.isBluetoothEnabled(context) -> BleLinkTestController.reportError("Switch Bluetooth on first.")
            BleSupport.isScanBlockedByLocationSwitch(context, role) ->
                BleLinkTestController.reportError("Android 11 and older: switch the system location setting on, or scanning finds nothing.")
            missing.isNotEmpty() -> permissionLauncher.launch(missing.toTypedArray())
            else -> BleLinkTestController.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("BLE link test (phase A2)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Run Central on one phone and Peripheral on the other (Both connects twice until A3).",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (role in BleRole.entries) {
                FilterChip(
                    selected = ui.role == role,
                    onClick = { BleLinkTestController.setRole(role) },
                    enabled = !ui.running && !ui.busy,
                    label = { Text(role.name) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.running) {
                Button(onClick = { BleLinkTestController.stop() }, enabled = !ui.busy) { Text("Stop") }
            } else {
                Button(onClick = ::startClicked, enabled = !ui.busy) { Text(if (ui.busy) "Starting…" else "Start") }
            }
            OutlinedButton(onClick = { BleLinkTestController.resetCounters() }, enabled = ui.running) { Text("Reset counters") }
        }

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.safetyNumber?.let { Text("This node: $it", style = MaterialTheme.typography.bodySmall) }

        if (ui.running) {
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

        Text("Links", style = MaterialTheme.typography.titleMedium)
        if (ui.links.isEmpty()) Text("none", style = MaterialTheme.typography.bodySmall)
        ui.links.forEach { LinkRow(it) }

        Text("Received", style = MaterialTheme.typography.titleMedium)
        val receiveRate = ui.receiveBytesPerSecond?.let { " · ${format(it)} B/s between first and last" }.orEmpty()
        Text("${ui.receivedMessages} messages, ${ui.receivedBytes} B payload$receiveRate", style = MaterialTheme.typography.bodySmall)

        Text("Log (newest first)", style = MaterialTheme.typography.titleMedium)
        ui.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
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

private fun format(value: Double): String = String.format(Locale.US, "%.0f", value)
