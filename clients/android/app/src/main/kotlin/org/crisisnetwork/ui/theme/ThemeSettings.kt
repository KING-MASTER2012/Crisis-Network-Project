package org.crisisnetwork.android.ui.theme

import kotlin.math.abs

/** Which colours the app uses: the system's day/night setting or a fixed choice. */
enum class ThemeMode {
    /** Follow the system's dark mode. */
    System,
    Light,
    Dark,

    /** Dark with true black surfaces: saves power on OLED screens and is easier on the eyes at night. */
    Amoled,
}

/**
 * The appearance choices of the user (phase A4). Pure data: which scheme and which text size
 * result from them is decided by the functions below and by `CrisisTheme`.
 *
 * @property dynamicColor Use the colours of the wallpaper (Android 12 and newer).
 * @property highContrast Strong black and white colours with solid outlines. Takes the place of
 *   the dynamic colours: their contrast cannot be guaranteed.
 * @property textScale Extra text size on top of the system's font size setting; one of [TEXT_SCALES].
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val highContrast: Boolean = false,
    val textScale: Float = 1.0f,
) {
    /** Whether the dark scheme applies, given the system's own setting. */
    fun isDark(systemDark: Boolean): Boolean =
        when (mode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark, ThemeMode.Amoled -> true
        }

    /** Black surfaces on top of the dark scheme. */
    val isAmoled: Boolean get() = mode == ThemeMode.Amoled

    /** The wallpaper colours are wanted and allowed (the device must support them as well). */
    val dynamicEnabled: Boolean get() = dynamicColor && !highContrast

    companion object {
        /** The text sizes offered in the settings: normal, large, larger, largest. */
        val TEXT_SCALES: List<Float> = listOf(1.0f, 1.15f, 1.3f, 1.5f)

        /** The offered size closest to [value] (for values that were stored by another version). */
        fun nearestTextScale(value: Float): Float = TEXT_SCALES.minByOrNull { abs(it - value) } ?: 1.0f
    }
}
