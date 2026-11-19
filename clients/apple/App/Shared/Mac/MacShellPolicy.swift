import Foundation

/// The rules behind the Mac-specific parts of the app (phase I8): how the app sits on the desktop (menu bar icon, Dock
/// icon, start at login), when the window counts as "on screen" and what the settings offer for opening the app at
/// login. No UI and no AppKit: the code compiles for every platform, so the rules are tested with the rest of the app
/// on the simulator; what uses them (`MacApp.swift`, `MacSettingsSection.swift`) exists on the Mac only.

// MARK: How the app sits on the desktop

/// What the person chose for the Mac. The defaults are the ones of a normal Mac app: a Dock icon, plus a menu bar
/// icon because the mesh keeps running when the window is closed and the person needs a way back to it.
struct MacShellChoices: Equatable, Sendable {
    /// The icon in the menu bar: the state of the mesh, a menu to open the app or to join and leave the mesh.
    var showMenuBarIcon = true

    /// Only the menu bar icon is left (no Dock icon, no app menu while the app is in the background).
    var hideDockIcon = false

    /// When the system opens the app at login, the window stays closed and only the menu bar icon shows.
    var startHiddenAtLogin = true

    /// The Dock icon is only hidden while the menu bar icon exists: a Mac app with neither could not be reached again.
    var effectiveHideDock: Bool { hideDockIcon && showMenuBarIcon }

    /// Same reason: a window that stays closed needs the menu bar icon to be opened from.
    var effectiveStartHidden: Bool { startHiddenAtLogin && showMenuBarIcon }

    enum Keys {
        static let menuBar = "mac.menuBarIcon"
        static let hideDock = "mac.hideDockIcon"
        static let startHidden = "mac.startHiddenAtLogin"
    }

    static func load(from defaults: UserDefaults) -> MacShellChoices {
        var choices = MacShellChoices()
        if let value = defaults.object(forKey: Keys.menuBar) as? Bool { choices.showMenuBarIcon = value }
        if let value = defaults.object(forKey: Keys.hideDock) as? Bool { choices.hideDockIcon = value }
        if let value = defaults.object(forKey: Keys.startHidden) as? Bool { choices.startHiddenAtLogin = value }
        return choices
    }

    func save(to defaults: UserDefaults) {
        defaults.set(showMenuBarIcon, forKey: Keys.menuBar)
        defaults.set(hideDockIcon, forKey: Keys.hideDock)
        defaults.set(startHiddenAtLogin, forKey: Keys.startHidden)
    }
}

/// The holder of ``MacShellChoices`` for the app; saves a choice at once.
@MainActor
final class MacPreferences: ObservableObject {
    static let shared = MacPreferences()

    private let defaults: UserDefaults

    @Published var choices: MacShellChoices {
        didSet { choices.save(to: defaults) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        choices = MacShellChoices.load(from: defaults)
    }
}

// MARK: Is the app on screen?

enum MacPresence {
    /// Whether the person can see the app: it is the active app **and** one of its windows is open on screen. A Mac app
    /// with its window closed is still the active app for a moment, and a window behind other apps is on screen but
    /// not looked at; in both cases a message should make a notification. (On iOS the scene phase says the same.)
    static func isOnScreen(appActive: Bool, visibleWindows: Int) -> Bool {
        appActive && visibleWindows > 0
    }
}

// MARK: The menu bar icon

/// What the menu bar icon and the first lines of its menu say.
struct MenuBarSummary: Equatable, Sendable {
    enum State: Equatable, Sendable {
        /// The introduction was not completed: nothing to join yet.
        case setupNeeded

        /// The mesh is off.
        case off

        /// The node is starting or stopping.
        case busy

        /// The mesh runs, with this many neighbours connected.
        case on(neighbours: Int)
    }

    let state: State

    /// The unread chat messages and announcements together.
    let unread: Int

    static func make(onboardingDone: Bool, running: Bool, busy: Bool, neighbours: Int, unread: Int) -> MenuBarSummary {
        let state: State
        if !onboardingDone {
            state = .setupNeeded
        } else if busy {
            state = .busy
        } else if running {
            state = .on(neighbours: max(neighbours, 0))
        } else {
            state = .off
        }
        return MenuBarSummary(state: state, unread: max(unread, 0))
    }

    /// An SF Symbol that both exist on every supported system. The state is also written in the menu, so the picture
    /// never carries the meaning alone.
    var symbol: String {
        switch state {
        case .on: return "dot.radiowaves.left.and.right"
        case .setupNeeded, .off, .busy: return "antenna.radiowaves.left.and.right.slash"
        }
    }

    /// Whether joining or leaving the mesh can be offered now.
    var canToggleMesh: Bool {
        switch state {
        case .off, .on: return true
        case .setupNeeded, .busy: return false
        }
    }

    var isRunning: Bool {
        if case .on = state { return true }
        return false
    }
}

// MARK: Opening the app at login

/// What the system says about the login item of this app (`SMAppService.mainApp`), in the app's own words.
enum LoginItemState: Equatable, Sendable {
    /// Not registered: the app does not open at login.
    case notRegistered

    /// Registered and allowed.
    case enabled

    /// Registered, but the person has to allow it in System Settings > General > Login Items.
    case requiresApproval

    /// The system cannot find the app as a login item (it runs from a place where that is not possible, such as a
    /// build folder or a disk image).
    case unavailable
}

/// What the settings offer for a state.
enum LoginItemAction: Equatable, Sendable {
    /// A switch that is off and can be turned on.
    case offerEnable

    /// A switch that is on and can be turned off.
    case offerDisable

    /// The switch is on, but the system still waits for the person: a button opens its Login Items page.
    case openSystemSettings

    /// Nothing can be done here; the settings say why.
    case unavailable
}

enum LoginItemPlan {
    static func decide(_ state: LoginItemState) -> LoginItemAction {
        switch state {
        case .notRegistered: return .offerEnable
        case .enabled: return .offerDisable
        case .requiresApproval: return .openSystemSettings
        case .unavailable: return .unavailable
        }
    }

    /// Whether the switch shows as on: a registered item that still waits for approval is on (turning it off removes
    /// the registration).
    static func isOn(_ state: LoginItemState) -> Bool {
        state == .enabled || state == .requiresApproval
    }
}

/// The system's login item service, behind a seam so that the model is tested without touching the real login items.
protocol LoginItemService {
    var state: LoginItemState { get }
    func enable() throws
    func disable() throws
    func openSystemSettings()
}

/// The service on a system that has none (iOS): always unavailable.
struct NoLoginItemService: LoginItemService {
    var state: LoginItemState { .unavailable }
    func enable() throws {}
    func disable() throws {}
    func openSystemSettings() {}
}

/// Reads and changes the login item for the settings screen.
@MainActor
final class LoginItemModel: ObservableObject {
    @Published private(set) var state: LoginItemState
    @Published private(set) var errorText: String?

    private let service: any LoginItemService

    init(service: any LoginItemService) {
        self.service = service
        state = service.state
    }

    /// Reads the state again: the person may have changed it in System Settings.
    func refresh() {
        state = service.state
    }

    /// Registers or removes the login item. A failure is kept as text for the screen; the state is always read back
    /// from the system, never assumed.
    func setEnabled(_ on: Bool) {
        errorText = nil
        let current = service.state
        do {
            if on, !LoginItemPlan.isOn(current) {
                try service.enable()
            } else if !on, LoginItemPlan.isOn(current) {
                try service.disable()
            }
        } catch {
            errorText = String(describing: error)
        }
        state = service.state
    }

    func openSystemSettings() {
        service.openSystemSettings()
    }
}
