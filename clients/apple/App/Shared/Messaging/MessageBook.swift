import CrisisCore
import Foundation

/// One line of the list of chats.
struct ChatRow: Equatable, Identifiable, Sendable {
    let peerHex: String

    /// The text of the newest message, `nil` when it cannot be shown.
    let preview: String?
    let lastTimeMs: UInt64
    let lastOutgoing: Bool
    let unread: Int

    var id: String { peerHex }
}

/// One message as a screen shows it.
struct MessageRow: Equatable, Identifiable, Sendable {
    /// Position in the node's log: unique, and the order of the messages.
    let seq: UInt64
    let outgoing: Bool

    /// The text, `nil` when the message cannot be read (it failed to decrypt, or was written before the node kept a copy).
    let text: String?
    let timeMs: UInt64
    let status: DeliveryStatus?

    /// The sender's node id for a message from someone else.
    let senderHex: String?
    let severity: AnnouncementSeverity

    var id: UInt64 { seq }
}

/// Unread incoming messages: in the chats together, and in the announcements.
struct Unread: Equatable, Sendable {
    var chats = 0
    var announcements = 0
}

/// How sending a message ended.
enum SendResult: Equatable, Sendable {
    case sent
    case empty
    case tooLong

    /// The node is not open: messages can only be sent while the device is part of the mesh.
    case meshOff
    case unknownContact
    case rejected
    case failed(detail: String)
}

/// The rules between the node's history and the screens, without a node and without UI: how a stored message becomes a
/// row, how a conversation is windowed and paged, how long a preview is. The same rules as the Android `Messaging`.
enum MessageBook {
    /// The longest preview of a chat in the list, in characters.
    static let previewChars = 120

    /// The text of a message of a conversation, cleaned of control characters and direction tricks. A preview is one line
    /// and is cut with an ellipsis.
    static func preview(of body: Data, maxChars: Int = previewChars) -> String {
        let text = sanitizeText(text: String(decoding: body, as: UTF8.self))
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespaces)
        guard text.count > maxChars else { return text }
        return String(text.prefix(maxChars)).trimmingCharacters(in: .whitespaces) + "…"
    }

    /// A stored message as a row. A private message is plain text; an announcement carries its importance in front of
    /// the text (the core reads it).
    static func row(from item: HistoryItem) -> MessageRow {
        let outgoing = item.direction == .outgoing
        let direct = item.kind == .direct
        var text: String?
        var severity = AnnouncementSeverity.normal
        if let body = item.body {
            if direct {
                text = sanitizeText(text: String(decoding: body, as: UTF8.self))
            } else {
                let decoded = decodeAnnouncement(payload: body)
                text = decoded.text
                severity = decoded.severity
            }
        }
        return MessageRow(
            seq: item.seq,
            outgoing: outgoing,
            text: text,
            timeMs: item.receivedAtMs,
            status: messageDeliveryStatus(outgoing: outgoing, direct: direct, delivered: item.deliveredAtMs != nil),
            senderHex: outgoing ? nil : item.counterpart.map(Hex.encode),
            severity: severity
        )
    }

    /// A conversation of the core as a line of the chat list; `nil` for the announcements (they have a screen of their
    /// own).
    static func chatRow(from info: ConversationInfo) -> ChatRow? {
        guard case let .direct(peer) = info.conversation else { return nil }
        let newest = info.last
        return ChatRow(
            peerHex: Hex.encode(peer),
            preview: newest?.body.map { preview(of: $0) },
            lastTimeMs: newest?.receivedAtMs ?? 0,
            lastOutgoing: newest?.direction == .outgoing,
            unread: saturatedInt(info.unread)
        )
    }

    static func saturatedInt(_ value: UInt64) -> Int {
        value > UInt64(Int.max) ? Int.max : Int(value)
    }

    /// Whether `ms` falls on the same calendar day as `nowMs`: such a message shows only its time.
    static func isToday(_ ms: UInt64, now nowMs: UInt64, calendar: Calendar = .current) -> Bool {
        calendar.isDate(
            Date(timeIntervalSince1970: Double(ms) / 1000),
            inSameDayAs: Date(timeIntervalSince1970: Double(nowMs) / 1000)
        )
    }
}

/// The loaded part of one conversation: a window over its newest messages that grows backwards one page at a time.
/// Entries are oldest first.
struct ConversationWindow {
    /// How many messages one page holds.
    static let pageSize = 40

    /// The most messages a window keeps while it is refreshed.
    static let maxSize = 2_000

    let ref: ConversationRef
    let peerHex: String?
    private(set) var entries: [HistoryItem] = []

    /// Where the next older page starts, `nil` when there are no older messages.
    private(set) var olderBefore: UInt64?

    /// The read marker of the conversation as far as this window knows.
    private(set) var readUpTo: UInt64 = 0

    /// What was unread when the window was first loaded: the "new messages" line stays above it until the screen closes.
    private(set) var firstUnreadSeq: UInt64?
    private(set) var loaded = false

    init(ref: ConversationRef, peerHex: String? = nil) {
        self.ref = ref
        self.peerHex = peerHex
    }

    /// How many of the newest messages a refresh reads: as many as are loaded, plus room for new ones.
    var refreshSize: Int {
        min(max(entries.count + 8, Self.pageSize), Self.maxSize)
    }

    /// Replaces the entries by a freshly read page over the newest messages.
    mutating func replace(with page: ConversationPageInfo) {
        entries = page.entries
        olderBefore = page.olderBeforeSeq
        readUpTo = page.readUpToSeq
        if !loaded {
            loaded = true
            firstUnreadSeq = page.entries.first { $0.direction == .incoming && $0.seq > page.readUpToSeq }?.seq
        }
    }

    /// Whether there are incoming messages after the read marker.
    var hasUnread: Bool {
        entries.contains { $0.direction == .incoming && $0.seq > readUpTo }
    }

    mutating func didMarkRead(upTo seq: UInt64) {
        readUpTo = max(readUpTo, seq)
    }

    /// Adds an older page in front.
    mutating func prepend(_ page: ConversationPageInfo) {
        entries = page.entries + entries
        olderBefore = page.olderBeforeSeq
    }
}

/// The open conversation with one person. Messages are oldest first.
struct ChatState: Equatable {
    var peerHex: String?
    var messages: [MessageRow] = []
    var hasOlder = false
    var loaded = false

    /// The first message that was unread when the chat was opened.
    var firstUnreadSeq: UInt64?
}

/// The announcements feed. Messages are newest first.
struct FeedState: Equatable {
    var messages: [MessageRow] = []
    var hasOlder = false
    var loaded = false
    var firstUnreadSeq: UInt64?
}
