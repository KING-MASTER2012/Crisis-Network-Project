#if os(macOS)
import AppKit
import Combine
import CoreServices
import ServiceManagement
import SwiftUI

/// Everything of the Mac app that is not the screens (phase I8): the window that can be closed while the mesh runs, the
/// menu bar icon, the Dock icon that can be hidden, opening at login, the radio's idea of "foreground" and a clean
/// stop when the app quits.
///
/// **The mesh outlives the window.** Closing the window never stops the node: the app goes on in the menu bar (and the
/// Dock) and `applicationShouldTerminateAfterLastWindowClosed` says no. Quitting the app (Cmd-Q or "Quit" in the menu
/// bar menu) stops the node first, so that the database is closed properly, and keeps the "join the mesh at launch"
/// choice, so that the next launch joins again.
@MainActor
final class MacDesktop {
    static let shared = MacDesktop()

    /// The identifier of the one window of the app.
    static let mainWindowID = "main"

    /// The system started the app because the person logged in (a login item), not the person.
    private(set) var launchedAtLogin = false

    private var hideNextWindow = false
    private var observers: [NSObjectProtocol] = []
    private var openWindowAction: OpenWindowAction?
    private var lastPresence: Bool?
    private var choicesObserver: AnyCancellable?

    // MARK: Launch

    func willFinishLaunching() {
        launchedAtLogin = Self.detectLoginLaunch()
        hideNextWindow = launchedAtLogin && MacPreferences.shared.choices.effectiveStartHidden
    }

    func didFinishLaunching() {
        applyActivationPolicy()
        startObserving()
        updatePresence()
        // The menu bar icon can also be taken out of the menu bar by dragging it away (the system then switches the
        // choice off): the Dock icon must come back whatever way the choices changed. `@Published` announces a change
        // before it is made, so the policy is applied on the next turn of the run loop.
        choicesObserver = MacPreferences.shared.$choices
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink { _ in Task { @MainActor in MacDesktop.shared.applyActivationPolicy() } }
    }

    /// `true` when the launch event says that the app was opened as a login item. When the system does not say so the
    /// window simply opens, which does no harm.
    private static func detectLoginLaunch() -> Bool {
        guard let event = NSAppleEventManager.shared().currentAppleEvent else { return false }
        let data = event.paramDescriptor(forKeyword: AEKeyword(keyAEPropData))
        return event.eventID == AEEventID(kAEOpenApplication)
            && data?.enumCodeValue == OSType(keyAELaunchedAsLogInItem)
    }

    // MARK: Windows

    /// Told by the window when it appears. A launch at login with the menu bar icon in place closes the first window, so
    /// that nothing opens on screen while the person is logging in. It is opened again from the menu bar icon.
    func windowAppeared(_ window: NSWindow) {
        guard hideNextWindow else { return }
        hideNextWindow = false
        DispatchQueue.main.async { window.close() }
    }

    func register(_ action: OpenWindowAction) {
        openWindowAction = action
    }

    /// Brings the window to the front, opening it when it was closed.
    func showMainWindow() {
        NSApplication.shared.activate()
        openWindowAction?(id: Self.mainWindowID)
    }

    /// Shows a destination of the shell (a tap on a notification, the Go menu, the settings command).
    func show(_ destination: Destination) {
        AppNavigator.shared.destinationRequest = destination
        showMainWindow()
    }

    // MARK: Dock icon

    /// A regular app has a Dock icon; an accessory app has none (only the menu bar icon). Called at launch and whenever
    /// the choices change. The Dock icon only goes while the menu bar icon exists (``MacShellChoices/effectiveHideDock``).
    func applyActivationPolicy() {
        let wanted: NSApplication.ActivationPolicy = MacPreferences.shared.choices.effectiveHideDock ? .accessory : .regular
        let app = NSApplication.shared
        guard app.activationPolicy() != wanted else { return }
        app.setActivationPolicy(wanted)
        if wanted == .regular { app.activate() }
    }

    // MARK: Presence and the system

