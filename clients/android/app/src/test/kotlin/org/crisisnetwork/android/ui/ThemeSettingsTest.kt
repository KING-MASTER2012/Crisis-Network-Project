package org.crisisnetwork.android.ui

import org.crisisnetwork.android.ui.shell.WidthClass
import org.crisisnetwork.android.ui.theme.ThemeMode
import org.crisisnetwork.android.ui.theme.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {
    @Test
    fun theModeDecidesWhetherTheSystemIsFollowed() {
        assertTrue(ThemeSettings(mode = ThemeMode.System).isDark(systemDark = true))
        assertFalse(ThemeSettings(mode = ThemeMode.System).isDark(systemDark = false))
        assertFalse(ThemeSettings(mode = ThemeMode.Light).isDark(systemDark = true))
        assertTrue(ThemeSettings(mode = ThemeMode.Dark).isDark(systemDark = false))
        assertTrue(ThemeSettings(mode = ThemeMode.Amoled).isDark(systemDark = false))
    }

    @Test
    fun onlyTheAmoledModeUsesBlackSurfaces() {
        assertTrue(ThemeSettings(mode = ThemeMode.Amoled).isAmoled)
        for (mode in listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)) assertFalse(ThemeSettings(mode = mode).isAmoled)
    }

    @Test
    fun highContrastTakesThePlaceOfTheWallpaperColours() {
        assertTrue(ThemeSettings(dynamicColor = true, highContrast = false).dynamicEnabled)
        assertFalse(ThemeSettings(dynamicColor = true, highContrast = true).dynamicEnabled)
        assertFalse(ThemeSettings(dynamicColor = false, highContrast = false).dynamicEnabled)
    }

    @Test
    fun aStoredTextScaleBecomesTheNearestOfferedOne() {
        assertEquals(1.0f, ThemeSettings.nearestTextScale(1.0f), 0f)
        assertEquals(1.15f, ThemeSettings.nearestTextScale(1.2f), 0f)
        assertEquals(1.3f, ThemeSettings.nearestTextScale(1.35f), 0f)
        assertEquals(1.5f, ThemeSettings.nearestTextScale(3.0f), 0f)
        assertEquals(1.0f, ThemeSettings.nearestTextScale(0.2f), 0f)
    }

    @Test
    fun theOfferedTextScalesGrowAndStartAtNormal() {
        val scales = ThemeSettings.TEXT_SCALES
        assertEquals(1.0f, scales.first(), 0f)
        assertEquals(scales.sorted(), scales)
    }

    @Test
    fun theWidthClassesFollowTheMaterialBreakpoints() {
        assertEquals(WidthClass.Compact, WidthClass.of(360f))
        assertEquals(WidthClass.Compact, WidthClass.of(599.9f))
        assertEquals(WidthClass.Medium, WidthClass.of(600f))
        assertEquals(WidthClass.Medium, WidthClass.of(839.9f))
        assertEquals(WidthClass.Expanded, WidthClass.of(840f))
        assertEquals(WidthClass.Expanded, WidthClass.of(1280f))
    }
}
