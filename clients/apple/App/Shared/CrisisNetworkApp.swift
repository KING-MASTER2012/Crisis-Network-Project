import SwiftUI

/// Phases I0 to I2: the self-test and the Bluetooth test bench. The real screens come in I4 and later.
@main
struct CrisisNetworkApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                SelfCheckView()
                    .tabItem { Label("Self-test", systemImage: "checkmark.seal") }
                BleLinkTestView()
                    .tabItem { Label("Bluetooth", systemImage: "dot.radiowaves.left.and.right") }
            }
        }
    }
}