    /// Tells the node whether the person can see the app, for notifications (a message that arrives while the app is
    /// on screen does not make one). The radio does **not** care: see ``BleLinkTestModel/appForegroundChanged(_:)``.
    private func updatePresence(excluding closing: ObjectIdentifier? = nil) {
        let app = NSApplication.shared
        let visible = app.windows.filter { window in
            window.isVisible && !window.isMiniaturized && window.canBecomeMain && ObjectIdentifier(window) != closing
        }.count
        let onScreen = MacPresence.isOnScreen(appActive: app.isActive, visibleWindows: visible)
        guard onScreen != lastPresence else { return }
        lastPresence = onScreen
        BleLinkTestModel.shared.appForegroundChanged(onScreen)
    }

    private func startObserving() {
        guard observers.isEmpty else { return }
        let names: [Notification.Name] = [
            NSApplication.didBecomeActiveNotification,
            NSApplication.didResignActiveNotification,
            NSWindow.didBecomeKeyNotification,
            NSWindow.willCloseNotification,
            NSWindow.didMiniaturizeNotification,
            NSWindow.didDeminiaturizeNotification,
        ]
        for name in names {
            let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                // The window that is closing is still "visible" while its notification is delivered.
                let closing = note.name == NSWindow.willCloseNotification ? (note.object as? NSWindow).map(ObjectIdentifier.init) : nil
                Task { @MainActor in self?.updatePresence(excluding: closing) }
            }
            observers.append(token)
        }
        // The radio stops while the Mac sleeps and the transport starts again by itself when it wakes; the log of the
        // test bench says when, which is what one needs to understand a gap in a measurement.
        let workspace = NSWorkspace.shared.notificationCenter
        observers.append(workspace.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in BleLinkTestModel.shared.noteSystemEvent("Mac going to sleep") }
        })
        observers.append(workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in BleLinkTestModel.shared.noteSystemEvent("Mac woke up") }
        })
    }
}

/// The application delegate of the SwiftUI app (`@NSApplicationDelegateAdaptor`): the things SwiftUI has no scene
/// modifier for.
@MainActor
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    private var terminationAnswered = false

    func applicationWillFinishLaunching(_ notification: Notification) {
        MacDesktop.shared.willFinishLaunching()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        MacDesktop.shared.didFinishLaunching()
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool { true }

    /// The mesh goes on when the window is closed.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// A click on the Dock icon while no window is open.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { MacDesktop.shared.showMainWindow() }
        return true
    }

    /// Stops the node before the process ends, so that the database is closed properly. The system waits for the reply
    /// (`terminateLater`); a node that does not stop within five seconds is left to the process ending.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard BleLinkTestModel.shared.running else { return .terminateNow }
        terminationAnswered = false
        Task { @MainActor in
            await BleLinkTestModel.shared.shutdownForQuit()
            self.answerTermination()
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            self.answerTermination()
        }
        return .terminateLater
    }

    private func answerTermination() {
        guard !terminationAnswered else { return }
        terminationAnswered = true
        NSApplication.shared.reply(toApplicationShouldTerminate: true)
    }
}

// MARK: The window

/// The root of the one window: the app, with a size below which the sidebar and the detail column would not fit.
struct MacRootView: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        RootView()
            .frame(minWidth: 760, minHeight: 520)
            .background(WindowReader { window in MacDesktop.shared.windowAppeared(window) })
            .task { MacDesktop.shared.register(openWindow) }
    }
}

/// Hands the `NSWindow` that a view is in to a closure.
struct WindowReader: NSViewRepresentable {
    let onWindow: @MainActor (NSWindow) -> Void

    func makeNSView(context: Context) -> WindowObservingView {
        let view = WindowObservingView()
        view.onWindow = onWindow
        return view
    }

    func updateNSView(_ view: WindowObservingView, context: Context) {
        view.onWindow = onWindow
    }
}

final class WindowObservingView: NSView {
    var onWindow: (@MainActor (NSWindow) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard let window, let onWindow else { return }
        MainActor.assumeIsolated { onWindow(window) }
    }
}

// MARK: The menu bar

