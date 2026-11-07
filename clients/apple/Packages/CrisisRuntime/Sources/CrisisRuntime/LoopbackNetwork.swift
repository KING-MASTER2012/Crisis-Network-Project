import Foundation

/// An in-memory "air" for tests and previews: endpoints are connected pairwise, packets are delivered immediately
/// and in order. It enforces the link MTU like a real radio would.
public final class LoopbackNetwork: @unchecked Sendable {
    public static let defaultLinkMtu = 185

    public init() {}

    public func endpoint() -> LoopbackTransport {
        LoopbackTransport()
    }

    /// Connects two endpoints with a link of `linkMtu` bytes. Both sides get a fresh handle and a `peerSeen`.
    @discardableResult
    public func connect(_ a: LoopbackTransport, _ b: LoopbackTransport, linkMtu: Int = LoopbackNetwork.defaultLinkMtu) -> LoopbackLink {
        precondition(a !== b, "an endpoint cannot connect to itself")
        let handleA = a.newHandle()
        let handleB = b.newHandle()
        a.setLink(handleA, LoopbackTransport.End(remote: b, remoteHandle: handleB, mtu: linkMtu))
        b.setLink(handleB, LoopbackTransport.End(remote: a, remoteHandle: handleA, mtu: linkMtu))
        a.emit(.peerSeen(peer: handleA, linkMtu: linkMtu))
        b.emit(.peerSeen(peer: handleB, linkMtu: linkMtu))
        return LoopbackLink(a: a, handleA: handleA, b: b, handleB: handleB)
    }

    /// Cuts a link from outside (the radio went out of range). Both sides get a `peerLost`.
    public func disconnect(_ link: LoopbackLink) {
        link.a.dropLink(link.handleA)
        link.b.dropLink(link.handleB)
    }
}

/// One connection between two endpoints, with the handle each side knows it by.
public struct LoopbackLink: @unchecked Sendable {
    let a: LoopbackTransport
    public let handleA: UInt64
    let b: LoopbackTransport
    public let handleB: UInt64
}

/// An endpoint of a ``LoopbackNetwork``.
public final class LoopbackTransport: Transport, @unchecked Sendable {
    struct End {
        let remote: LoopbackTransport
        let remoteHandle: UInt64
        let mtu: Int
    }

    public let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private let lock = NSLock()
    private var links: [UInt64: End] = [:]
    private var lastHandle: UInt64 = 0
    private var oversized = 0

    init() {
        (events, continuation) = AsyncStream.makeStream(of: TransportEvent.self)
    }

    /// Packets that were dropped because they exceeded the link MTU. A correct core never causes any.
    public var oversizedPacketsDropped: Int {
        lock.lock()
        defer { lock.unlock() }
        return oversized
    }

    public func start() throws {}

    public func stop() {}

    public func send(peer: UInt64, bytes: Data) {
        lock.lock()
        let end = links[peer]
        if let end, bytes.count > end.mtu { oversized += 1 }
        lock.unlock()
        guard let end, bytes.count <= end.mtu else { return }
        end.remote.emit(.packetReceived(peer: end.remoteHandle, bytes: bytes))
    }

    public func disconnect(peer: UInt64) {
        dropLink(peer)
    }

    func newHandle() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        lastHandle += 1
        return lastHandle
    }

    func setLink(_ handle: UInt64, _ end: End) {
        lock.lock()
        links[handle] = end
        lock.unlock()
    }

    func emit(_ event: TransportEvent) {
        continuation.yield(event)
    }

    /// Removes our end and the remote end of a link; both sides report `peerLost` once.
    func dropLink(_ handle: UInt64) {
        lock.lock()
        let end = links.removeValue(forKey: handle)
        lock.unlock()
        guard let end else { return }
        emit(.peerLost(peer: handle))
        if end.remote.removeLink(end.remoteHandle) {
            end.remote.emit(.peerLost(peer: end.remoteHandle))
        }
    }

    private func removeLink(_ handle: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return links.removeValue(forKey: handle) != nil
    }
}
