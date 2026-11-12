import SwiftUI

/// One colour as `0xAARRGGBB`, with the arithmetic needed to check contrast (WCAG 2.x).
struct Swatch: Equatable, Sendable {
    let argb: UInt32

    init(_ argb: UInt32) {
        self.argb = argb
    }

    private var red: Double { Double((argb >> 16) & 0xFF) / 255 }
    private var green: Double { Double((argb >> 8) & 0xFF) / 255 }
    private var blue: Double { Double(argb & 0xFF) / 255 }

    var color: Color {
        Color(.sRGB, red: red, green: green, blue: blue, opacity: 1)
    }

    /// Relative luminance (0 black, 1 white) as WCAG defines it.
    var luminance: Double {
        func linear(_ channel: Double) -> Double {
            channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// Contrast ratio between two colours, 1 (equal) to 21 (black on white). Text needs 4.5, large text and the
    /// outlines of controls 3; the high-contrast palette aims for 7.
    func contrast(with other: Swatch) -> Double {
        let a = luminance
        let b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

/// The colours of the three kinds of announcement and of positive feedback. They never carry a meaning alone: every use
/// also has a label or an icon.
struct StatusSwatches: Equatable, Sendable {
    let successContainer: Swatch
    let onSuccessContainer: Swatch
    let warningContainer: Swatch
    let onWarningContainer: Swatch
    let emergencyContainer: Swatch
    let onEmergencyContainer: Swatch
}

/// The brand palette: a calm blue for actions, neutral greys for surfaces. It is the one of the Android client
/// (`ui/theme/Color.kt`), so that the two apps look alike; what Android calls the wallpaper colours has no counterpart
/// on Apple platforms.
///
/// Four palettes (light, dark, and a high-contrast version of each) and the black variant of the dark one, which saves
/// power on OLED screens. ``make(dark:highContrast:amoled:)`` picks one.
struct Palette: Equatable, Sendable {
    let primary: Swatch
    let onPrimary: Swatch
    let primaryContainer: Swatch
    let onPrimaryContainer: Swatch
    let secondary: Swatch
    let onSecondary: Swatch
    let secondaryContainer: Swatch
    let onSecondaryContainer: Swatch
    let error: Swatch
    let onError: Swatch
    let errorContainer: Swatch
    let onErrorContainer: Swatch
    let background: Swatch
    let onBackground: Swatch
    let surface: Swatch
    let onSurface: Swatch
    let surfaceVariant: Swatch
    let onSurfaceVariant: Swatch
    /// The background of cards: a little apart from the screen.
    let container: Swatch
    let outline: Swatch
    let outlineVariant: Swatch
    let status: StatusSwatches

    static let light = Palette(
        primary: Swatch(0xFF0B5CAD), onPrimary: Swatch(0xFFFFFFFF),
        primaryContainer: Swatch(0xFFD3E4FF), onPrimaryContainer: Swatch(0xFF001C3A),
        secondary: Swatch(0xFF565F71), onSecondary: Swatch(0xFFFFFFFF),
        secondaryContainer: Swatch(0xFFDAE2F9), onSecondaryContainer: Swatch(0xFF131C2B),
        error: Swatch(0xFFBA1A1A), onError: Swatch(0xFFFFFFFF),
        errorContainer: Swatch(0xFFFFDAD6), onErrorContainer: Swatch(0xFF410002),
        background: Swatch(0xFFF9F9FF), onBackground: Swatch(0xFF191C20),
        surface: Swatch(0xFFF9F9FF), onSurface: Swatch(0xFF191C20),
        surfaceVariant: Swatch(0xFFE0E2EC), onSurfaceVariant: Swatch(0xFF44474E),
        container: Swatch(0xFFECEDF4),
        outline: Swatch(0xFF74777F), outlineVariant: Swatch(0xFFC4C6D0),
        status: StatusSwatches(
            successContainer: Swatch(0xFFC8F0D2), onSuccessContainer: Swatch(0xFF002111),
            warningContainer: Swatch(0xFFFFE08A), onWarningContainer: Swatch(0xFF241A00),
            emergencyContainer: Swatch(0xFFFFD9D4), onEmergencyContainer: Swatch(0xFF410002)
        )
    )

    static let dark = Palette(
        primary: Swatch(0xFFA5C8FF), onPrimary: Swatch(0xFF00315F),
        primaryContainer: Swatch(0xFF004789), onPrimaryContainer: Swatch(0xFFD3E4FF),
        secondary: Swatch(0xFFBEC6DC), onSecondary: Swatch(0xFF283141),
        secondaryContainer: Swatch(0xFF3E4758), onSecondaryContainer: Swatch(0xFFDAE2F9),
        error: Swatch(0xFFFFB4AB), onError: Swatch(0xFF690005),
        errorContainer: Swatch(0xFF93000A), onErrorContainer: Swatch(0xFFFFDAD6),
        background: Swatch(0xFF111318), onBackground: Swatch(0xFFE2E2E9),
        surface: Swatch(0xFF111318), onSurface: Swatch(0xFFE2E2E9),
        surfaceVariant: Swatch(0xFF44474E), onSurfaceVariant: Swatch(0xFFC4C6D0),
        container: Swatch(0xFF1D2024),
        outline: Swatch(0xFF8E9099), outlineVariant: Swatch(0xFF44474E),
        status: StatusSwatches(
            successContainer: Swatch(0xFF1B5133), onSuccessContainer: Swatch(0xFFC8F0D2),
            warningContainer: Swatch(0xFF5A4300), onWarningContainer: Swatch(0xFFFFE08A),
            emergencyContainer: Swatch(0xFF8B1A12), onEmergencyContainer: Swatch(0xFFFFDAD6)
        )
    )

    /// Black on white, a dark blue for actions, outlines as strong as the text.
    static let highContrastLight = Palette(
        primary: Swatch(0xFF00346B), onPrimary: Swatch(0xFFFFFFFF),
        primaryContainer: Swatch(0xFFDCE8FF), onPrimaryContainer: Swatch(0xFF000000),
        secondary: Swatch(0xFF1F2837), onSecondary: Swatch(0xFFFFFFFF),
        secondaryContainer: Swatch(0xFFE3E8F5), onSecondaryContainer: Swatch(0xFF000000),
        error: Swatch(0xFF8C0009), onError: Swatch(0xFFFFFFFF),
        errorContainer: Swatch(0xFFFFE3E0), onErrorContainer: Swatch(0xFF000000),
        background: Swatch(0xFFFFFFFF), onBackground: Swatch(0xFF000000),
        surface: Swatch(0xFFFFFFFF), onSurface: Swatch(0xFF000000),
        surfaceVariant: Swatch(0xFFEDEDED), onSurfaceVariant: Swatch(0xFF000000),
        container: Swatch(0xFFEDEDED),
        outline: Swatch(0xFF000000), outlineVariant: Swatch(0xFF3A3A3A),
        status: StatusSwatches(
            successContainer: Swatch(0xFFD7F5DF), onSuccessContainer: Swatch(0xFF000000),
            warningContainer: Swatch(0xFFFFEFB0), onWarningContainer: Swatch(0xFF000000),
            emergencyContainer: Swatch(0xFFFFDCD7), onEmergencyContainer: Swatch(0xFF000000)
        )
    )

    static let highContrastDark = Palette(
        primary: Swatch(0xFFCFE2FF), onPrimary: Swatch(0xFF000000),
        primaryContainer: Swatch(0xFF1B3A66), onPrimaryContainer: Swatch(0xFFFFFFFF),
        secondary: Swatch(0xFFDDE4F5), onSecondary: Swatch(0xFF000000),
        secondaryContainer: Swatch(0xFF2B3446), onSecondaryContainer: Swatch(0xFFFFFFFF),
        error: Swatch(0xFFFFB4AB), onError: Swatch(0xFF000000),
        errorContainer: Swatch(0xFF5C0008), onErrorContainer: Swatch(0xFFFFFFFF),
        background: Swatch(0xFF000000), onBackground: Swatch(0xFFFFFFFF),
        surface: Swatch(0xFF000000), onSurface: Swatch(0xFFFFFFFF),
        surfaceVariant: Swatch(0xFF1A1A1A), onSurfaceVariant: Swatch(0xFFFFFFFF),
        container: Swatch(0xFF1A1A1A),
        outline: Swatch(0xFFFFFFFF), outlineVariant: Swatch(0xFFC8C8C8),
        status: StatusSwatches(
            successContainer: Swatch(0xFF0E3B22), onSuccessContainer: Swatch(0xFFFFFFFF),
            warningContainer: Swatch(0xFF4A3800), onWarningContainer: Swatch(0xFFFFFFFF),
            emergencyContainer: Swatch(0xFF6B0F09), onEmergencyContainer: Swatch(0xFFFFFFFF)
        )
    )

    /// The dark palette with true black surfaces; the cards keep a hint of grey so that they stay visible.
    var amoled: Palette {
        Palette(
            primary: primary, onPrimary: onPrimary, primaryContainer: primaryContainer, onPrimaryContainer: onPrimaryContainer,
            secondary: secondary, onSecondary: onSecondary, secondaryContainer: secondaryContainer, onSecondaryContainer: onSecondaryContainer,
            error: error, onError: onError, errorContainer: errorContainer, onErrorContainer: onErrorContainer,
            background: Swatch(0xFF000000), onBackground: onBackground,
            surface: Swatch(0xFF000000), onSurface: onSurface,
            surfaceVariant: surfaceVariant, onSurfaceVariant: onSurfaceVariant,
            container: Swatch(0xFF121212),
            outline: outline, outlineVariant: outlineVariant,
            status: status
        )
    }

    /// High contrast wins over the rest; black surfaces only apply to the dark palette.
    static func make(dark: Bool, highContrast: Bool, amoled: Bool) -> Palette {
        let base: Palette
        switch (highContrast, dark) {
        case (true, true): base = .highContrastDark
        case (true, false): base = .highContrastLight
        case (false, true): base = .dark
        case (false, false): base = .light
        }
        return dark && amoled ? base.amoled : base
    }
}

extension Color {
    init(_ swatch: Swatch) {
        self = swatch.color
    }
}
