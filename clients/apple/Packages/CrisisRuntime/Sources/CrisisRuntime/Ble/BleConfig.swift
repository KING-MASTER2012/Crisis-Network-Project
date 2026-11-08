import Foundation

/// Which sides of the link this device runs.
public enum BleRole: String, Sendable, CaseIterable, Identifiable {
    /// Scan and connect only. Does not advertise, so nobody can connect to it.
    case central

    /// Advertise and accept connections only.
    case peripheral

    /// Both, which is what the product does. Two devices that run `both` open one link each way until the
    /// HELLO exchange closes one of them (see ``HelloValue``). For topology experiments (a chain A - B - C) run the
    /// end devices as `central` or `peripheral` and the middle one as `both`.
    case both

    public var id: String { rawValue }
    public var runsCentral: Bool { self != .peripheral }
    public var runsPeripheral: Bool { self != .central }
}

/// Tunables of ``BleTransport``. The defaults are the conservative ones of the Android client; phase I3 adds power
/// profiles, tie-break and state restoration on top.
public struct BleConfig: Sendable {
    public var role: BleRole = .both

    /// The smallest packet size the core accepts (`coreInfo().minLinkMtu`, 64 in protocol v1). A link that cannot
    /// carry it is closed instead of being handed to the core. Pass the value reported by the core.
    public var minLinkMtu = 64

    /// Links (connected or still connecting, both directions together) the transport keeps. When it is 3 or more,
    /// one slot stays free for incoming connections.
    public var maxLinks = 5

    /// Outgoing connections that may be in progress at once.
    public var maxConcurrentConnects = 1

    /// From the start of a connection until it is usable (services, notifications, HELLO).
    public var handshakeTimeoutMs: Int64 = 15_000

    /// A single packet that is not completed (or not accepted by the platform) within this time closes the link.
    public var sendTimeoutMs: Int64 = 5_000

    /// This many sends in a row that the platform reported as failed close the link.
    public var maxConsecutiveSendFailures = 3

    /// Bytes waiting for one link. A packet that does not fit is dropped (the mesh repairs losses).
    public var maxQueuedBytes = 32 * 1024

    /// After a link ended, or a connection attempt failed once, the same address is left alone for this long. Every
    /// further failure in a row doubles it, up to ``maxBackoffMs``.
    public var reconnectCooldownMs: Int64 = 5_000
    public var maxBackoffMs: Int64 = 60_000

    /// A device that was not seen for this long is no longer a candidate.
    public var candidateTtlMs: Int64 = 20_000

    /// A link that lived at least this long counts as healthy: its address starts from a clean slate when it ends.
    public var stableLinkMs: Int64 = 20_000

    /// After a link to a neighbour that was already connected the other way round was closed, its address is left
    /// alone for this long.
    public var duplicateCooldownMs: Int64 = 5 * 60_000

    /// Period of the housekeeping tick (timeouts, candidates, MTU polling).
    public var tickMs: Int64 = 250

    public init() {}
}
