import SwiftUI

/// The home screen: whether this device is part of the mesh, with how many neighbours it reaches, the button to join or
/// leave, and the safety number to read to a person who is met.
struct HomeView: View {
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @StateObject private var access = BluetoothAccess()
    @State private var joinAfterPermission = false

    var body: some View {
        ScreenColumn {
            meshCard
            if let error = mesh.errorText {
                InfoCard(title: "home_error_title", tone: .error) { Text(error) }
            }
            if !mesh.running {
                BluetoothBlockerCard(access: access)
            }
            identityCard
        }
        .navigationTitle("app_name")
        .onAppear { access.refresh() }
        .onChange(of: access.readiness) { _, readiness in
            // The permission was just given (or Bluetooth just switched on): go on with what the person asked for.
            if joinAfterPermission, readiness == .ready {
                joinAfterPermission = false
                Task { await mesh.start() }
            }
        }
    }

    private var neighbours: Int {
        mesh.links.filter(\.active).count
    }

    private var status: (key: LocalizedStringKey, tone: Tone) {
        if mesh.busy { return (mesh.running ? "home_status_stopping" : "home_status_starting", .neutral) }
        guard mesh.running else { return ("home_status_offline", .neutral) }
        return neighbours > 0 ? ("home_status_connected", .success) : ("home_status_searching", .neutral)
    }

    private var meshCard: some View {
        InfoCard(title: "home_mesh_title") {
            StatusPill(status.key, tone: status.tone)
            if mesh.running, neighbours > 0 {
                Text("home_neighbours \(neighbours)").font(TypeScale.titleSmall)
            }
            if mesh.running {
                Text("home_profile_line \(profileName)")
                if neighbours == 0 { Text("home_searching_hint") }
            } else if !mesh.busy {
                Text("home_offline_hint")
            }
            if mesh.running {
                SecondaryButton("home_leave") { Task { await mesh.stop() } }.disabled(mesh.busy)
            } else {
                PrimaryButton("home_join") { join() }.disabled(mesh.busy)
            }
        }
    }

    /// The name of the profile in the language chosen in the app (it is put into another sentence, so it is a string).
    private var profileName: String {
        L10n.string("mesh_profile_\(mesh.effectiveProfile.rawValue)", language: prefs.language)
    }

    @ViewBuilder
    private var identityCard: some View {
        if let number = prefs.safetyNumber {
            InfoCard(title: "home_identity_title") {
                Text(OnboardingFlow.formatSafetyNumber(number))
                    .font(TypeScale.monospace)
                    .textSelection(.enabled)
                Text("home_identity_hint").font(TypeScale.bodySmall)
            }
        }
    }

    private func join() {
        switch access.readiness {
        case .ready, .unknown:
            Task { await mesh.start() }
        case .notDetermined, .poweredOff:
            joinAfterPermission = true
            access.request()
        case .denied, .restricted, .unsupported:
            // The card below says why and what to do.
            break
        }
    }
}
