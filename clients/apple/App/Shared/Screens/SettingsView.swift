import CrisisCore
import CrisisRuntime
import SwiftUI

/// The settings (phases I4 and I7): appearance (theme, contrast, text size), the language, notifications, privacy (the
/// app switcher), the power profile, the network screen, the message store and the default lifetime of announcements,
/// the identity, what this build is, and a few developer tools.
struct SettingsView: View {
    @ObservedObject private var prefs = UiPreferences.shared
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var nodePrefs = NodePreferences.shared
    @StateObject private var notifications = NotificationAccess()
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Form {
            appearanceSection
            languageSection
            notificationsSection
            privacySection
            powerSection
            networkSection
            storageSection
            lifetimeSection
            identitySection
            aboutSection
            developerSection
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.color)
        .navigationTitle("nav_settings")
        .task { await notifications.refresh() }
        // Back from the system's settings: the permission may have changed there.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await notifications.refresh() } }
        }
    }

    // MARK: Notifications

    private var notificationsSection: some View {
        Section {
            switch notifications.action {
            case .none:
                Text("settings_notifications_on")
            case .askInApp:
                Text("settings_notifications_ask")
                Button("settings_notifications_allow") { Task { await notifications.request() } }
            case .openSettings:
                Text("settings_notifications_off")
                Button("bt_open_settings") { notifications.openSettings() }
            }
        } header: {
            Text("settings_notifications")
        } footer: {
            Text("settings_notifications_hint")
        }
        .listRowBackground(palette.container.color)
    }

    // MARK: Privacy

    @ViewBuilder
    private var privacySection: some View {
        #if os(iOS)
        Section {
            Toggle("settings_hide_switcher", isOn: $prefs.hideInSwitcher)
        } header: {
            Text("settings_privacy")
        } footer: {
            Text("settings_hide_switcher_hint")
        }
        .listRowBackground(palette.container.color)
        #endif
    }

    // MARK: Network, storage, lifetime

    private var networkSection: some View {
        Section {
            NavigationLink("settings_open_network") { NetworkView() }
        } header: {
            Text("net_title")
        }
        .listRowBackground(palette.container.color)
    }

    private var storageSection: some View {
        Section {
            Picker("settings_storage_title", selection: $nodePrefs.storeLimitMb) {
                ForEach(StoreLimits.presetsMb, id: \.self) { mb in
                    Text("settings_storage_option \(mb)").tag(mb)
                }
            }
            if mesh.settingsPending { Text("settings_pending").font(TypeScale.bodySmall) }
        } header: {
            Text("settings_storage_title")
        } footer: {
            Text("settings_storage_hint")
        }
        .listRowBackground(palette.container.color)
    }

    private var lifetimeSection: some View {
        Section {
            Picker("settings_lifetime_title", selection: $nodePrefs.defaultLifetimeSecs) {
                ForEach(DefaultLifetimes.options(), id: \.self) { secs in
                    DurationLabel(secs: secs).tag(secs)
                }
            }
            if mesh.settingsPending { Text("settings_pending").font(TypeScale.bodySmall) }
        } header: {
            Text("settings_lifetime_title")
        } footer: {
            Text("settings_lifetime_hint")
        }
        .listRowBackground(palette.container.color)
    }

    // MARK: About

    private var aboutSection: some View {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let core = coreInfo()
        return Section {
            Text("settings_about_app \(version) \(build)")
            Text("settings_about_core \(core.crateVersion) \(Int(core.apiVersion)) \(Int(core.protocolVersion))")
        } header: {
            Text("settings_about")
        }
        .listRowBackground(palette.container.color)
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
                    language.label.tag(language)
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
    /// read the current one. Only the system choice is translated. (`LocalizedStringKey` has no verbatim initializer,
    /// so the names are built as verbatim `Text`.)
    var label: Text {
        switch self {
        case .system: return Text("lang_system")
        case .english: return Text(verbatim: "English")
        case .turkish: return Text(verbatim: "Türkçe")
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
