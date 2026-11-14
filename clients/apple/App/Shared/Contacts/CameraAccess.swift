import AVFoundation
import Foundation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// What stands between the user and the camera that reads a QR code.
enum CameraReadiness: Equatable, Sendable {
    /// Allowed, and the device has a camera.
    case ready

    /// The system has not asked yet: the first use shows the permission dialog.
    case notDetermined

    /// The user refused. Only the Settings app can change it.
    case denied

    /// A restriction (Screen Time, a management profile) forbids it.
    case restricted

    /// The device has no camera (a Mac without one, an iPad in some cases, the simulator).
    case unavailable

    static func of(status: AVAuthorizationStatus, hasCamera: Bool) -> CameraReadiness {
        guard hasCamera else { return .unavailable }
        switch status {
        case .authorized: return .ready
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .restricted: return .restricted
        @unknown default: return .denied
        }
    }
}

/// Watches the camera permission for the scan screen and asks for it when the user presses the button (not before: the
/// person should know why first).
@MainActor
final class CameraAccess: ObservableObject {
    @Published private(set) var readiness: CameraReadiness

    init() {
        readiness = Self.current()
    }

    func refresh() {
        readiness = Self.current()
    }

    func request() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    func openSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        #elseif os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }

    private static func current() -> CameraReadiness {
        CameraReadiness.of(status: AVCaptureDevice.authorizationStatus(for: .video), hasCamera: hasCamera)
    }

    private static var hasCamera: Bool {
        AVCaptureDevice.default(for: .video) != nil
    }
}
