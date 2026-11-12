import SwiftUI

/// Phase I4: the introduction, then the shell (tabs on an iPhone, sidebar on an iPad and a Mac). The Bluetooth test bench
/// and the self-test of the earlier phases moved to the developer section of the settings.
///
/// The node is started in `init`, which also runs when the system relaunches the app in the background for a Bluetooth
/// event (state restoration): the transport must exist before any scene does.
@main
struct CrisisNetworkApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        Task { @MainActor in BleLinkTestModel.shared.resumeIfEnabled() }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: BleLinkTestModel.shared.appForegroundChanged(true)
            case .background: BleLinkTestModel.shared.appForegroundChanged(false)
            default: break
            }
        }
    }
}
