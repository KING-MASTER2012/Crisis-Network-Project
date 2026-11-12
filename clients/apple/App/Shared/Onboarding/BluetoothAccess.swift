import CoreBluetooth
import Foundation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// What stands between the user and a working Bluetooth mesh.
enum BluetoothReadiness: Equatable, Sendable {
    /// Allowed and on.
    case ready

    /// The system has not asked yet: the first use of Bluetooth shows the permission dialog.
    case notDetermined

    /// The user refused. Only the Settings app can change it.
    case denied

    /// A restriction (Screen Time, a management profile) forbids it.
    case restricted

    case poweredOff
    case unsupported

    /// Allowed, but the state is not known yet (the manager is still starting, or resetting).
    case unknown

    /// The answer for an authorisation and a manager state; `state` is `nil` when no manager exists yet.
    static func of(authorization: CBManagerAuthorization, state: CBManagerState?) -> BluetoothReadiness {
        switch authorization {
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .allowedAlways:
            switch state {
            case .poweredOn: return .ready
            case .poweredOff: return .poweredOff
            case .unsupported: return .unsupported
            case .unauthorized: return .denied
            case .resetting, .unknown, .none: return .unknown
            @unknown default: return .unknown
            }
        @unknown default:
            return .unknown
        }
    }
}

/// Watches the Bluetooth permission and power state for the screens of the introduction and the home screen, and asks
/// for the permission when the user presses the button (not at launch: the person should know why first).
///
/// A manager has to exist for the system to show the permission dialog and to report the power state; this one only
/// observes (it never scans or connects), and the node's own transport has its own.
@MainActor
final class BluetoothAccess: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published private(set) var readiness: BluetoothReadiness = .unknown

    private var manager: CBCentralManager?

    override init() {
        super.init()
        refresh()
    }

    /// Reads the authorisation (and the power state, when a manager exists or the permission is already given).
    func refresh() {
        let authorization = CBManager.authorization
        if authorization == .allowedAlways, manager == nil {
            // Allowed already: creating the manager shows nothing and tells whether Bluetooth is on.
            manager = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionShowPowerAlertKey: false])
        }
        readiness = BluetoothReadiness.of(authorization: authorization, state: manager?.state)
    }

    /// Shows the permission dialog when it was not shown yet, and the system's "turn on Bluetooth" alert when it is off.
    func request() {
        if manager == nil {
            manager = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        }
        refresh()
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in self.refresh() }
    }

    /// Opens the page of the settings where the person can allow Bluetooth for this app.
    func openSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #elseif os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }
}

/// What to tell the person when Bluetooth is not ready, with the button that helps. Shows nothing when it is ready or
/// when the system simply has not asked yet.
struct BluetoothBlockerCard: View {
    @ObservedObject var access: BluetoothAccess

    var body: some View {
        switch access.readiness {
        case .denied:
            InfoCard(title: "bt_denied_title", tone: .warning) {
                Text("bt_denied_body")
                SecondaryButton("bt_open_settings") { access.openSettings() }
            }
        case .restricted:
            InfoCard(title: "bt_restricted_title", tone: .warning) {
                Text("bt_restricted_body")
            }
        case .poweredOff:
            InfoCard(title: "bt_off_title", tone: .warning) {
                Text("bt_off_body")
                SecondaryButton("bt_turn_on") { access.request() }
            }
        case .unsupported:
            InfoCard(title: "bt_unsupported_title", tone: .error) {
                Text("bt_unsupported_body")
            }
        case .ready, .notDetermined, .unknown:
            EmptyView()
        }
    }
}
