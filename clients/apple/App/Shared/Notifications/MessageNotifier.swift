import CrisisCore
import Foundation
import UserNotifications
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The notification permission as the settings screen shows it, and the ways to change it: ask (the system shows its
/// dialog once) or open the system's notification settings (after a refusal).
@MainActor
final class NotificationAccess: ObservableObject {
    @Published private(set) var status: UNAuthorizationStatus = .notDetermined

    var action: NotificationAccessAction { NotificationAccessPlan.decide(status) }

    func refresh() async {
        status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// Asks for the permission. Alerts and sounds for messages, the badge for the unread count; nothing else.
    func request() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await refresh()
    }

    func openSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
        #elseif os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }
}

/// Local notifications for messages that arrive while the app is not on screen (phase I7). iOS has no push for this app:
/// the node receives messages over Bluetooth, and when the system wakes the app for that (Bluetooth background modes,
/// state restoration) a message is shown as a notification, the same way as when the app is just in the background.
///
/// * A private message makes one notification per person; the next message of that person replaces it.
/// * Announcements make one notification that counts them.
/// * Nothing is shown while the app is on screen: the badges of the tabs say it. Opening a chat or the announcements
///   removes their notifications.
/// * The lock screen shows no names and no text (the categories carry a placeholder text for hidden previews); with
///   previews allowed the notification reads like any message.
@MainActor
final class MessageNotifier {
    static let shared = MessageNotifier()

    private var planner = MessageNotificationPlanner()
    private let center = UNUserNotificationCenter.current()

    /// The categories of the notifications, with the text shown when the system hides previews. Called at launch and
    /// before a notification is posted, so a language change is picked up.
    func registerCategories() {
        let language = UiPreferences.shared.language
        let direct = UNNotificationCategory(
            identifier: NotificationKind.direct.rawValue,
            actions: [],
            intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: L10n.string("notif_direct_public", language: language),
            options: []
        )
        let broadcast = UNNotificationCategory(
            identifier: NotificationKind.broadcast.rawValue,
            actions: [],
            intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: L10n.string("notif_broadcast_public", language: language),
            options: []
        )
        center.setNotificationCategories([direct, broadcast])
    }

    /// Called for every notification of the node. Shows a message that arrived while the app is not on screen.
    func handle(_ notification: CoreNotification) {
        guard case let .messageReceived(_, sender, kind, body) = notification else { return }
        // An introduction (a contact card sent by the person who scanned our code) is not a message to announce.
        if kind == .direct, Introduction.parse(body, owner: sender) != nil { return }
        // While the app is on screen the badges and the open screen show the message.
        guard !BleLinkTestModel.shared.isForeground else { return }
        Task { await post(kind: kind, sender: sender, body: body) }
    }

    private func post(kind: MessageKind, sender: Data, body: Data) async {
        let status = await center.notificationSettings().authorizationStatus
        guard NotificationAccessPlan.decide(status) == .none else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard let plan = planner.plan(
            kind: kind,
            sender: sender,
            body: body,
            names: ContactsModel.shared.names,
            language: UiPreferences.shared.language,
            nowMs: now
        ) else { return }
        registerCategories()

        let content = UNMutableNotificationContent()
        content.title = plan.title
        content.body = plan.body
        content.threadIdentifier = plan.thread
        content.categoryIdentifier = plan.kind.rawValue
        content.sound = plan.silent ? nil : .default
        if let peer = plan.peerHex { content.userInfo = ["peer": peer] } else { content.userInfo = ["open": "announcements"] }
        try? await center.add(UNNotificationRequest(identifier: plan.identifier, content: content, trigger: nil))
    }

    /// The chat with `peerHex` is open: its notification is no longer needed.
    func chatOpened(_ peerHex: String) {
        center.removeDeliveredNotifications(withIdentifiers: [NotificationPlan.directIdentifier(peerHex)])
    }

    /// The announcements are open.
    func announcementsOpened() {
        planner.announcementsSeen()
        center.removeDeliveredNotifications(withIdentifiers: [NotificationPlan.broadcastIdentifier])
    }
}

/// The unread count on the app icon.
enum AppBadge {
    static func set(_ count: Int) {
        Task { try? await UNUserNotificationCenter.current().setBadgeCount(max(count, 0)) }
    }
}

/// What a tap on a notification does: the chat of the sender, or the announcements. Also keeps notifications from showing
/// while the app is on screen. Set as the center's delegate at launch, so that a tap that starts the app is not lost.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationRouter()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // The app is on screen: the badges show the message.
        completionHandler([])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        let peer = info["peer"] as? String
        let open = info["open"] as? String
        Task { @MainActor in
            NotificationRouter.route(peer: peer, open: open)
            completionHandler()
        }
    }

    @MainActor
    static func route(peer: String?, open: String?) {
        if let peer {
            AppNavigator.shared.openChat(peer)
        } else if open == "announcements" {
            AppNavigator.shared.openAnnouncements()
        } else {
            return
        }
        #if os(macOS)
        // The window may be closed (the mesh runs on in the menu bar): a tap brings it back.
        MacDesktop.shared.showMainWindow()
        #endif
    }
}
