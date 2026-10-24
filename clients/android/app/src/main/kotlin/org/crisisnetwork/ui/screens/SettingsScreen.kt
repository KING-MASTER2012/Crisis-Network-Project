package org.crisisnetwork.android.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import org.crisisnetwork.android.BleLinkTestActivity
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ble.PowerProfile
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.ChoiceGroup
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTitle
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.SectionTitle
import org.crisisnetwork.android.ui.components.SwitchRow
import org.crisisnetwork.android.ui.onboarding.OnboardingFlow
import org.crisisnetwork.android.ui.theme.ThemeMode
import org.crisisnetwork.android.ui.theme.ThemeSettings

private enum class SettingsPage { Main, Diagnostics }

/**
 * The settings (phase A4: appearance, power profile, identity, developer tools). Storage limits,
 * default lifetime and the version of the core arrive with A6.
 */
@Composable
fun SettingsScreen() {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    BackHandler(enabled = page != SettingsPage.Main) { page = SettingsPage.Main }

    when (page) {
        SettingsPage.Main -> SettingsMain(onOpenDiagnostics = { page = SettingsPage.Diagnostics })
        SettingsPage.Diagnostics -> DiagnosticsScreen(onBack = { page = SettingsPage.Main })
    }
}

@Composable
private fun SettingsMain(onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val ui by UiPreferences.state.collectAsState()
    val mesh by MeshController.state.collectAsState()
    val theme = ui.theme

    ScreenColumn {
        ScreenTitle(stringResource(R.string.nav_settings))

        SectionTitle(stringResource(R.string.settings_appearance))
        ChoiceGroup(
            options = ThemeMode.entries,
            selected = theme.mode,
            label = { stringResource(themeModeLabel(it)) },
            onSelect = { UiPreferences.setThemeMode(context, it) },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(if (theme.highContrast) R.string.settings_dynamic_overridden else R.string.settings_dynamic_color_hint),
                checked = theme.dynamicColor,
                onCheckedChange = { UiPreferences.setDynamicColor(context, it) },
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_high_contrast),
            subtitle = stringResource(R.string.settings_high_contrast_hint),
            checked = theme.highContrast,
            onCheckedChange = { UiPreferences.setHighContrast(context, it) },
        )

        SectionTitle(stringResource(R.string.settings_text_size))
        ChoiceGroup(
            options = ThemeSettings.TEXT_SCALES,
            selected = theme.textScale,
            label = { stringResource(textScaleLabel(it)) },
            onSelect = { UiPreferences.setTextScale(context, it) },
        )
        Text(stringResource(R.string.settings_text_sample), style = MaterialTheme.typography.bodyLarge)

        SectionTitle(stringResource(R.string.settings_power))
        Text(stringResource(R.string.settings_power_hint), style = MaterialTheme.typography.bodyMedium)
        ChoiceGroup(
            options = PowerProfile.entries,
            selected = mesh.profile,
            label = { profileLabel(it) },
            onSelect = { MeshController.setProfile(context, it) },
        )

        SectionTitle(stringResource(R.string.settings_identity))
        InfoCard {
            val number = ui.safetyNumber
            if (number == null) {
                Text(stringResource(R.string.settings_identity_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.onb_identity_safety_label), style = MaterialTheme.typography.labelLarge)
                Text(OnboardingFlow.formatSafetyNumber(number), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                ui.nodeIdHex?.let { id ->
                    Text(stringResource(R.string.settings_node_id_label), style = MaterialTheme.typography.labelLarge)
                    Text(OnboardingFlow.shortNodeId(id), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                Text(stringResource(R.string.settings_identity_hint), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (isDebuggable(context)) {
            SectionTitle(stringResource(R.string.settings_developer))
            SecondaryButton(text = stringResource(R.string.settings_open_selftest), onClick = onOpenDiagnostics)
            SecondaryButton(
                text = stringResource(R.string.ble_test_open),
                onClick = { context.startActivity(Intent(context, BleLinkTestActivity::class.java)) },
            )
            SecondaryButton(
                text = stringResource(R.string.settings_show_intro),
                onClick = { UiPreferences.resetOnboarding(context) },
            )
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
        ThemeMode.Amoled -> R.string.settings_theme_amoled
    }

private fun textScaleLabel(scale: Float): Int =
    when (ThemeSettings.TEXT_SCALES.indexOf(scale)) {
        0 -> R.string.settings_text_normal
        1 -> R.string.settings_text_large
        2 -> R.string.settings_text_larger
        else -> R.string.settings_text_largest
    }

private fun isDebuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