/// What the menu bar shows: a symbol for the state of the mesh and, when there is something unread, its number.
struct MenuBarLabel: View {
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var prefs = UiPreferences.shared
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        let summary = MenuBarModel.summary(mesh: mesh, messaging: messaging, prefs: prefs)
        HStack(spacing: 3) {
            Image(systemName: summary.symbol)
            if summary.unread > 0 { Text(verbatim: "\(summary.unread)") }
        }
        .environment(\.locale, prefs.language.locale)
        .accessibilityLabel("app_name")
        // The menu bar item exists from launch on, also when the window never opens (a launch at login).
        .task { MacDesktop.shared.register(openWindow) }
    }
}

/// The menu of the menu bar icon: the state, the unread count, and the three things one wants from there.
struct MenuBarContent: View {
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var messaging = MessagingModel.shared
    @ObservedObject private var prefs = UiPreferences.shared

    var body: some View {
        let summary = MenuBarModel.summary(mesh: mesh, messaging: messaging, prefs: prefs)
        Group {
            switch summary.state {
            case .setupNeeded: Text("menubar_setup")
            case .off: Text("menubar_off")
            case .busy: Text("menubar_busy")
            case let .on(neighbours): Text("menubar_on \(neighbours)")
            }
            if summary.unread > 0 { Text("chat_unread \(summary.unread)") }
            Divider()
            Button("menubar_open") { MacDesktop.shared.showMainWindow() }
            Button(LocalizedStringKey(summary.isRunning ? "menubar_leave" : "menubar_join")) {
                Task {
                    if summary.isRunning { await mesh.stop() } else { await mesh.start() }
                }
            }
            .disabled(!summary.canToggleMesh)
            Divider()
            Button("menubar_quit") { NSApplication.shared.terminate(nil) }
                .keyboardShortcut("q")
        }
        .environment(\.locale, prefs.language.locale)
    }
}

enum MenuBarModel {
    @MainActor
    static func summary(mesh: BleLinkTestModel, messaging: MessagingModel, prefs: UiPreferences) -> MenuBarSummary {
        MenuBarSummary.make(
            onboardingDone: prefs.onboardingDone,
            running: mesh.running,
            busy: mesh.busy,
            neighbours: mesh.links.filter(\.active).count,
            unread: messaging.unread.chats + messaging.unread.announcements
        )
    }
}

// MARK: Menus of the app

/// The Mac menu bar of the app: Settings on its usual shortcut, a Go menu with the five destinations on Cmd-1 to Cmd-5
/// and a Mesh menu. (The system draws menus in the system language; the language chosen in the app applies to the
/// window and to the menu bar icon's menu.)
struct MacCommands: Commands {
    @ObservedObject private var mesh = BleLinkTestModel.shared
    @ObservedObject private var prefs = UiPreferences.shared

    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button("menu_settings") { MacDesktop.shared.show(.settings) }
                .keyboardShortcut(",", modifiers: .command)
        }
        CommandMenu("menu_go") {
            ForEach(Array(Destination.allCases.enumerated()), id: \.element) { index, destination in
                Button(destination.title) { MacDesktop.shared.show(destination) }
                    .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
            }
        }
        CommandMenu("menu_mesh") {
            Button(LocalizedStringKey(mesh.running ? "menubar_leave" : "menubar_join")) {
                Task {
                    if mesh.running { await mesh.stop() } else { await mesh.start() }
                }
            }
            .keyboardShortcut("j", modifiers: [.command, .shift])
            .disabled(!prefs.onboardingDone || mesh.busy)
        }
    }
}

// MARK: Opening at login

/// The login item service of macOS 13 and later: the app itself, registered with `SMAppService`. Needs no helper app and
/// no entitlement; the person sees and can switch it off in System Settings > General > Login Items.
struct SMAppServiceLoginItem: LoginItemService {
    var state: LoginItemState {
        switch SMAppService.mainApp.status {
        case .enabled: return .enabled
        case .notRegistered: return .notRegistered
        case .requiresApproval: return .requiresApproval
        case .notFound: return .unavailable
        @unknown default: return .unavailable
        }
    }

    func enable() throws {
        try SMAppService.mainApp.register()
    }

    func disable() throws {
        try SMAppService.mainApp.unregister()
    }

    func openSystemSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}
#endif
