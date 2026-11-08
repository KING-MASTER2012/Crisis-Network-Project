import SwiftUI

/// Phases I0 to I3: the self-test and the Bluetooth test bench. The real screens come in I4 and later.
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
            TabView {
                SelfCheckView()
                    .tabItem { Label("Self-test", systemImage: "checkmark.seal") }
                BleLinkTestView()
                    .tabItem { Label("Bluetooth", systemImage: "dot.radiowaves.left.and.right") }
            }
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
