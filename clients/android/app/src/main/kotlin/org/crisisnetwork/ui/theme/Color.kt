package org.crisisnetwork.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The brand palette: a calm blue for actions, neutral greys for surfaces. Status colours (see
// StatusColors) carry the meaning of announcements: they never rely on colour alone, every use
// also has a label or an icon.

internal val CrisisLight: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF0B5CAD),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E4FF),
        onPrimaryContainer = Color(0xFF001C3A),
        secondary = Color(0xFF565F71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDAE2F9),
        onSecondaryContainer = Color(0xFF131C2B),
        tertiary = Color(0xFF6E5676),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF8D8FF),
        onTertiaryContainer = Color(0xFF28132F),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF9F9FF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF9F9FF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE0E2EC),
        onSurfaceVariant = Color(0xFF44474E),
        outline = Color(0xFF74777F),
        outlineVariant = Color(0xFFC4C6D0),
    )

internal val CrisisDark: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFA5C8FF),
        onPrimary = Color(0xFF00315F),
        primaryContainer = Color(0xFF004789),
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF283141),
        secondaryContainer = Color(0xFF3E4758),
        onSecondaryContainer = Color(0xFFDAE2F9),
        tertiary = Color(0xFFDBBCE3),
        onTertiary = Color(0xFF3E2845),
        tertiaryContainer = Color(0xFF553F5D),
        onTertiaryContainer = Color(0xFFF8D8FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE2E2E9),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE2E2E9),
        surfaceVariant = Color(0xFF44474E),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF44474E),
    )

/** Black text on white (and the reverse), a dark blue for actions, outlines as strong as the text. */
internal val CrisisHighContrastLight: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF00346B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE8FF),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF1F2837),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE3E8F5),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF3B2442),
        onTertiary = Color(0xFFFFFFFF),
        error = Color(0xFF8C0009),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE3E0),
        onErrorContainer = Color(0xFF000000),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFF000000),
        outline = Color(0xFF000000),
        outlineVariant = Color(0xFF3A3A3A),
    )

internal val CrisisHighContrastDark: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFCFE2FF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF1B3A66),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFDDE4F5),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF2B3446),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFF1D6F8),
        onTertiary = Color(0xFF000000),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF000000),
        errorContainer = Color(0xFF5C0008),
        onErrorContainer = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1A1A1A),
        onSurfaceVariant = Color(0xFFFFFFFF),
        outline = Color(0xFFFFFFFF),
        outlineVariant = Color(0xFFC8C8C8),
    )

/** The dark scheme with true black surfaces; the containers keep a hint of grey so that cards stay visible. */
internal fun ColorScheme.amoled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0B),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF191919),
        surfaceContainerHighest = Color(0xFF212121),
    )

/**
 * The colours of the three kinds of announcement and of positive feedback. Used for cards and
 * labels; never the only carrier of the message.
 */
data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val emergencyContainer: Color,
    val onEmergencyContainer: Color,
)

internal val StatusColorsLight =
    StatusColors(
        successContainer = Color(0xFFC8F0D2),
        onSuccessContainer = Color(0xFF002111),
        warningContainer = Color(0xFFFFE08A),
        onWarningContainer = Color(0xFF241A00),
        emergencyContainer = Color(0xFFFFD9D4),
        onEmergencyContainer = Color(0xFF410002),
    )

internal val StatusColorsDark =
    StatusColors(
        successContainer = Color(0xFF1B5133),
        onSuccessContainer = Color(0xFFC8F0D2),
        warningContainer = Color(0xFF5A4300),
        onWarningContainer = Color(0xFFFFE08A),
        emergencyContainer = Color(0xFF8B1A12),
        onEmergencyContainer = Color(0xFFFFDAD6),
    )

internal val StatusColorsHighContrastLight =
    StatusColors(
        successContainer = Color(0xFFD7F5DF),
        onSuccessContainer = Color(0xFF000000),
        warningContainer = Color(0xFFFFEFB0),
        onWarningContainer = Color(0xFF000000),
        emergencyContainer = Color(0xFFFFDCD7),
        onEmergencyContainer = Color(0xFF000000),
    )

internal val StatusColorsHighContrastDark =
    StatusColors(
        successContainer = Color(0xFF0E3B22),
        onSuccessContainer = Color(0xFFFFFFFF),
        warningContainer = Color(0xFF4A3800),
        onWarningContainer = Color(0xFFFFFFFF),
        emergencyContainer = Color(0xFF6B0F09),
        onEmergencyContainer = Color(0xFFFFFFFF),
    )
