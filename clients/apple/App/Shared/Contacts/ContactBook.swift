import CrisisCore
import Foundation

/// A person this device knows: a contact card was added, the trust was changed, or the user gave a nickname. (The same
/// idea as the Android `ContactRow`.)
struct ContactRow: Equatable, Identifiable, Sendable {
    /// The node id in lowercase hexadecimal.
    let hex: String
    let trust: TrustLevel

    /// `true` when the card is stored: only then can a message be sent to this node.
    let hasCard: Bool
    let safetyNumber: String?
    let lastSeenMs: UInt64

    var id: String { hex }

    init(hex: String, trust: TrustLevel, hasCard: Bool, safetyNumber: String?, lastSeenMs: UInt64) {
        self.hex = hex
        self.trust = trust
        self.hasCard = hasCard
        self.safetyNumber = safetyNumber
        self.lastSeenMs = lastSeenMs
    }

    init(_ info: ContactInfo) {
        self.init(
            hex: Hex.encode(info.nodeId),
            trust: info.trust,
            hasCard: info.card != nil,
            safetyNumber: info.safetyNumber,
            lastSeenMs: info.lastSeenMs
        )
    }
}

enum ContactBook {
    /// Nodes that were merely seen in passing are not contacts: a card, a changed trust or a nickname makes them one.
    static func isKnownPerson(_ info: ContactInfo, names: [String: String]) -> Bool {
        info.card != nil || info.trust != .unknown || names[Hex.encode(info.nodeId)] != nil
    }

    /// The rows of the contact list: the known people, the one seen last first (the core already sorts by that; the
    /// order is kept, ties broken by the node id so that the list never jumps around).
    static func rows(from infos: [ContactInfo], names: [String: String]) -> [ContactRow] {
        infos
            .filter { isKnownPerson($0, names: names) }
            .map(ContactRow.init)
            .sorted { left, right in
                left.lastSeenMs != right.lastSeenMs ? left.lastSeenMs > right.lastSeenMs : left.hex < right.hex
            }
    }

    /// The name shown for a node: its nickname, or its short address in `unknownFormat` (`%@` stands for the address).
    static func displayName(hex: String, names: [String: String], unknownFormat: String) -> String {
        if let nickname = names[hex] { return nickname }
        return String(format: unknownFormat, shortNodeId(nodeIdHex: hex))
    }
}

/// The text form of a contact card, used in QR codes and when a card is shared as text: the prefix `crisis:` and the 128
/// bytes of the card in URL-safe base64 without padding (171 characters). The rules live in the Rust core (`appcodec`);
/// decoding is forgiving about white space, the case of the prefix, a missing prefix and standard base64.
enum CardText {
    static func encode(_ card: Data) -> String { encodeCardText(card: card) }

    /// The card bytes, `nil` when `text` is not a card (wrong characters or wrong length).
    static func decode(_ text: String) -> Data? { decodeCardText(text: text) }
}

enum Hex {
    static func encode(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// The bytes of a hexadecimal text (any case), `nil` for an odd length or a character that is not a digit.
    static func decode(_ text: String) -> Data? {
        let chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = Data(capacity: chars.count / 2)
        var index = 0
        while index < chars.count {
            guard let high = nibble(chars[index]), let low = nibble(chars[index + 1]) else { return nil }
            bytes.append(high << 4 | low)
            index += 2
        }
        return bytes
    }

    private static func nibble(_ char: UInt8) -> UInt8? {
        switch char {
        case 0x30...0x39: return char - 0x30
        case 0x61...0x66: return char - 0x61 + 10
        case 0x41...0x46: return char - 0x41 + 10
        default: return nil
        }
    }
}

/// Where small texts are kept. A dictionary in tests, `UserDefaults` in the app.
protocol KeyValueStore: AnyObject {
    func all() -> [String: String]
    func put(_ key: String, _ value: String)
    func remove(_ key: String)
}

/// ``KeyValueStore`` on `UserDefaults`, under one key of its own so that nothing else in the defaults is touched.
/// Private to the app and not part of any backup the app asks for (the defaults file is small and holds no secret: a
/// nickname is what the user calls a person).
final class DefaultsKeyValueStore: KeyValueStore {
    private let defaults: UserDefaults
    private let key: String

    init(defaults: UserDefaults = .standard, key: String) {
        self.defaults = defaults
        self.key = key
    }

    func all() -> [String: String] {
        defaults.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    func put(_ key: String, _ value: String) {
        var current = all()
        current[key] = value
        defaults.set(current, forKey: self.key)
    }

    func remove(_ key: String) {
        var current = all()
        current[key] = nil
        defaults.set(current, forKey: self.key)
    }
}

/// An in-memory ``KeyValueStore``, for tests and previews.
final class MemoryKeyValueStore: KeyValueStore {
    private var values: [String: String]

    init(_ values: [String: String] = [:]) {
        self.values = values
    }

    func all() -> [String: String] { values }
    func put(_ key: String, _ value: String) { values[key] = value }
    func remove(_ key: String) { values[key] = nil }
}

/// Nicknames: what the user calls a contact, kept on this device only (the core knows keys, not names). Keyed by the
/// node id in hexadecimal. The rules for what a nickname may look like are the core's (`normalizeNickname`).
final class NicknameStore {
    private let store: KeyValueStore

    init(_ store: KeyValueStore) {
        self.store = store
    }

    func loadAll() -> [String: String] { store.all() }

    /// Forgets every nickname.
    func clear() {
        for key in store.all().keys { store.remove(key) }
    }

    /// Saves `raw` for `nodeIdHex` (an empty text removes the nickname) and returns what was saved, if anything.
    @discardableResult
    func set(_ nodeIdHex: String, _ raw: String) -> String? {
        let nickname = Self.normalize(raw)
        if let nickname { store.put(nodeIdHex, nickname) } else { store.remove(nodeIdHex) }
        return nickname
    }

    /// The longest nickname, in characters.
    static var maxLength: Int { Int(conventionInfo().maxNicknameChars) }

    /// One line, no control characters, trimmed, at most ``maxLength`` characters; `nil` when nothing is left.
    static func normalize(_ raw: String) -> String? { normalizeNickname(raw: raw) }
}

/// Contact cards that were scanned or pasted while the node was not running, waiting for it (the database cannot be
/// opened a second time). A card is public, so the list is kept in plain defaults like the Android client does.
final class PendingCards {
    private let store: KeyValueStore

    init(_ store: KeyValueStore) {
        self.store = store
    }

    /// The card texts waiting (in no particular order: each is added on its own).
    func all() -> [String] {
        store.all().sorted { $0.key < $1.key }.map(\.value)
    }

    /// Remembers a card text once; the same card twice stays one entry.
    func add(_ cardText: String) {
        store.put(cardText, cardText)
    }

    func remove(_ cardText: String) {
        store.remove(cardText)
    }

    func clear() {
        for key in store.all().keys { store.remove(key) }
    }
}
