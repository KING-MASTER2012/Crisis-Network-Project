import Foundation

/// Hands out the handles the core uses to identify a neighbour. A handle is **never reused** during the life of the
/// transport: a reconnect gets a new one, so that the core can never confuse a returning device (or a different one
/// that got the same identifier) with the old link. Handle 0 is not used.
struct HandleAllocator {
    private var upcoming: UInt64

    init(first: UInt64 = 1) {
        upcoming = first
    }

    mutating func next() -> UInt64 {
        precondition(upcoming != UInt64.max, "handle space exhausted")
        let handle = upcoming
        upcoming += 1
        return handle
    }
}

/// The packets waiting for one link, in order, bounded by their total size. When a packet does not fit, ``offer(_:)``
/// refuses it and the caller drops it: the mesh repairs a lost packet by itself, while an unbounded queue behind a
/// slow link would grow without limit.
///
/// The packet being sent stays at the head until it is completed, so it counts against the limit.
struct SendQueue {
    private let maxBytes: Int
    private var packets: [Data] = []

    /// Total size of the queued packets.
    private(set) var bytes = 0

    init(maxBytes: Int) {
        self.maxBytes = maxBytes
    }

    var isEmpty: Bool { packets.isEmpty }
    var count: Int { packets.count }

    mutating func offer(_ packet: Data) -> Bool {
        guard bytes + packet.count <= maxBytes else { return false }
        packets.append(packet)
        bytes += packet.count
        return true
    }

    func peek() -> Data? { packets.first }

    @discardableResult
    mutating func removeFirst() -> Data? {
        guard !packets.isEmpty else { return nil }
        let head = packets.removeFirst()
        bytes -= head.count
        return head
    }

    mutating func clear() {
        packets.removeAll()
        bytes = 0
    }
}
