import SwiftUI

/// Which colours the app uses: the system's light/dark setting or a fixed choice.
enum ThemeMode: String, CaseIterable, Identifiable, Sendable {
    /// Follow the system.
    case system
    case light
    case dark

    /// Dark with true black surfaces: saves power on OLED screens and is easier on the eyes at night.
    case amoled

    var id: String { rawValue }
}

/// The appearance choices of the user, as pure data: which palette and which text size result from them is decided
/// by the functions here and by ``crisisTheme(_:)``.
struct ThemeSettings: Equatable, Sendable {
    var mode: ThemeMode = .system

    /// Strong black and white colours with solid outlines.
    var highContrast = false

    /// Extra text size on top of the system's Dynamic Type setting; one of ``textScales``.
    var textScale: Double = 1.0

    /// The text sizes offered in the settings: normal, large, larger, largest (the steps of the Android client).
    static let textScales: [Double] = [1.0, 1.15, 1.3, 1.5]

    /// The offered size closest to `value` (for values stored by another version).
    static func nearestTextScale(_ value: Double) -> Double {
        textScales.min { abs($0 - value) < abs($1 - value) } ?? 1.0
    }

    /// Whether the dark palette applies, given the system's own setting.
    func isDark(systemDark: Bool) -> Bool {
        switch mode {
        case .system: return systemDark
        case .light: return false
        case .dark, .amoled: return true
        }
    }

    var isAmoled: Bool { mode == .amoled }

    /// What to tell SwiftUI: `nil` follows the system.
    var preferredColorScheme: ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark, .amoled: return .dark
        }
    }

    /// How many Dynamic Type steps the chosen text size adds: 0, 1, 2 or 3.
    var textSizeSteps: Int {
        Self.textScales.firstIndex(of: Self.nearestTextScale(textScale)) ?? 0
    }

    /// The Dynamic Type size to use when the system's is `base`: the same, moved up by the chosen steps (never beyond
    /// the largest accessibility size).
    func dynamicTypeSize(for base: DynamicTypeSize) -> DynamicTypeSize {
        let all = DynamicTypeSize.allCases
        guard let index = all.firstIndex(of: base) else { return base }
        return all[min(index + textSizeSteps, all.count - 1)]
    }

    func palette(systemDark: Bool) -> Palette {
        Palette.make(dark: isDark(systemDark: systemDark), highContrast: highContrast, amoled: isAmoled)
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.light
}

extension EnvironmentValues {
    /// The colours of the current theme.
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

private struct CrisisThemeModifier: ViewModifier {
    let settings: ThemeSettings

    // Read outside the content: the system's own setting, not what `preferredColorScheme` makes of it.
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.dynamicTypeSize) private var systemTypeSize

    func body(content: Content) -> some View {
        let palette = settings.palette(systemDark: systemScheme == .dark)
        content
            .environment(\.palette, palette)
            .tint(palette.primary.color)
            .foregroundStyle(palette.onBackground.color)
            .dynamicTypeSize(settings.dynamicTypeSize(for: systemTypeSize))
            .background(palette.background.color.ignoresSafeArea())
            .preferredColorScheme(settings.preferredColorScheme)
    }
}

extension View {
    /// Applies the palette, the extra text size and the light/dark choice of `settings` to everything inside.
    func crisisTheme(_ settings: ThemeSettings) -> some View {
        modifier(CrisisThemeModifier(settings: settings))
    }
}

/// The type scale: Dynamic Type text styles, so that everything grows with the system setting and the extra size chosen
/// in the app. The names are those of the Android client's Material typography.
enum TypeScale {
    static let display = Font.largeTitle.weight(.semibold)
    static let headline = Font.title.weight(.semibold)
    static let title = Font.title3.weight(.semibold)
    static let titleSmall = Font.headline
    static let bodyLarge = Font.body
    static let body = Font.subheadline
    static let bodySmall = Font.footnote
    static let label = Font.subheadline.weight(.medium)
    static let monospace = Font.system(.title3, design: .monospaced)
    static let monospaceSmall = Font.system(.footnote, design: .monospaced)
}
