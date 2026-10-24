package org.crisisnetwork.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ffi.SmokeReport
import org.crisisnetwork.android.core.ffi.runCoreSmoke
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTitle
import java.io.File

/**
 * The core self-test of phase A0, moved here from the launcher screen: a developer tool in the
 * settings. The step texts are diagnostics and stay in English.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<SmokeReport?>(null) }
    var runCount by remember { mutableIntStateOf(0) }

    // The core opens SQLite files, so it never runs on the main thread.
    LaunchedEffect(runCount) {
        report = null
        report =
            withContext(Dispatchers.Default) {
                runCoreSmoke(File(context.cacheDir, "core-smoke"))
            }
    }

    ScreenColumn {
        QuietButton(text = stringResource(R.string.diag_back), onClick = onBack)
        ScreenTitle(stringResource(R.string.selftest_title))

        val current = report
        if (current == null) {
            CircularProgressIndicator()
            Text(stringResource(R.string.selftest_running))
        } else {
            ReportView(current)
            PrimaryButton(text = stringResource(R.string.selftest_run_again), onClick = { runCount += 1 })
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

    for (step in report.steps) {
        Row {
            // Shape and colour: a tick or a cross, never colour alone.
            Text(
                text = if (step.ok) "✓" else "✗",
                color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(step.name, style = MaterialTheme.typography.titleSmall)
                Text(step.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
