package org.crisisnetwork.android.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.MeshService
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ble.android.BleReadiness
import org.crisisnetwork.android.core.ble.android.BleSupport
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTitle
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.permissions.BlockerCard
import org.crisisnetwork.android.ui.permissions.rememberMeshStartFlow

/**
 * The introduction shown on first launch (phase A4): what the app is, creating the identity, the
 * permissions with their reasons, and what happens in the background (the notification, the
 * battery). Only the identity is required. [onFinished] gets `true` when the person chose to join
 * the mesh right away.
 */
@Composable
fun OnboardingScreen(onFinished: (joinNow: Boolean) -> Unit) {
    val context = LocalContext.current
    val ui by UiPreferences.state.collectAsState()
    var step by rememberSaveable { mutableStateOf(OnboardingFlow.firstStep(UiPreferences.state.value.identityCreated)) }
    var nearbyReady by remember { mutableStateOf(BleSupport.readiness(context, MeshController.state.value.role) == BleReadiness.Ready) }
    val nearbyFlow = rememberMeshStartFlow(onReady = { nearbyReady = true })
    val joinFlow =
        rememberMeshStartFlow(
            onReady = {
                MeshService.start(context)
                onFinished(true)
            },
        )

    BackHandler(enabled = OnboardingFlow.canGoBack(step)) { step = OnboardingFlow.previous(step) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val label = stringResource(R.string.onb_step_of, OnboardingFlow.position(step), OnboardingFlow.total)
                Text(label, style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { OnboardingFlow.position(step).toFloat() / OnboardingFlow.total },
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = label },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (step) {
                OnboardingStep.Welcome -> WelcomeStep(onNext = { step = OnboardingFlow.next(step) })
                OnboardingStep.Identity ->
                    IdentityStep(
                        identityCreated = ui.identityCreated,
                        safetyNumber = ui.safetyNumber,
                        onNext = { step = OnboardingFlow.next(step) },
                        onBack = { step = OnboardingFlow.previous(step) },
                    )
                OnboardingStep.Nearby ->
                    NearbyStep(
                        ready = nearbyReady,
                        flow = nearbyFlow,
                        onNext = { step = OnboardingFlow.next(step) },
                        onBack = { step = OnboardingFlow.previous(step) },
                    )
                OnboardingStep.Background ->
                    BackgroundStep(
                        onNext = { step = OnboardingFlow.next(step) },
                        onBack = { step = OnboardingFlow.previous(step) },
                    )
                OnboardingStep.Done ->
                    DoneStep(
                        identityCreated = ui.identityCreated,
                        nearbyReady = nearbyReady,
                        joinFlow = joinFlow,
                        onOpenApp = { onFinished(false) },
                        onBack = { step = OnboardingFlow.previous(step) },
                    )
            }
        }
    }
}

