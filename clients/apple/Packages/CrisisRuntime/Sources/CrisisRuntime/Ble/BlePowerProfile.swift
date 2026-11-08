import Foundation

/// The numbers behind a power profile.
public struct BleTuning: Sendable, Equatable {
    /// Foreground scan duty cycle: scan for `scanOnMs`, pause for `scanOffMs`. `scanOffMs == 0` scans continuously.
    public let scanOnMs: Int64
    public let scanOffMs: Int64

    /// Report every advertisement of a device (not once per scan). Costs power; only in the foreground, because the
    /// system ignores it in the background.
    public let allowDuplicates: Bool

    /// Most links (both directions together) this profile keeps.
    public let maxLinks: Int

    /// How often a saturated node may swap its oldest outgoing link for a neighbour that is waiting.
    public let rotationIntervalMs: Int64

    var isContinuous: Bool { scanOffMs == 0 }
}

/// The Apple counterpart of the Android power profiles. iOS has no scan modes and no advertising interval to choose,
/// so the profile works where the application can: **how long the radio scans** (a duty cycle in the foreground; in
/// the background the system decides and the profile does not matter), how many links are kept, and how often links
/// are rotated.
///
/// A node that already has all the links it wants scans like ``saver`` whatever the profile is: it only needs to
/// notice a neighbour that is waiting.
public enum BlePowerProfile: String, Sendable, CaseIterable, Identifiable {
    /// Scans 2 s in every 20 s, 3 links. For a phone that has to last the day.
    case saver

    /// Scans 5 s in every 15 s, 5 links. The default.
    case balanced

    /// Scans continuously and sees every advertisement, 7 links. Finds neighbours fastest, costs the most.
    case aggressive

    public var id: String { rawValue }

    public var tuning: BleTuning {
        switch self {
        case .saver:
            return BleTuning(scanOnMs: 2_000, scanOffMs: 18_000, allowDuplicates: false, maxLinks: 3, rotationIntervalMs: 15 * 60_000)
        case .balanced:
            return BleTuning(scanOnMs: 5_000, scanOffMs: 10_000, allowDuplicates: false, maxLinks: 5, rotationIntervalMs: 10 * 60_000)
        case .aggressive:
            return BleTuning(scanOnMs: 1_000, scanOffMs: 0, allowDuplicates: true, maxLinks: 7, rotationIntervalMs: 5 * 60_000)
        }
    }
}
