package org.crisisnetwork.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.crisisnetwork.android.core.ffi.SmokeReport
import org.crisisnetwork.android.core.ffi.runCoreSmoke
import java.io.File

/**
 * Phase A0 shell: a single screen that runs the native core self-test and prints the result.
 * The real design system, navigation and screens arrive in phase A4.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SelfTestScreen()
                }
            }
        }
    }
}

@Composable
private fun SelfTestScreen() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<SmokeReport?>(null) }
    var runCount by remember { mutableIntStateOf(0) }

    // The core opens SQLite files, so it never runs on the main thread.
    LaunchedEffect(runCount) {
        report = null
        report = withContext(Dispatchers.Default) {
            runCoreSmoke(File(context.cacheDir, "core-smoke"))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.selftest_title), style = MaterialTheme.typography.headlineMedium)

        val current = report
        if (current == null) {
            CircularProgressIndicator()
            Text(stringResource(R.string.selftest_running))
        } else {
            ReportView(current)
            Button(onClick = { runCount += 1 }) {
                Text(stringResource(R.string.selftest_run_again))
            }
            // Phase A2 test bench: two phones, real Bluetooth.
            OutlinedButton(onClick = { context.startActivity(Intent(context, BleLinkTestActivity::class.java)) }) {
                Text(stringResource(R.string.ble_test_open))
            }
        }
    }
}

@Composable
private fun ReportView(report: SmokeReport) {
    Text(
        text = stringResource(if (report.passed) R.string.selftest_passed else R.string.selftest_failed),
        style = MaterialTheme.typography.titleLarge,
        color = if (report.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )

    val info = report.info
    if (info == null) {
        Text(stringResource(R.string.selftest_library_missing), color = MaterialTheme.colorScheme.error)
    } else {
        Text(
            stringResource(
                R.string.selftest_core_line,
                info.crateVersion,
                info.apiVersion.toInt(),
                info.protocolVersion.toInt(),
            ),
        )
        Text(
            stringResource(
                R.string.selftest_limits_line,
                info.minLinkMtu.toInt(),
                info.maxPayloadBytes.toInt(),
            ),
        )
    }

    when (report.encryptionAvailable) {
        true -> Text(stringResource(R.string.selftest_encryption_active))
        false -> Text(stringResource(R.string.selftest_encryption_inactive))
        null -> Unit
    }

    // The step texts are diagnostics for developers and stay in English.
    for (step in report.steps) {
        Row {
            Text(text = if (step.ok) "✓" else "✗", color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(step.name, style = MaterialTheme.typography.titleSmall)
                Text(step.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