/** The buttons under a step, centred and limited in width like the content above. */
@Composable
private fun StepActions(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun StepFrame(
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            ScreenColumn { content() }
        }
        StepActions(actions)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepFrame(actions = { PrimaryButton(text = stringResource(R.string.onb_get_started), onClick = onNext) }) {
        ScreenTitle(stringResource(R.string.onb_welcome_title))
        Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
        InfoCard(title = stringResource(R.string.onb_point_offline_title)) {
            Text(stringResource(R.string.onb_point_offline_body), style = MaterialTheme.typography.bodyMedium)
        }
        InfoCard(title = stringResource(R.string.onb_point_relay_title)) {
            Text(stringResource(R.string.onb_point_relay_body), style = MaterialTheme.typography.bodyMedium)
        }
        InfoCard(title = stringResource(R.string.onb_point_private_title)) {
            Text(stringResource(R.string.onb_point_private_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IdentityStep(
    identityCreated: Boolean,
    safetyNumber: String?,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<CoreFailure?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    StepFrame(
        actions = {
            if (identityCreated) {
                PrimaryButton(text = stringResource(R.string.onb_next), onClick = onNext)
            } else {
                PrimaryButton(
                    text = stringResource(R.string.onb_identity_create),
                    enabled = !creating,
                    onClick = {
                        creating = true
                        failure = null
                        scope.launch {
                            when (val result = IdentityCreator.create(context)) {
                                is IdentityResult.Created -> UiPreferences.saveIdentity(context, result.safetyNumber, result.nodeIdHex)
                                is IdentityResult.Failed -> failure = result.failure
                            }
                            creating = false
                        }
                    },
                )
            }
            QuietButton(text = stringResource(R.string.onb_back), onClick = onBack)
        },
    ) {
        ScreenTitle(stringResource(R.string.onb_identity_title))
        Text(stringResource(R.string.onb_identity_body), style = MaterialTheme.typography.bodyLarge)
        if (creating) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(stringResource(R.string.onb_identity_creating), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (identityCreated) {
            InfoCard(title = stringResource(R.string.onb_identity_created), tone = Tone.Success) {
                Text(stringResource(R.string.onb_identity_safety_label), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = OnboardingFlow.formatSafetyNumber(safetyNumber.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.onb_identity_safety_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        val current = failure
        if (current != null) {
            InfoCard(title = stringResource(R.string.onb_identity_failed), tone = Tone.Error) {
                Text(stringResource(failureMessage(current)), style = MaterialTheme.typography.bodyMedium)
                if (current is CoreFailure.SecretsUnavailable && current.resettable) {
                    SecondaryButton(text = stringResource(R.string.onb_identity_reset), onClick = { confirmReset = true })
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.onb_reset_title)) },
            text = { Text(stringResource(R.string.onb_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        scope.launch {
                            IdentityCreator.reset(context)
                            UiPreferences.clearIdentity(context)
                            failure = null
                        }
                    },
                ) { Text(stringResource(R.string.onb_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.onb_reset_cancel)) }
            },
        )
    }
}

/** What to tell the person about a failure: short, no jargon; the details stay in the logs. */
private fun failureMessage(failure: CoreFailure): Int =
    when (failure) {
        is CoreFailure.SecretsUnavailable ->
            if (failure.resettable) R.string.onb_failure_secrets_resettable else R.string.onb_failure_secrets
        CoreFailure.DatabaseUnreadable -> R.string.onb_failure_database
        CoreFailure.EncryptionUnavailable -> R.string.onb_failure_encryption
        is CoreFailure.SchemaTooNew -> R.string.onb_failure_newer
        else -> R.string.onb_failure_generic
    }

@Composable
private fun NearbyStep(
    ready: Boolean,
    flow: org.crisisnetwork.android.ui.permissions.MeshStartFlow,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    StepFrame(
        actions = {
            if (ready) {
                PrimaryButton(text = stringResource(R.string.onb_next), onClick = onNext)
            } else {
                PrimaryButton(
                    text = stringResource(R.string.onb_nearby_allow),
                    onClick = {
                        // Ask straight away when only permissions are missing: the reasons are on this screen.
                        val readiness = BleSupport.readiness(context, MeshController.state.value.role)
                        if (readiness is BleReadiness.MissingPermissions) flow.requestPermissions(readiness.permissions) else flow.start()
                    },
                )
                QuietButton(text = stringResource(R.string.onb_skip), onClick = onNext)
            }
            QuietButton(text = stringResource(R.string.onb_back), onClick = onBack)
        },
    ) {
        ScreenTitle(stringResource(R.string.onb_nearby_title))
        Text(stringResource(R.string.onb_nearby_body), style = MaterialTheme.typography.bodyLarge)
        InfoCard(title = stringResource(R.string.onb_nearby_why_title)) {
            val reason = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) R.string.perm_rationale_body else R.string.perm_rationale_body_legacy
            Text(stringResource(reason), style = MaterialTheme.typography.bodyMedium)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Text(stringResource(R.string.perm_notifications_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ready) {
            InfoCard(tone = Tone.Success) { Text(stringResource(R.string.onb_nearby_ready), style = MaterialTheme.typography.bodyMedium) }
        } else {
            val blocker = flow.blocker
            if (blocker is BleReadiness.MissingPermissions && !flow.permanentlyDenied) {
                InfoCard(tone = Tone.Warning) {
                    Text(stringResource(R.string.onb_nearby_not_granted), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                BlockerCard(flow)
            }
        }
    }
}

@Composable
private fun BackgroundStep(
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck += 1 }
    val unrestricted = remember(recheck) { isBatteryUnrestricted(context) }

    StepFrame(
        actions = {
            PrimaryButton(text = stringResource(R.string.onb_next), onClick = onNext)
            QuietButton(text = stringResource(R.string.onb_back), onClick = onBack)
        },
    ) {
        ScreenTitle(stringResource(R.string.onb_background_title))
        Text(stringResource(R.string.onb_background_body), style = MaterialTheme.typography.bodyLarge)
        InfoCard(title = stringResource(R.string.onb_background_notification_title)) {
            Text(stringResource(R.string.onb_background_notification_body), style = MaterialTheme.typography.bodyMedium)
        }
        InfoCard(title = stringResource(R.string.onb_background_battery_title), tone = if (unrestricted) Tone.Success else Tone.Neutral) {
            Text(stringResource(R.string.onb_background_battery_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (unrestricted) R.string.onb_battery_unrestricted else R.string.onb_battery_optimized),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!unrestricted) {
                SecondaryButton(
                    text = stringResource(R.string.onb_battery_open_settings),
                    onClick = { launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
            }
            Text(stringResource(R.string.onb_battery_oem_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Whether Android leaves the app out of its battery optimisations (so that Doze does not stop it in the background). */
internal fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

@Composable
private fun DoneStep(
    identityCreated: Boolean,
    nearbyReady: Boolean,
    joinFlow: org.crisisnetwork.android.ui.permissions.MeshStartFlow,
    onOpenApp: () -> Unit,
    onBack: () -> Unit,
) {
    StepFrame(
        actions = {
            PrimaryButton(text = stringResource(R.string.onb_join_now), enabled = identityCreated, onClick = joinFlow.start)
            SecondaryButton(text = stringResource(R.string.onb_open_app), onClick = onOpenApp)
            QuietButton(text = stringResource(R.string.onb_back), onClick = onBack)
        },
    ) {
        ScreenTitle(stringResource(R.string.onb_done_title))
        Text(stringResource(R.string.onb_done_body), style = MaterialTheme.typography.bodyLarge)
        InfoCard(tone = if (identityCreated) Tone.Success else Tone.Warning) {
            Text(stringResource(if (identityCreated) R.string.onb_done_identity_ok else R.string.onb_done_identity_missing), style = MaterialTheme.typography.bodyMedium)
        }
        InfoCard(tone = if (nearbyReady) Tone.Success else Tone.Warning) {
            Text(stringResource(if (nearbyReady) R.string.onb_done_nearby_ok else R.string.onb_done_nearby_missing), style = MaterialTheme.typography.bodyMedium)
        }
        BlockerCard(joinFlow)
    }
}
