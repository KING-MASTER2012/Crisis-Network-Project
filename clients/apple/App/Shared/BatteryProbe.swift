import Foundation
#if os(iOS)
import UIKit
#endif

/// A battery measurement for the test bench: the level when it started, the level now and the drop per hour. It is
/// how the power profiles are compared (phase I3 exit test). A measurement that was charging at any moment is not
/// valid, and a drop needs time to show: iOS reports whole percents.
struct BatteryReport: Equatable {
    let startPercent: Int
    let nowPercent: Int
    let elapsedSeconds: Double
    let charged: Bool
    let lowPowerMode: Bool

    /// Percent per hour, once at least ten minutes have passed and the device never charged.
    var percentPerHour: Double? {
        guard !charged, elapsedSeconds >= 600 else { return nil }
        return Double(startPercent - nowPercent) / (elapsedSeconds / 3600)
    }
}

@MainActor
final class BatteryProbe {
    private var startPercent: Int?
    private var startedAt = Date()
    private var charged = false

    /// `false` where there is no battery to read (a Mac without one, the simulator).
    var isAvailable: Bool {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        return UIDevice.current.batteryLevel >= 0
        #else
        return false
        #endif
    }

    func begin() {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        startPercent = level >= 0 ? Int((level * 100).rounded()) : nil
        charged = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        #else
        startPercent = nil
        #endif
        startedAt = Date()
    }

    func end() {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = false
        #endif
        startPercent = nil
    }

    func report() -> BatteryReport? {
        #if os(iOS)
        guard let startPercent else { return nil }
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return nil }
        let state = UIDevice.current.batteryState
        if state == .charging || state == .full { charged = true }
        return BatteryReport(
            startPercent: startPercent,
            nowPercent: Int((level * 100).rounded()),
            elapsedSeconds: Date().timeIntervalSince(startedAt),
            charged: charged,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled
        )
        #else
        return nil
        #endif
    }
}
