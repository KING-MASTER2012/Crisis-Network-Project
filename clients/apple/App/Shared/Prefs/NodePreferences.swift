import CrisisCore
import Foundation

/// The sizes of the message store the user can choose (phase I7). The core takes the size when the node is opened, so a new
/// choice is remembered at once and applies the next time the mesh starts.
///
/// The share of the store that other nodes may pin by addressing messages to this device stays a quarter of it (8 of
/// 32 MB in the core's defaults), whatever size is chosen. The same choices as the Android client.
enum StoreLimits {
    /// The choices, in megabytes. The smallest is far above the core's minimum (one maximum-size envelope).
    static let presetsMb = [8, 16, 32, 64, 128, 256]

    /// Equals the core's default store size.
    static let defaultMb = 32

    private static let bytesPerMb: UInt64 = 1_024 * 1_024
    private static let inboundPinnedDivisor: UInt64 = 4

    static func bytes(of mb: Int) -> UInt64 { UInt64(max(mb, 0)) * bytesPerMb }

    /// Bytes of the store that other nodes may fill with messages addressed to this device.
    static func inboundPinnedBytes(of mb: Int) -> UInt64 { bytes(of: mb) / inboundPinnedDivisor }

    /// A stored value that is not one of the choices (an old or damaged setting) becomes the default.
    static func coerce(_ mb: Int) -> Int { presetsMb.contains(mb) ? mb : defaultMb }
}

/// The lifetime a new announcement gets unless its author picks another one. The choices are the presets of the composer,
/// so the preselected row always exists there.
enum DefaultLifetimes {
    static func options(maxSecs: Int64 = Limits.fromCore().maxLifetimeSecs) -> [Int64] {
        lifetimePresets(maxSecs: maxSecs)
    }

    /// A stored value that is not a choice (or exceeds the protocol's maximum) becomes the core's default (one day).
    static func coerce(_ secs: Int64, maxSecs: Int64 = Limits.fromCore().maxLifetimeSecs) -> Int64 {
        options(maxSecs: maxSecs).contains(secs) ? secs : conventionInfo().defaultLifetimeSecs
    }
}

/// What the user chose for the node: the store size and the default lifetime of announcements. Both are read by the core
/// when the node opens.
struct NodeChoices: Equatable, Sendable {
    var storeLimitMb: Int
    var defaultLifetimeSecs: Int64

    private enum Keys {
        static let storeMb = "node.storeLimitMb"
        static let lifetime = "node.defaultLifetimeSecs"
    }

    /// The choices as they are stored, each coerced to something valid.
    static func load(from defaults: UserDefaults = .standard) -> NodeChoices {
        let stored = defaults.object(forKey: Keys.storeMb) as? Int ?? StoreLimits.defaultMb
        let lifetime = (defaults.object(forKey: Keys.lifetime) as? NSNumber)?.int64Value ?? conventionInfo().defaultLifetimeSecs
        return NodeChoices(storeLimitMb: StoreLimits.coerce(stored), defaultLifetimeSecs: DefaultLifetimes.coerce(lifetime))
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(storeLimitMb, forKey: Keys.storeMb)
        defaults.set(NSNumber(value: defaultLifetimeSecs), forKey: Keys.lifetime)
    }

    /// The settings the core opens with: `base` with the store size (and the pinned share of it) and the lifetime
    /// replaced.
    func nodeSettings(base: NodeSettings = defaultSettings()) -> NodeSettings {
        var settings = base
        settings.maxStoreBytes = StoreLimits.bytes(of: storeLimitMb)
        settings.maxInboundPinnedBytes = StoreLimits.inboundPinnedBytes(of: storeLimitMb)
        settings.defaultLifetimeSecs = UInt32(clamping: defaultLifetimeSecs)
        return settings
    }
}

/// ``NodeChoices`` for the screens: changing a value saves it at once.
@MainActor
final class NodePreferences: ObservableObject {
    static let shared = NodePreferences()

    private let defaults: UserDefaults

    @Published var storeLimitMb: Int {
        didSet { choices.save(to: defaults) }
    }

    @Published var defaultLifetimeSecs: Int64 {
        didSet { choices.save(to: defaults) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let loaded = NodeChoices.load(from: defaults)
        storeLimitMb = loaded.storeLimitMb
        defaultLifetimeSecs = loaded.defaultLifetimeSecs
    }

    var choices: NodeChoices {
        NodeChoices(storeLimitMb: storeLimitMb, defaultLifetimeSecs: defaultLifetimeSecs)
    }
}
