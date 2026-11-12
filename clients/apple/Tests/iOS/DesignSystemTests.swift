import SwiftUI
import XCTest
@testable import CrisisNetwork_iOS

/// The design system of phase I4: the palettes meet the contrast the interface needs, and the theme choices turn into the
/// right palette, colour scheme and text size.
final class DesignSystemTests: XCTestCase {
    private let palettes: [(name: String, palette: Palette, highContrast: Bool)] = [
        ("light", .light, false),
        ("dark", .dark, false),
        ("high contrast light", .highContrastLight, true),
        ("high contrast dark", .highContrastDark, true),
        ("black", Palette.dark.amoled, false),
    ]

    private func pairs(_ p: Palette) -> [(String, Swatch, Swatch)] {
        [
            ("text on background", p.onBackground, p.background),
            ("text on surface", p.onSurface, p.surface),
            ("text on card", p.onSurface, p.container),
            ("secondary text on surface variant", p.onSurfaceVariant, p.surfaceVariant),
            ("button text", p.onPrimary, p.primary),
            ("text on primary container", p.onPrimaryContainer, p.primaryContainer),
            ("text on secondary container", p.onSecondaryContainer, p.secondaryContainer),
            ("action text on background", p.primary, p.background),
            ("action text on card", p.primary, p.container),
            ("error text on background", p.error, p.background),
            ("text on error", p.onError, p.error),
            ("text on error container", p.onErrorContainer, p.errorContainer),
            ("text on success", p.status.onSuccessContainer, p.status.successContainer),
            ("text on warning", p.status.onWarningContainer, p.status.warningContainer),
            ("text on emergency", p.status.onEmergencyContainer, p.status.emergencyContainer),
        ]
    }

    func testEveryTextColourHasEnoughContrastOnItsBackground() {
        for entry in palettes {
            for (what, foreground, background) in pairs(entry.palette) {
                let ratio = foreground.contrast(with: background)
                XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(entry.name): \(what) is \(ratio):1")
            }
        }
    }

    func testTheHighContrastPalettesReachSeven() {
        for entry in palettes where entry.highContrast {
            for (what, foreground, background) in pairs(entry.palette) {
                let ratio = foreground.contrast(with: background)
                XCTAssertGreaterThanOrEqual(ratio, 7.0, "\(entry.name): \(what) is \(ratio):1")
            }
        }
    }

    func testTheOutlinesOfControlsAreVisible() {
        for entry in palettes {
            let ratio = entry.palette.outline.contrast(with: entry.palette.background)
            XCTAssertGreaterThanOrEqual(ratio, 3.0, "\(entry.name): the outline is \(ratio):1")
        }
    }

    func testContrastArithmeticMatchesTheWcagExtremes() {
        XCTAssertEqual(Swatch(0xFF000000).contrast(with: Swatch(0xFFFFFFFF)), 21.0, accuracy: 0.01)
        XCTAssertEqual(Swatch(0xFF777777).contrast(with: Swatch(0xFF777777)), 1.0, accuracy: 0.0001)
    }

    func testTheBlackPaletteKeepsTheCardsApartFromTheScreen() {
        let black = Palette.dark.amoled
        XCTAssertEqual(black.background, Swatch(0xFF000000))
        XCTAssertNotEqual(black.container, black.background)
        XCTAssertEqual(black.primary, Palette.dark.primary)
    }

    func testHighContrastWinsAndBlackOnlyAppliesToDark() {
        XCTAssertEqual(Palette.make(dark: false, highContrast: false, amoled: true), .light)
        XCTAssertEqual(Palette.make(dark: true, highContrast: false, amoled: false), .dark)
        XCTAssertEqual(Palette.make(dark: true, highContrast: false, amoled: true), Palette.dark.amoled)
        XCTAssertEqual(Palette.make(dark: true, highContrast: true, amoled: false), .highContrastDark)
        XCTAssertEqual(Palette.make(dark: false, highContrast: true, amoled: false), .highContrastLight)
    }

    // MARK: Theme settings

    func testTheSystemModeFollowsTheSystem() {
        let settings = ThemeSettings()
        XCTAssertTrue(settings.isDark(systemDark: true))
        XCTAssertFalse(settings.isDark(systemDark: false))
        XCTAssertNil(settings.preferredColorScheme)
    }

    func testFixedModesIgnoreTheSystem() {
        var settings = ThemeSettings()
        settings.mode = .light
        XCTAssertFalse(settings.isDark(systemDark: true))
        XCTAssertEqual(settings.preferredColorScheme, .light)
        settings.mode = .dark
        XCTAssertTrue(settings.isDark(systemDark: false))
        XCTAssertEqual(settings.preferredColorScheme, .dark)
        settings.mode = .amoled
        XCTAssertTrue(settings.isDark(systemDark: false))
        XCTAssertTrue(settings.isAmoled)
        XCTAssertEqual(settings.palette(systemDark: false), Palette.dark.amoled)
    }

    func testTheTextSizeMovesDynamicTypeUpByWholeSteps() {
        var settings = ThemeSettings()
        XCTAssertEqual(settings.dynamicTypeSize(for: .large), .large)
        settings.textScale = 1.15
        XCTAssertEqual(settings.dynamicTypeSize(for: .large), .xLarge)
        settings.textScale = 1.3
        XCTAssertEqual(settings.dynamicTypeSize(for: .large), .xxLarge)
        settings.textScale = 1.5
        XCTAssertEqual(settings.dynamicTypeSize(for: .large), .xxxLarge)
    }

    func testTheTextSizeNeverGoesBeyondTheLargestAccessibilitySize() {
        var settings = ThemeSettings()
        settings.textScale = 1.5
        XCTAssertEqual(settings.dynamicTypeSize(for: .accessibility5), .accessibility5)
        XCTAssertEqual(settings.dynamicTypeSize(for: .accessibility4), .accessibility5)
    }

    func testAStoredTextScaleOfAnotherVersionSnapsToAnOfferedSize() {
        XCTAssertEqual(ThemeSettings.nearestTextScale(1.0), 1.0)
        XCTAssertEqual(ThemeSettings.nearestTextScale(1.2), 1.15)
        XCTAssertEqual(ThemeSettings.nearestTextScale(2.0), 1.5)
        XCTAssertEqual(ThemeSettings.nearestTextScale(0.5), 1.0)
    }
}
