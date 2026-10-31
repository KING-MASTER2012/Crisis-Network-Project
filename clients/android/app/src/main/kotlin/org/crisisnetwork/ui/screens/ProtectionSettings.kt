package org.crisisnetwork.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import org.crisisnetwork.android.AppVisibility
import org.crisisnetwork.android.R
import org.crisisnetwork.android.settings.PhoneMaker
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.SwitchRow
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.onboarding.isBatteryUnrestricted

/** The privacy switch of the settings (phase A7): keep the app out of screenshots and the recent-apps preview. */
@Composable
fun PrivacySettings() {
    val context = LocalContext.current
    val ui by UiPreferences.state.collectAsState()
    SwitchRow(
        title = stringResource(R.string.settings_screen_protection),
        subtitle = stringResource(R.string.settings_screen_protection_hint),
        checked = ui.screenProtection,
        onCheckedChange = { UiPreferences.setScreenProtection(context, it) },
    )
}

/**
 * What keeps the mesh alive with the screen off (phase A7): the battery setting of Android, and the
 * extra lists of the phone maker that need a different place (see [PhoneMaker]). The state is read
 * again whenever the app comes back to the front, because the person leaves the app to change it.
 */
@Composable
fun BackgroundSettings() {
    val context = LocalContext.current
    val resumes by AppVisibility.resumes.collectAsState()
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    LaunchedEffect(resumes) { unrestricted = isBatteryUnrestricted(context) }
    val maker = remember { PhoneMaker.of(Build.MANUFACTURER) }

    Text(stringResource(R.string.settings_background_hint), style = MaterialTheme.typography.bodyMedium)
    InfoCard(tone = if (unrestricted) Tone.Success else Tone.Warning) {
        // The words carry the state; the colour only supports them.
        Text(
            stringResource(if (unrestricted) R.string.onb_battery_unrestricted else R.string.onb_battery_optimized),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!unrestricted) {
            SecondaryButton(text = stringResource(R.string.onb_battery_open_settings), onClick = { openBatterySettings(context) })
        }
    }
    InfoCard(title = stringResource(R.string.settings_background_maker_title)) {
        Text(stringResource(makerHint(maker)), style = MaterialTheme.typography.bodyMedium)
        SecondaryButton(text = stringResource(R.string.settings_background_app_settings), onClick = { openAppDetails(context) })
    }
}

private fun makerHint(maker: PhoneMaker): Int =
    when (maker) {
        PhoneMaker.Xiaomi -> R.string.bg_maker_xiaomi
        PhoneMaker.Samsung -> R.string.bg_maker_samsung
        PhoneMaker.Huawei -> R.string.bg_maker_huawei
        PhoneMaker.Oppo -> R.string.bg_maker_oppo
        PhoneMaker.Other -> R.string.bg_maker_other
    }

/** The list of apps with battery optimisation: choosing the app there needs no special permission. */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        openAppDetails(context)
    }
}

/** The system page of this app: the makers' own switches (autostart, launch, battery) are reached from there. */
private fun openAppDetails(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)))
}
