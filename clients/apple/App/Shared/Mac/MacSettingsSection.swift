#if os(macOS)
import SwiftUI

/// The Mac section of the settings (phase I8): the menu bar icon, the Dock icon and opening the app at login.
struct MacSettingsSection: View {
    @ObservedObject private var mac = MacPreferences.shared
    @StateObject private var login = LoginItemModel(service: SMAppServiceLoginItem())
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Section {
            Toggle("settings_mac_menubar", isOn: $mac.choices.showMenuBarIcon)
            Toggle("settings_mac_dock", isOn: $mac.choices.hideDockIcon)
                .disabled(!mac.choices.showMenuBarIcon)
            Toggle("settings_mac_login", isOn: loginBinding)
                .disabled(LoginItemPlan.decide(login.state) == .unavailable)
            Toggle("settings_mac_hidden", isOn: $mac.choices.startHiddenAtLogin)
                .disabled(!mac.choices.showMenuBarIcon)
            loginNotes
        } header: {
            Text("settings_mac")
        } footer: {
            Text("settings_mac_hint")
        }
        .listRowBackground(palette.container.color)
        .task { login.refresh() }
        // The Dock icon follows the choices at once.
        .onChange(of: mac.choices) { _, _ in MacDesktop.shared.applyActivationPolicy() }
        // Back from System Settings: the person may have allowed the login item there.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { login.refresh() }
        }
    }

    private var loginBinding: Binding<Bool> {
        Binding(
            get: { LoginItemPlan.isOn(login.state) },
            set: { login.setEnabled($0) }
        )
    }

    @ViewBuilder
    private var loginNotes: some View {
        switch LoginItemPlan.decide(login.state) {
        case .openSystemSettings:
            Text("settings_mac_login_approval").font(TypeScale.bodySmall)
            Button("settings_mac_login_open") { login.openSystemSettings() }
        case .unavailable:
            Text("settings_mac_login_unavailable").font(TypeScale.bodySmall)
        case .offerEnable, .offerDisable:
            EmptyView()
        }
        if let error = login.errorText {
            Text("settings_mac_login_error \(error)").font(TypeScale.bodySmall)
        }
    }
}
#endif
