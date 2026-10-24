package org.crisisnetwork.android.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The colours of the status styles (success, warning, emergency) in the current theme. */
val LocalStatusColors = staticCompositionLocalOf { StatusColorsLight }

/** Shortcut: `MaterialTheme.statusColors`. */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * The app theme (phase A4).
 *
 * * Colours: the wallpaper colours on Android 12 and newer when [settings] allow it, otherwise
 *   the brand palette; light, dark or AMOLED; or the high-contrast palette, which wins over the
 *   rest.
 * * Text size: [ThemeSettings.textScale] multiplies the system's font scale, so a person who set
 *   a large font in Android gets it, and can still go further here.
 */
@Composable
fun CrisisTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = settings.isDark(isSystemInDarkTheme())
    val colors = colorSchemeFor(settings, dark, context)
    val status =
        when {
            settings.highContrast && dark -> StatusColorsHighContrastDark
            settings.highContrast -> StatusColorsHighContrastLight
            dark -> StatusColorsDark
            else -> StatusColorsLight
        }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * settings.textScale),
        LocalStatusColors provides status,
    ) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}

internal fun colorSchemeFor(settings: ThemeSettings, dark: Boolean, context: Context): ColorScheme {
    val base: ColorScheme =
        if (settings.highContrast) {
            if (dark) CrisisHighContrastDark else CrisisHighContrastLight
        } else if (settings.dynamicEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) CrisisDark else CrisisLight
        }
    return if (dark && settings.isAmoled) base.amoled() else base
}
