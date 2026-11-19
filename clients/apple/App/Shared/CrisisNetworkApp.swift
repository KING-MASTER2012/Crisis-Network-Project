import SwiftUI
import UserNotifications

/// Phases I4 to I7: the introduction, then the shell (tabs on an iPhone, sidebar on an iPad and a Mac). The Bluetooth test bench
/// and the self-test of the earlier phases moved to the developer section of the settings.
///
/// The node is started in `init`, which also runs when the system relaunches the app in the background for a Bluetooth
/// event (state restoration): the transport must exist before any scene does.
///
/// **On a Mac (phase I8)** the app is one window plus a menu bar icon, and the node outlives the window: see `MacApp.swift`.
/// What the scene phase tells an iPhone app (on screen or not) the Mac app takes from its own windows and from the active
/// state of the app, through `MacDesktop`.
@main
struct CrisisNetworkApp: App {
    #if os(macOS)
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @ObservedObject private var macPrefs = MacPreferences.shared
    #else
    @Environment(\.scenePhase) private var scenePhase
    #endif

    init() {
        // Before any scene: a tap on a notification that starts the app must reach the router.
        UNUserNotificationCenter.current().delegate = NotificationRouter.shared
        Task { @MainActor in
            BleLinkTestModel.shared.resumeIfEnabled()
            ContactsModel.shared.observe(BleLinkTestModel.shared)
            MessagingModel.shared.observe(BleLinkTestModel.shared)
            MessageNotifier.shared.registerCategories()
        }
    }

    var body: some Scene {
        #if os(macOS)
        Window("app_name", id: MacDesktop.mainWindowID) {
            MacRootView()
        }
        .windowResizability(.contentMinSize)
        .commands { MacCommands() }

        MenuBarExtra(isInserted: $macPrefs.choices.showMenuBarIcon) {
            MenuBarContent()
        } label: {
            MenuBarLabel()
        }
        .menuBarExtraStyle(.menu)
        #else
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
        #endif
    }
}
