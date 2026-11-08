import Foundation

/// Things that went wrong or are worth knowing, for the log and the test screen.
public enum BleDiagnostic: Sendable, Equatable {
    case centralState(BleRadioState)
    case peripheralState(BleRadioState)
    case scanStarted
    case scanFailed(code: Int)
    case advertiseStarted
    case advertiseFailed(code: Int)
    case serverReady
    case serverFailed(code: Int)

    /// An outgoing connection did not come up.
    case connectFailed(address: String, reason: String)

    /// A link was refused because the limit was reached.
    case linkRefused(address: String, role: GattRole)

    /// A link never became usable within the handshake time.
    case handshakeTimeout(address: String, role: GattRole, payloadMtu: Int)

    /// A link was closed because it cannot carry the smallest packet the core accepts.
    case mtuTooSmall(address: String, payloadMtu: Int)

    /// A usable link (reported to the core under `handle`).
    case linkUp(address: String, role: GattRole, handle: UInt64, payloadMtu: Int)
    case linkDown(address: String, role: GattRole, handle: UInt64?, reason: String)

    /// A link was closed because the same neighbour was already connected through another one.
    case duplicateLink(address: String, role: GattRole)

    /// An address that failed `failures` times in a row is left alone for `delayMs`.
    case connectBackoff(address: String, delayMs: Int64, failures: Int)

    case packetDropped(handle: UInt64, reason: DropReason)

    /// The oldest outgoing link was closed to make room for a neighbour that is waiting.
    case linkRotated(address: String)

    case powerChanged(profile: BlePowerProfile, foreground: Bool)

    /// The HELLO identifier was replaced (new links use the new one).
    case helloRotated

    /// The system relaunched the app for Bluetooth (`side` is "central" or "peripheral") and handed back `count`
    /// connections or services, which were reset.
    case restored(side: String, count: Int)
}

public enum DropReason: String, Sendable, Equatable {
    case queueFull
    case tooLarge
    case sendFailed
}

/// Counters of one link.
public struct BleLinkMetrics: Sendable, Equatable {
    public var packetsSent: Int64 = 0
    public var bytesSent: Int64 = 0
    public var packetsReceived: Int64 = 0
    public var bytesReceived: Int64 = 0
    public var packetsDropped: Int64 = 0

    /// Receiving window: the first and the last packet, in transport clock milliseconds.
    public var firstReceiveMs: Int64?
    public var lastReceiveMs: Int64?

    public init() {}

    /// Bytes per second between the first and the last received packet, or `nil` with fewer than two packets.
    public var rxBytesPerSecond: Double? {
        guard let first = firstReceiveMs, let last = lastReceiveMs, packetsReceived >= 2, last > first else { return nil }
        return Double(bytesReceived) * 1000.0 / Double(last - first)
    }
}

/// A snapshot of one link, for the test screen.
public struct BleLinkInfo: Sendable, Equatable, Identifiable {
    public var id: Int
    public var handle: UInt64?
    public var role: GattRole
    public var address: String
    public var active: Bool

    /// Largest packet the link carries (ATT MTU - 3), 0 while unknown.
    public var payloadMtu: Int
    public var queuedBytes: Int
    public var peerHello: UInt64?
    public var metrics: BleLinkMetrics
}

/// A snapshot of the radio and discovery state.
public struct BleStats: Sendable, Equatable {
    public var role: BleRole
    public var centralState: BleRadioState
    public var peripheralState: BleRadioState
    public var scanning: Bool
    public var advertising: Bool
    public var serverReady: Bool

    /// Devices seen recently that are not linked.
    public var candidates: Int

    /// Addresses currently in back-off.
    public var backedOff: Int

    /// Candidates the tie-break holds back (the neighbour is expected to connect to us).
    public var heldByTieBreak: Int
    public var profile: BlePowerProfile
    public var foreground: Bool

    /// The most links the profile and the configuration allow together.
    public var effectiveMaxLinks: Int
    public var linkRotations: Int64
    public var connectAttempts: Int64
    public var connectFailures: Int64
    public var duplicateLinks: Int64
}
