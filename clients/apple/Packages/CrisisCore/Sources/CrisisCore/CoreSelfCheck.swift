import Foundation

/// One line of the self-test: what was checked and how it went.
public struct SelfCheckStep: Sendable, Identifiable, Equatable {
    public let id: Int
    public let title: String
    public let passed: Bool
    public let detail: String

    public init(id: Int, title: String, passed: Bool, detail: String) {
        self.id = id
        self.title = title
        self.passed = passed
        self.detail = detail
    }
}

/// The outcome of ``CoreSelfCheck/run()``.
public struct SelfCheckReport: Sendable, Equatable {
    public let steps: [SelfCheckStep]

    public init(steps: [SelfCheckStep]) {
        self.steps = steps
    }

    /// Whether every step passed.
    public var passed: Bool { steps.allSatisfy(\.passed) }
}

private struct SelfCheckFailure: Error, CustomStringConvertible {
    let description: String
}

private func expect(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw SelfCheckFailure(description: message()) }
}

/// A self-test of the Rust core on the device it runs on: the library loads, identities and conventions work,
/// two nodes talk to each other through an in-memory link, and the encrypted database works.
///
/// The same code runs in the unit tests, on the simulator, on a phone and on a Mac, so that "it works on my
/// device" means the same thing everywhere. It needs no Bluetooth.
public enum CoreSelfCheck {
    public static func run() -> SelfCheckReport {
        var steps: [SelfCheckStep] = []

        func record(_ title: String, _ body: () throws -> String) {
            do {
                let detail = try body()
                steps.append(SelfCheckStep(id: steps.count, title: title, passed: true, detail: detail))
            } catch {
                steps.append(SelfCheckStep(id: steps.count, title: title, passed: false, detail: "\(error)"))
            }
        }

        record("The core loads") {
            let info = try CoreRuntime.verify()
            return "core \(info.crateVersion), interface \(info.apiVersion), protocol \(info.protocolVersion); "
                + CoreBuildInfo.summary
        }
        record("Identity") { try checkIdentity() }
        record("Conventions") { try checkConventions() }
        record("Two nodes talk") { try checkTwoNodes() }
        record("Encrypted database") { try checkDatabase() }

        return SelfCheckReport(steps: steps)
    }

    // MARK: - Steps

    private static let start: UInt64 = 1_000_000

    private static func node(seedByte: UInt8) throws -> CrisisNode {
        try CrisisNode.inMemory(seed: Data(repeating: seedByte, count: 32), settings: defaultSettings(), nowMs: start)
    }

    private static func checkIdentity() throws -> String {
        let seed = try generateSeed()
        try expect(seed.count == 32, "a seed has \(seed.count) bytes, not 32")
        let first = try CrisisNode.inMemory(seed: seed, settings: defaultSettings(), nowMs: start)
        let again = try CrisisNode.inMemory(seed: seed, settings: defaultSettings(), nowMs: start)
        try expect(first.nodeId() == again.nodeId(), "the same seed gave two identities")
        let other = try node(seedByte: 9)
        try expect(first.nodeId() != other.nodeId(), "two seeds gave the same identity")
        let card = first.contactCard()
        try expect(card.count == Int(conventionInfo().cardLen), "a contact card has \(card.count) bytes")
        let details = try inspectContactCard(card: card)
        try expect(details.nodeId == first.nodeId(), "the card names another node")
        return "node \(first.nodeIdHex().prefix(8))…, card of \(card.count) bytes"
    }

    private static func checkConventions() throws -> String {
        try expect(sanitizeText(text: "a\u{7}b\u{202E}c") == "abc", "sanitizing left controls in the text")

        let text = "Water at the school"
        let payload = encodeAnnouncement(text: text, severity: .important)
        try expect(payload.count == text.utf8.count + Int(announcementOverhead(severity: .important)),
                   "an important announcement has the wrong size")
        let decoded = decodeAnnouncement(payload: payload)
        try expect(decoded.text == text && decoded.severity == .important, "an announcement did not round-trip")
        try expect(decodeAnnouncement(payload: Data("plain".utf8)).severity == .normal,
                   "plain text is not a normal announcement")

        let card = try node(seedByte: 3).contactCard()
        let cardText = encodeCardText(card: card)
        try expect(cardText.hasPrefix(conventionInfo().cardPrefix), "a card text has no prefix")
        try expect(decodeCardText(text: cardText) == card, "a card did not round-trip through its text form")
        try expect(decodeCardText(text: "crisis:!!!") == nil, "garbage was accepted as a card")

        let parts = splitLifetime(secs: 900)
        try expect(parts.value == 15 && parts.unit == .minutes, "900 seconds are not 15 minutes")
        try expect(normalizeNickname(raw: "  Ali \n") == "Ali", "a nickname was not normalised")
        try expect(directByteLimit(maxPayloadBytes: 1024) == 1024 - conventionInfo().directSealOverhead,
                   "the direct message limit is wrong")
        return "announcements, cards, lifetimes and nicknames agree with the core"
    }

    private static func checkTwoNodes() throws -> String {
        let pair = try LoopbackPair.make()
        pair.connect()
        pair.settle()

        // A message to everybody.
        try pair.publishBroadcast(from: .a, payload: Data("hello mesh".utf8))
        pair.settle()
        try expect(pair.received(by: .b).contains { $0.kind == .broadcast && $0.body == Data("hello mesh".utf8) },
                   "the broadcast did not reach the other node")

        // Contacts, then a private message and its acknowledgement.
        _ = try pair.a.addContact(nowMs: pair.now, card: pair.b.contactCard())
        _ = try pair.b.addContact(nowMs: pair.now, card: pair.a.contactCard())
        let id = try pair.publishDirect(from: .a, to: pair.b.contactCard(), plaintext: Data("hello you".utf8))
        pair.settle()
        try expect(pair.received(by: .b).contains { $0.kind == .direct && $0.body == Data("hello you".utf8) },
                   "the private message did not reach the other node")
        try expect(pair.delivered(to: .a).contains(id), "the acknowledgement did not come back")
        return "broadcast, private message and acknowledgement over an in-memory link"
    }

