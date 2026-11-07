import Foundation

/// What a transport tells the runtime. Handles are chosen by the transport.
public enum TransportEvent: Sendable, Equatable {
    /// A neighbour is connected, or its link MTU changed. `linkMtu` is the largest packet the link carries.
    case peerSeen(peer: UInt64, linkMtu: Int)

    /// A neighbour is gone. Reporting it twice, or for a link the core closed itself, is harmless.
    case peerLost(peer: UInt64)

    /// One packet arrived from a neighbour.
    case packetReceived(peer: UInt64, bytes: Data)
}

/// The radio, as the runtime sees it. The Bluetooth transport (phases I2 and I3) and the in-memory
/// ``LoopbackNetwork`` implement it. The contract is the one of the Android `Transport`:
///
/// * **Handles** identify one connection and are never reused during the life of the transport: a reconnect gets a
///   new handle. The core keys all per-neighbour state by handle.
/// * ``send(peer:bytes:)`` and ``disconnect(peer:)`` **must not block**: they enqueue and return. A packet that no
///   longer fits the link, or a link that is gone, is dropped silently; the mesh repairs losses by itself.
/// * Every packet handed to ``send(peer:bytes:)`` already fits the MTU last reported with ``TransportEvent/peerSeen``.
/// * ``events`` is consumed by exactly one consumer, the runtime.
public protocol Transport: AnyObject, Sendable {
    var events: AsyncStream<TransportEvent> { get }

    /// Starts scanning, advertising and so on. Called once by the runtime before it processes events.
    func start() throws

    /// Stops the radio and closes all links.
    func stop()

    func send(peer: UInt64, bytes: Data)

    /// Closes the connection. The transport reports ``TransportEvent/peerLost`` afterwards.
    func disconnect(peer: UInt64)
}
