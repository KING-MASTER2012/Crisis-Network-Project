package org.crisisnetwork.android.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.MeshService
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ble.PowerProfile
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTitle
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.onboarding.OnboardingFlow
import org.crisisnetwork.android.ui.permissions.BlockerCard
import org.crisisnetwork.android.ui.permissions.rememberMeshStartFlow

/** The label of a power profile, shared by the home screen and the settings. */
@Composable
fun profileLabel(profile: PowerProfile): String =
    stringResource(
        when (profile) {
            PowerProfile.Saver -> R.string.mesh_profile_saver
            PowerProfile.Balanced -> R.string.mesh_profile_balanced
            PowerProfile.Aggressive -> R.string.mesh_profile_aggressive
        },
    )

/**
 * The first screen: is this phone part of the mesh, with how many neighbours, and the switch to
 * join or leave. Joining goes through the permission flow, then starts the foreground service.
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val mesh by MeshController.state.collectAsState()
    val ui by UiPreferences.state.collectAsState()
    val startFlow = rememberMeshStartFlow(onReady = { MeshService.start(context) })

    ScreenColumn {
        ScreenTitle(stringResource(R.string.app_name))

        val running = mesh.status == MeshController.Status.Running
        val busy = mesh.status == MeshController.Status.Starting || mesh.status == MeshController.Status.Stopping
        val neighbours = mesh.activeLinks

        InfoCard(title = stringResource(R.string.home_mesh_title)) {
            val (label, tone) =
                when {
                    mesh.status == MeshController.Status.Starting -> stringResource(R.string.home_status_starting) to Tone.Neutral
                    mesh.status == MeshController.Status.Stopping -> stringResource(R.string.home_status_stopping) to Tone.Neutral
                    running && neighbours > 0 -> stringResource(R.string.home_status_connected) to Tone.Success
                    running -> stringResource(R.string.home_status_searching) to Tone.Warning
                    else -> stringResource(R.string.home_status_offline) to Tone.Neutral
                }
            StatusPill(text = label, tone = tone)
            if (running) {
                Text(pluralStringResource(R.plurals.home_neighbours, neighbours, neighbours), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_profile_line, profileLabel(mesh.profile)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (neighbours == 0) {
                    Text(stringResource(R.string.home_searching_hint), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (!busy) {
                Text(stringResource(R.string.home_offline_hint), style = MaterialTheme.typography.bodyMedium)
            }
            if (running || mesh.status == MeshController.Status.Stopping) {
                SecondaryButton(
                    text = stringResource(R.string.home_leave),
                    enabled = !busy,
                    onClick = { MeshService.stop(context) },
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.home_join),
                    enabled = !busy,
                    onClick = startFlow.start,
                )
            }
        }

        BlockerCard(startFlow)

        mesh.error?.let { error ->
            InfoCard(title = stringResource(R.string.home_error_title), tone = Tone.Error) {
                Text(error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        ui.safetyNumber?.let { number ->
            InfoCard(title = stringResource(R.string.home_identity_title)) {
                Text(
                    OnboardingFlow.formatSafetyNumber(number),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.home_identity_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
