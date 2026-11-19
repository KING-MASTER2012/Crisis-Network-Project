import CrisisCore
import Foundation
import UserNotifications

/// What the notification screens should offer, from what the system says about notifications.
enum NotificationAccessAction: Equatable {
    /// Notifications are allowed.
    case none

    /// The system has not asked yet: pressing the button shows its permission dialog.
    case askInApp

    /// The user refused. Only the Settings app can change it.
    case openSettings
}

enum NotificationAccessPlan {
    static func decide(_ status: UNAuthorizationStatus) -> NotificationAccessAction {
        switch status {
        case .authorized, .provisional: return .none
        case .notDetermined: return .askInApp
        default: return .openSettings
        }
    }
}

/// Keeps a burst of messages from making noise more than once: the first message of a kind makes a sound, the ones that
/// follow within the cooldown arrive silently (same notification, no extra buzz). The same idea as the Android
/// `AlertGate`.
struct AlertGate {
    private var lastAlertMs: [String: Int64] = [:]

    /// `true` when a message of `key` at `nowMs` should make a sound; remembers it when so.
    mutating func shouldAlert(_ key: String, nowMs: Int64, cooldownMs: Int64) -> Bool {
        if let last = lastAlertMs[key], nowMs - last < cooldownMs, nowMs >= last { return false }
        lastAlertMs[key] = nowMs
        return true
    }
}

/// The two kinds of notification.
enum NotificationKind: String, Equatable {
    /// A private message: one notification per person.
    case direct

    /// Announcements: one notification for all of them, which says how many.
    case broadcast
}

/// A notification ready to be handed to the system.
struct NotificationPlan: Equatable {
    /// A message of the same person (or another announcement) replaces the notification with the same identifier.
    let identifier: String
    let kind: NotificationKind
    let thread: String
    let title: String
    let body: String
    let silent: Bool

    /// The node id of the sender of a private message: where a tap leads.
    let peerHex: String?

    static func directIdentifier(_ peerHex: String) -> String { "direct-\(peerHex)" }
    static let broadcastIdentifier = "broadcast"
}

/// Turns a received message into a notification: whose it is, what it says, whether it makes a sound. Pure (the clock and
/// the names are passed in), so it can be tested without the system's notification center.
struct MessageNotificationPlanner {
    /// A burst from one person (or of announcements of one importance) makes one sound per this many milliseconds.
    static let cooldownMs: Int64 = 3_000

    /// The longest text a notification shows, in characters.
    static let maxTextChars = 300

    private var gate = AlertGate()

    /// Announcements that arrived since the announcements screen was last open.
    private(set) var unseenAnnouncements = 0

    /// The announcements screen was opened: the next announcement starts a new count.
    mutating func announcementsSeen() {
        unseenAnnouncements = 0
    }

    mutating func plan(
        kind: MessageKind,
        sender: Data,
        body: Data,
        names: [String: String],
        language: AppLanguage,
        nowMs: Int64
    ) -> NotificationPlan? {
        switch kind {
        case .direct: return directPlan(sender: sender, body: body, names: names, language: language, nowMs: nowMs)
        case .broadcast: return broadcastPlan(body: body, language: language, nowMs: nowMs)
        default: return nil
        }
    }

    private mutating func directPlan(
        sender: Data,
        body: Data,
        names: [String: String],
        language: AppLanguage,
        nowMs: Int64
    ) -> NotificationPlan {
        let hex = Hex.encode(sender)
        let name = ContactBook.displayName(
            hex: hex,
            names: names,
            unknownFormat: L10n.string("name_unknown_node %@", language: language)
        )
        let text = Self.clean(String(decoding: body, as: UTF8.self))
        let loud = gate.shouldAlert("direct:\(hex)", nowMs: nowMs, cooldownMs: Self.cooldownMs)
        return NotificationPlan(
            identifier: NotificationPlan.directIdentifier(hex),
            kind: .direct,
            thread: hex,
            title: name,
            body: text.isEmpty ? L10n.string("notif_direct_no_text", language: language) : text,
            silent: !loud,
            peerHex: hex
        )
    }

    private mutating func broadcastPlan(body: Data, language: AppLanguage, nowMs: Int64) -> NotificationPlan {
        let decoded = decodeAnnouncement(payload: body)
        unseenAnnouncements += 1
        let title: String
        if unseenAnnouncements > 1 {
            title = L10n.string("notif_broadcast_many %lld", language: language, unseenAnnouncements)
        } else {
            title = L10n.string(Self.titleKey(decoded.severity), language: language)
        }
        let text = Self.clean(decoded.text)
        let loud = gate.shouldAlert("broadcast:\(decoded.severity)", nowMs: nowMs, cooldownMs: Self.cooldownMs)
        return NotificationPlan(
            identifier: NotificationPlan.broadcastIdentifier,
            kind: .broadcast,
            thread: "announcements",
            title: title,
            body: text.isEmpty ? L10n.string("notif_direct_no_text", language: language) : text,
            silent: !loud,
            peerHex: nil
        )
    }

    static func titleKey(_ severity: AnnouncementSeverity) -> String {
        switch severity {
        case .normal: return "notif_broadcast_normal"
        case .important: return "notif_broadcast_important"
        case .emergency: return "notif_broadcast_emergency"
        }
    }

    /// One line, cleaned of control characters and direction tricks, cut to ``maxTextChars``.
    static func clean(_ text: String) -> String {
        let line = sanitizeText(text: text).replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        guard line.count > maxTextChars else { return line }
        return String(line.prefix(maxTextChars)).trimmingCharacters(in: .whitespaces) + "…"
    }
}

/// Whether the app's window is covered while the app is not in use (the app switcher shows a snapshot of it).
enum PrivacyCover {
    /// iOS only: a Mac has no app switcher snapshot, and covering the window whenever another app is in front would only
    /// be in the way.
    static func shouldCover(enabled: Bool, active: Bool, switcherExists: Bool = defaultSwitcherExists) -> Bool {
        enabled && !active && switcherExists
    }

    static var defaultSwitcherExists: Bool {
        #if os(iOS)
        return true
        #else
        return false
        #endif
    }
}
