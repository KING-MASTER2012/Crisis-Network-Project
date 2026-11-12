import CrisisRuntime
import SwiftUI

/// The settings of phase I4: appearance (theme, contrast, text size), the language, the power profile, the identity and a
/// few developer tools. Notifications, storage and the rest come with I7.
struct SettingsView: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @Environment(\.palette) private var palette

    var body: some View {
        Form {
            appearanceSection
            languageSection
            powerSection
            identitySection
            developerSection
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.color)
        .navigationTitle("nav_settings")
    }

    // MARK: Appearance

    private var appearanceSection: some View {
        Section {
            Picker("settings_theme_title", selection: $prefs.theme.mode) {
                ForEach(ThemeMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            Toggle("settings_high_contrast", isOn: $prefs.theme.highContrast)
            Picker("settings_text_size", selection: $prefs.theme.textScale) {
                ForEach(ThemeSettings.textScales, id: \.self) { scale in
                    Text(ThemeSettings.textScaleTitle(scale)).tag(scale)
                }
            }
            Text("settings_text_sample")
                .font(TypeScale.bodyLarge)
                .accessibilityLabel("settings_text_sample")
        } header: {
            Text("settings_appearance")
        } footer: {
            Text("settings_high_contrast_hint")
        }
        .listRowBackground(palette.container.color)
    }

    // MARK: Language

    private var languageSection: some View {
        Section {
            Picker("settings_language", selection: $prefs.language) {
                ForEach(AppLanguage.allCases) { language in
                    Text(language.title).tag(language)
                }
            }
            #if os(iOS)
            .pickerStyle(.inline)
            #endif
        } header: {
            Text("settings_language")
        }
        .listRowBackground(palette.container.color)
    }

    // MARK: Power

    private var powerSection: some View {
        Section {
            Picker("settings_power_title", selection: $mesh.profile) {
                ForEach(BlePowerProfile.allCases) { profile in
                    Text(LocalizedStringKey("mesh_profile_\(profile.rawValue)")).tag(profile)
                }
            }
            if mesh.lowPowerMode {
                Text("settings_power_low_power").font(TypeScale.bodySmall)
            }
        } header: {
            Text("settings_power_title")
        } footer: {
            Text("settings_power_hint")
        }
        .listRowBackground(palette.container.color)
    }

    // MARK: Identity

    @ViewBuilder
    private var identitySection: some View {
        if let number = prefs.safetyNumber {
            Section {
                Text(OnboardingFlow.formatSafetyNumber(number))
                    .font(TypeScale.monospace)
                    .textSelection(.enabled)
                if let node = prefs.nodeIdHex {
                    LabeledContent("settings_node_id") {
                        Text(OnboardingFlow.shortNodeId(node))
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
            } header: {
                Text("home_identity_title")
            } footer: {
                Text("home_identity_hint")
            }
            .listRowBackground(palette.container.color)
        }
    }

    // MARK: Developer

    private var developerSection: some View {
        Section {
            NavigationLink("settings_open_bluetooth_test") { BleLinkTestView() }
            NavigationLink("settings_selftest") { SelfCheckView() }
            Button("settings_show_intro") { prefs.resetOnboarding() }
        } header: {
            Text("settings_developer")
        }
        .listRowBackground(palette.container.color)
    }
}

extension ThemeMode {
    var title: LocalizedStringKey {
        switch self {
        case .system: return "settings_theme_system"
        case .light: return "settings_theme_light"
        case .dark: return "settings_theme_dark"
        case .amoled: return "settings_theme_amoled"
        }
    }
}

extension AppLanguage {
    /// A language is named in itself, whatever language the app is in, so that it can be found by someone who does not
    /// read the current one. Only the system choice is translated.
    var title: LocalizedStringKey {
        switch self {
        case .system: return "lang_system"
        case .english: return LocalizedStringKey(verbatim: "English")
        case .turkish: return LocalizedStringKey(verbatim: "Türkçe")
        }
    }
}

extension ThemeSettings {
    /// The key of the name of an offered text size.
    static func textScaleTitle(_ scale: Double) -> LocalizedStringKey {
        switch nearestTextScale(scale) {
        case 1.0: return "settings_text_normal"
        case 1.15: return "settings_text_large"
        case 1.3: return "settings_text_larger"
        default: return "settings_text_largest"
        }
    }
}