    private static func checkDatabase() throws -> String {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("crisis-selfcheck-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("node.db").path
        let seed = Data(repeating: 5, count: 32)
        let key = try generateDatabaseKey()
        let friend = try node(seedByte: 6).contactCard()

        func open(_ key: Data?, at time: UInt64) throws -> CrisisNode {
            try CrisisNode.open(seed: seed, databasePath: path, databaseKey: key, settings: defaultSettings(), nowMs: time)
        }

        guard CoreBuildInfo.sqlcipher else {
            do {
                _ = try open(key, at: start)
            } catch {
                // The error is an enum of the bindings; its name is stable, its Swift spelling depends on the
                // generator version, so the description is compared.
                if String(describing: error).lowercased().contains("encryptionunavailable") {
                    return "this build has no SQLCipher; a key is refused, as it should be"
                }
                throw error
            }
            throw SelfCheckFailure(description: "a key was accepted although this build has no SQLCipher")
        }

        do {
            let node = try open(key, at: start)
            _ = try node.addContact(nowMs: start, card: friend)
        }
        let reopened = try open(key, at: start + 1)
        try expect(try reopened.contacts(limit: 10).count == 1, "the contact was not stored")

        let header = try Data(contentsOf: URL(fileURLWithPath: path)).prefix(15)
        try expect(header != Data("SQLite format 3".utf8), "the database file is not encrypted")

        var wrongKeyOpened = false
        do {
            _ = try open(try generateDatabaseKey(), at: start + 2)
            wrongKeyOpened = true
        } catch {
            // Refused, as it should be.
        }
        try expect(!wrongKeyOpened, "a wrong key opened the database")
        return "stored, reopened with the key, refused with another; the file is not plain SQLite"
    }
}

// MARK: - Two nodes joined by memory

/// Two nodes and the packets between them, driven by hand like a transport would (the same idea as the pump of
/// the Rust end-to-end tests). Time only moves when `settle()` is called.
final class LoopbackPair {
    enum Side { case a, b

        var other: Side { self == .a ? .b : .a }
    }

    struct Received {
        let kind: MessageKind
        let body: Data
    }

    let a: CrisisNode
    let b: CrisisNode
    private(set) var now: UInt64 = 1_000_000
    private var inboxA: [CoreNotification] = []
    private var inboxB: [CoreNotification] = []
    private var packets: [(to: Side, bytes: Data)] = []

    private init(a: CrisisNode, b: CrisisNode) {
        self.a = a
        self.b = b
    }

    static func make() throws -> LoopbackPair {
        let first = try CrisisNode.inMemory(seed: Data(repeating: 1, count: 32), settings: defaultSettings(), nowMs: 1_000_000)
        let second = try CrisisNode.inMemory(seed: Data(repeating: 2, count: 32), settings: defaultSettings(), nowMs: 1_000_000)
        return LoopbackPair(a: first, b: second)
    }

    private func node(_ side: Side) -> CrisisNode { side == .a ? a : b }

    /// Both nodes see each other on link handle 1, with a link big enough for the protocol.
    func connect() {
        let mtu = max(coreInfo().minLinkMtu, 185)
        for side in [Side.a, Side.b] {
            if let output = try? node(side).peerSeen(nowMs: now, peer: 1, linkMtu: mtu) { absorb(output, from: side) }
        }
    }

    func publishBroadcast(from side: Side, payload: Data) throws {
        absorb(try node(side).publishBroadcast(nowMs: now, payload: payload).output, from: side)
    }

    /// Returns the id of the message.
    func publishDirect(from side: Side, to card: Data, plaintext: Data) throws -> Data {
        let outcome = try node(side).publishDirectToCard(nowMs: now, card: card, plaintext: plaintext)
        absorb(outcome.output, from: side)
        return outcome.messageId
    }

    /// Lets time pass and moves packets until nothing more happens.
    func settle() {
        for _ in 0..<300 {
            now += 50
            let batch = packets
            packets.removeAll()
            for packet in batch {
                if let output = try? node(packet.to).packetReceived(nowMs: now, peer: 1, bytes: packet.bytes) {
                    absorb(output, from: packet.to)
                }
            }
            for side in [Side.a, Side.b] {
                if let output = try? node(side).tick(nowMs: now) { absorb(output, from: side) }
            }
        }
    }

    func received(by side: Side) -> [Received] {
        notifications(of: side).compactMap { notification in
            if case let .messageReceived(_, _, kind, body) = notification { return Received(kind: kind, body: body) }
            return nil
        }
    }

    func delivered(to side: Side) -> [Data] {
        notifications(of: side).compactMap { notification in
            if case let .delivered(id) = notification { return id }
            return nil
        }
    }

    private func notifications(of side: Side) -> [CoreNotification] {
        side == .a ? inboxA : inboxB
    }

    /// What a node asked for: packets go to the other node, notifications are kept for the checks.
    private func absorb(_ output: CoreOutput, from side: Side) {
        for command in output.transport {
            if case let .sendPacket(_, bytes) = command {
                packets.append((to: side.other, bytes: bytes))
            }
        }
        if side == .a { inboxA += output.notifications } else { inboxB += output.notifications }
    }
}
