import CrisisCore
import UserNotifications
import XCTest
@testable import CrisisNetwork_iOS

/// Phase I7: the choices for the node (store size, default lifetime), the way sizes and counters are written, what a
/// received message becomes as a notification, and the cover of the app switcher. The notification center itself and the
/// screens are not tested by a machine.
final class NetworkAndNotificationsTests: XCTestCase {
    // MARK: Helpers

    private let english = Locale(identifier: "en_US")
    private let turkish = Locale(identifier: "tr_TR")

    private func isolatedDefaults() -> (UserDefaults, String) {
        let suite = "org.crisisnetwork.tests.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }

    private func sender(_ byte: UInt8) -> Data { Data(repeating: byte, count: 32) }

    // MARK: Store size and default lifetime

    func testTheStoreSizesAreChoicesAndAQuarterOfThemMayBePinned() {
        XCTAssertEqual(StoreLimits.presetsMb, [8, 16, 32, 64, 128, 256])
        XCTAssertTrue(StoreLimits.presetsMb.contains(StoreLimits.defaultMb))
        XCTAssertEqual(StoreLimits.bytes(of: 32), 33_554_432)
        XCTAssertEqual(StoreLimits.inboundPinnedBytes(of: 32), 8_388_608)
        XCTAssertEqual(StoreLimits.bytes(of: -5), 0)
    }

    func testAValueThatIsNotAChoiceBecomesTheDefault() {
        XCTAssertEqual(StoreLimits.coerce(64), 64)
        XCTAssertEqual(StoreLimits.coerce(50), StoreLimits.defaultMb)
        XCTAssertEqual(StoreLimits.coerce(0), StoreLimits.defaultMb)

        let day = conventionInfo().defaultLifetimeSecs
        XCTAssertEqual(DefaultLifetimes.coerce(3_600), 3_600)
        XCTAssertEqual(DefaultLifetimes.coerce(7), day)
        XCTAssertEqual(DefaultLifetimes.coerce(Int64.max), day)
        XCTAssertTrue(DefaultLifetimes.options().contains(day))
    }

    func testTheChoicesBecomeTheSettingsTheCoreOpensWith() {
        let base = defaultSettings()
        let settings = NodeChoices(storeLimitMb: 64, defaultLifetimeSecs: 3_600).nodeSettings(base: base)
        XCTAssertEqual(settings.maxStoreBytes, 67_108_864)
        XCTAssertEqual(settings.maxInboundPinnedBytes, 16_777_216)
        XCTAssertEqual(settings.defaultLifetimeSecs, 3_600)
        // Everything else stays as it was.
        XCTAssertEqual(settings.maxPeers, base.maxPeers)
    }

    func testTheChoicesAreRememberedAndASpoiledValueIsCorrected() {
        let (defaults, suite) = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(NodeChoices.load(from: defaults).storeLimitMb, StoreLimits.defaultMb)

        NodeChoices(storeLimitMb: 128, defaultLifetimeSecs: 900).save(to: defaults)
        XCTAssertEqual(NodeChoices.load(from: defaults), NodeChoices(storeLimitMb: 128, defaultLifetimeSecs: 900))

        defaults.set(999, forKey: "node.storeLimitMb")
        defaults.set(NSNumber(value: 13), forKey: "node.defaultLifetimeSecs")
        let corrected = NodeChoices.load(from: defaults)
        XCTAssertEqual(corrected.storeLimitMb, StoreLimits.defaultMb)
        XCTAssertEqual(corrected.defaultLifetimeSecs, conventionInfo().defaultLifetimeSecs)
    }

    @MainActor
    func testChangingAChoiceInThePreferencesSavesItAtOnce() {
        let (defaults, suite) = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = NodePreferences(defaults: defaults)
        prefs.storeLimitMb = 16
        prefs.defaultLifetimeSecs = 3_600
        XCTAssertEqual(NodeChoices.load(from: defaults), NodeChoices(storeLimitMb: 16, defaultLifetimeSecs: 3_600))
        XCTAssertEqual(prefs.choices, NodeChoices(storeLimitMb: 16, defaultLifetimeSecs: 3_600))
        XCTAssertEqual(NodePreferences(defaults: defaults).storeLimitMb, 16)
    }

    // MARK: Sizes and counters

    func testSizesAreWrittenInTheLargestUnitThatFits() {
        XCTAssertEqual(ByteSizes.format(Int64(0), locale: english), "0 B")
        XCTAssertEqual(ByteSizes.format(Int64(1_023), locale: english), "1023 B")
        XCTAssertEqual(ByteSizes.format(Int64(1_024), locale: english), "1.0 KB")
        XCTAssertEqual(ByteSizes.format(Int64(1_536), locale: english), "1.5 KB")
        XCTAssertEqual(ByteSizes.format(Int64(100 * 1_024), locale: english), "100 KB")
        XCTAssertEqual(ByteSizes.format(Int64(1_048_576), locale: english), "1.0 MB")
        XCTAssertEqual(ByteSizes.format(Int64(32 * 1_048_576), locale: english), "32.0 MB")
        XCTAssertEqual(ByteSizes.format(Int64(5) * 1_073_741_824, locale: english), "5.0 GB")
        // Just below a unit: "1024.0 KB" never shows.
        XCTAssertEqual(ByteSizes.format(Int64(1_048_575), locale: english), "1.0 MB")
        XCTAssertEqual(ByteSizes.format(Int64(-7), locale: english), "0 B")
        XCTAssertEqual(ByteSizes.format(UInt64(2_048), locale: english), "2.0 KB")
        XCTAssertEqual(ByteSizes.format(UInt64.max, locale: english).hasSuffix("TB"), true)
    }

    func testTheDecimalSeparatorFollowsTheLanguage() {
        XCTAssertEqual(ByteSizes.format(Int64(1_536), locale: turkish), "1,5 KB")
        XCTAssertEqual(ByteSizes.count(UInt64(1_234_567), locale: english), "1,234,567")
        XCTAssertEqual(ByteSizes.count(UInt64(1_234_567), locale: turkish), "1.234.567")
        XCTAssertEqual(ByteSizes.count(UInt64(12), locale: english), "12")
    }

    func testHowFullTheStoreIsStaysBetweenZeroAndOne() {
        XCTAssertEqual(ByteSizes.fraction(used: 8, limit: 32), 0.25, accuracy: 0.0001)
        XCTAssertEqual(ByteSizes.fraction(used: 64, limit: 32), 1)
        XCTAssertEqual(ByteSizes.fraction(used: 5, limit: 0), 0)
        XCTAssertEqual(ByteSizes.fraction(used: 0, limit: 32), 0)
    }

    func testTheCountersOfTheNodeAreInThreeGroupsAndNoneIsLost() {
        let stats = NodeStats(
            packetsReceived: 1, packetsSent: 2, unknownPeerPackets: 3, rateLimited: 4, malformedPackets: 5,
            fragmentsRejected: 6, expiredTransfers: 7, invalidSignatures: 8, blockedDropped: 9, senderRateLimited: 10,
            duplicates: 11, tombstoned: 12, quotaRejected: 13, framesAccepted: 14, undecryptable: 15, acksSent: 16,
            wantGaveUp: 17, inflightFull: 18, queueFull: 19, peersBanned: 20, storedMessages: 21, storedBytes: 22
        )
        let groups = NetworkBook.statGroups(stats)
        XCTAssertEqual(groups.map(\.titleKey), ["net_group_traffic", "net_group_refused", "net_group_trouble"])
        let rows = groups.flatMap(\.rows)
        // Twenty counters; the two of the store have their own card.
        XCTAssertEqual(rows.count, 20)
        XCTAssertEqual(Set(rows.map(\.key)).count, 20)
        XCTAssertEqual(Set(rows.map(\.value)), Set(UInt64(1)...UInt64(20)))
        let byKey = Dictionary(uniqueKeysWithValues: rows.map { ($0.key, $0.value) })
        XCTAssertEqual(byKey["net_stat_packets_received"], 1)
        XCTAssertEqual(byKey["net_stat_frames_accepted"], 14)
        XCTAssertEqual(byKey["net_stat_peers_banned"], 20)
    }

    // MARK: Notification permission and the alert gate

    func testTheSettingsOfferWhatTheSystemStillAllows() {
        XCTAssertEqual(NotificationAccessPlan.decide(.authorized), .none)
        XCTAssertEqual(NotificationAccessPlan.decide(.provisional), .none)
        XCTAssertEqual(NotificationAccessPlan.decide(.notDetermined), .askInApp)
        XCTAssertEqual(NotificationAccessPlan.decide(.denied), .openSettings)
    }

    func testABurstMakesASoundOnlyOncePerCooldownAndPerKey() {
        var gate = AlertGate()
        XCTAssertTrue(gate.shouldAlert("a", nowMs: 1_000, cooldownMs: 3_000))
        XCTAssertFalse(gate.shouldAlert("a", nowMs: 2_000, cooldownMs: 3_000))
        XCTAssertFalse(gate.shouldAlert("a", nowMs: 3_999, cooldownMs: 3_000))
        XCTAssertTrue(gate.shouldAlert("a", nowMs: 4_000, cooldownMs: 3_000))
        // Another key is its own burst.
        XCTAssertTrue(gate.shouldAlert("b", nowMs: 4_100, cooldownMs: 3_000))
        // A clock that went back never silences a message.
        XCTAssertTrue(gate.shouldAlert("a", nowMs: 100, cooldownMs: 3_000))
    }

    // MARK: A message as a notification

    func testAPrivateMessageNotifiesWithTheNameOfTheSenderAndOneLineOfText() {
        var planner = MessageNotificationPlanner()
        let hex = String(repeating: "09", count: 32)
        let plan = planner.plan(
            kind: .direct, sender: sender(9), body: Data("hello\nthere".utf8),
            names: [hex: "Ayşe"], language: .english, nowMs: 1_000
        )
        XCTAssertEqual(plan?.identifier, "direct-\(hex)")
        XCTAssertEqual(plan?.kind, .direct)
        XCTAssertEqual(plan?.thread, hex)
        XCTAssertEqual(plan?.title, "Ayşe")
        XCTAssertEqual(plan?.body, "hello there")
        XCTAssertEqual(plan?.silent, false)
        XCTAssertEqual(plan?.peerHex, hex)
    }

    func testTheNextMessageOfTheSamePersonReplacesTheNotificationAndIsSilent() {
        var planner = MessageNotificationPlanner()
        let first = planner.plan(kind: .direct, sender: sender(9), body: Data("one".utf8), names: [:], language: .english, nowMs: 1_000)
        let second = planner.plan(kind: .direct, sender: sender(9), body: Data("two".utf8), names: [:], language: .english, nowMs: 2_000)
        let later = planner.plan(kind: .direct, sender: sender(9), body: Data("three".utf8), names: [:], language: .english, nowMs: 5_000)
        let other = planner.plan(kind: .direct, sender: sender(8), body: Data("hi".utf8), names: [:], language: .english, nowMs: 2_100)
        XCTAssertEqual(second?.identifier, first?.identifier)
        XCTAssertEqual(first?.silent, false)
        XCTAssertEqual(second?.silent, true)
        XCTAssertEqual(later?.silent, false)
        XCTAssertNotEqual(other?.identifier, first?.identifier)
        XCTAssertEqual(other?.silent, false)
    }

    func testAnUnknownSenderIsCalledByItsShortAddressAndAnEmptyMessageSaysSo() {
        var planner = MessageNotificationPlanner()
        let plan = planner.plan(kind: .direct, sender: sender(9), body: Data(), names: [:], language: .english, nowMs: 1)
        XCTAssertEqual(plan?.title, "Node 09090909")
        XCTAssertEqual(plan?.body, "New message")
        var turkishPlanner = MessageNotificationPlanner()
        let turkishPlan = turkishPlanner.plan(kind: .direct, sender: sender(9), body: Data(), names: [:], language: .turkish, nowMs: 1)
        XCTAssertEqual(turkishPlan?.title, "Düğüm 09090909")
        XCTAssertEqual(turkishPlan?.body, "Yeni mesaj")
    }

    func testALongTextIsCutAndControlCharactersAreDropped() {
        let long = MessageNotificationPlanner.clean(String(repeating: "x", count: 500))
        XCTAssertEqual(long.count, MessageNotificationPlanner.maxTextChars + 1)
        XCTAssertTrue(long.hasSuffix("…"))
        XCTAssertEqual(MessageNotificationPlanner.clean("a\u{7}b"), "ab")
        XCTAssertEqual(MessageNotificationPlanner.clean("  padded \n"), "padded")
    }

    func testAnnouncementsShareOneNotificationThatCountsThem() {
        var planner = MessageNotificationPlanner()
        let body = encodeAnnouncement(text: "Water at the school", severity: .important)
        let first = planner.plan(kind: .broadcast, sender: sender(1), body: body, names: [:], language: .english, nowMs: 1_000)
        XCTAssertEqual(first?.identifier, "broadcast")
        XCTAssertEqual(first?.kind, .broadcast)
        XCTAssertNil(first?.peerHex)
        XCTAssertEqual(first?.title, "Important announcement")
        XCTAssertEqual(first?.body, "Water at the school")
        XCTAssertEqual(planner.unseenAnnouncements, 1)

        let second = planner.plan(kind: .broadcast, sender: sender(2), body: body, names: [:], language: .english, nowMs: 1_500)
        XCTAssertEqual(second?.identifier, "broadcast")
        XCTAssertEqual(second?.title, "2 new announcements")
        XCTAssertEqual(second?.silent, true)

        // The announcements were opened: the next one starts a new count.
        planner.announcementsSeen()
        let third = planner.plan(kind: .broadcast, sender: sender(3), body: body, names: [:], language: .english, nowMs: 9_000)
        XCTAssertEqual(third?.title, "Important announcement")
        XCTAssertEqual(third?.silent, false)
    }

    func testAnEmergencyStillMakesASoundRightAfterANormalAnnouncement() {
        var planner = MessageNotificationPlanner()
        let normal = planner.plan(
            kind: .broadcast, sender: sender(1), body: encodeAnnouncement(text: "All clear", severity: .normal),
            names: [:], language: .english, nowMs: 1_000
        )
        let emergency = planner.plan(
            kind: .broadcast, sender: sender(1), body: encodeAnnouncement(text: "Evacuate", severity: .emergency),
            names: [:], language: .turkish, nowMs: 1_200
        )
        XCTAssertEqual(normal?.silent, false)
        XCTAssertEqual(emergency?.silent, false)
        XCTAssertEqual(emergency?.title, "2 yeni duyuru")
        XCTAssertEqual(emergency?.body, "Evacuate")
    }

    func testACommandOfTheCoreThatIsNoMessageNotifiesNothing() {
        var planner = MessageNotificationPlanner()
        XCTAssertNil(planner.plan(kind: .ack, sender: sender(1), body: Data(), names: [:], language: .english, nowMs: 1))
    }

    func testTheTitlesOfAnnouncementsNameTheirImportance() {
        XCTAssertEqual(MessageNotificationPlanner.titleKey(.normal), "notif_broadcast_normal")
        XCTAssertEqual(MessageNotificationPlanner.titleKey(.important), "notif_broadcast_important")
        XCTAssertEqual(MessageNotificationPlanner.titleKey(.emergency), "notif_broadcast_emergency")
    }

    // MARK: The cover of the app switcher

    func testTheCoverIsOnlyDrawnWhenItIsWantedTheAppIsNotInUseAndThereIsASwitcher() {
        XCTAssertTrue(PrivacyCover.shouldCover(enabled: true, active: false, switcherExists: true))
        XCTAssertFalse(PrivacyCover.shouldCover(enabled: true, active: true, switcherExists: true))
        XCTAssertFalse(PrivacyCover.shouldCover(enabled: false, active: false, switcherExists: true))
        XCTAssertFalse(PrivacyCover.shouldCover(enabled: true, active: false, switcherExists: false))
    }

    @MainActor
    func testTheCoverIsOnByDefaultAndTheChoiceIsRemembered() {
        let (defaults, suite) = isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = UiPreferences(defaults: defaults)
        XCTAssertTrue(prefs.hideInSwitcher)
        prefs.hideInSwitcher = false
        XCTAssertFalse(UiPreferences(defaults: defaults).hideInSwitcher)
    }

    // MARK: Navigation and strings

    @MainActor
    func testATapOnAnAnnouncementNotificationOpensTheAnnouncements() {
        let navigator = AppNavigator()
        navigator.openAnnouncements()
        XCTAssertEqual(navigator.destinationRequest, .announcements)
    }

    @MainActor
    func testTheRouterOpensTheChatOfTheSenderOrTheAnnouncements() {
        let navigator = AppNavigator.shared
        navigator.destinationRequest = nil
        navigator.chatsPath = []
        NotificationRouter.route(peer: "abcd", open: nil)
        XCTAssertEqual(navigator.chatsPath, [.chat("abcd")])
        XCTAssertEqual(navigator.destinationRequest, .chats)

        navigator.destinationRequest = nil
        NotificationRouter.route(peer: nil, open: "announcements")
        XCTAssertEqual(navigator.destinationRequest, .announcements)

        navigator.destinationRequest = nil
        NotificationRouter.route(peer: nil, open: "something else")
        XCTAssertNil(navigator.destinationRequest)
        navigator.chatsPath = []
    }

    func testTheStringsOfTheNetworkSettingsAndNotificationsAreTranslated() {
        var keys = [
            "settings_notifications", "settings_notifications_on", "settings_notifications_ask",
            "settings_notifications_allow", "settings_notifications_off", "settings_notifications_hint",
            "settings_privacy", "settings_hide_switcher", "settings_hide_switcher_hint", "net_title",
            "settings_open_network", "settings_storage_title", "settings_pending", "settings_storage_hint",
            "settings_lifetime_title", "settings_lifetime_hint", "settings_about", "home_network_details",
            "net_not_running", "net_neighbours_title", "net_neighbours_none", "net_link_active", "net_link_connecting",
            "net_link_role_client", "net_link_role_server", "net_storage_title", "net_loading", "net_radio_title",
            "net_radio_central", "net_radio_peripheral", "net_radio_scanning", "net_radio_advertising",
            "net_radio_connections", "net_radio_backed_off", "net_radio_connect_failures", "net_radio_on", "net_radio_off",
            "net_radio_unauthorized", "net_radio_unsupported", "net_radio_unknown", "net_yes", "net_no",
            "net_group_traffic", "net_group_refused", "net_group_trouble", "notif_direct_no_text", "notif_direct_public",
            "notif_broadcast_public", "notif_broadcast_normal", "notif_broadcast_important", "notif_broadcast_emergency",
        ]
        // Every counter named by ``NetworkBook`` has its words.
        keys += NetworkBook.statGroups(NodeStats(
            packetsReceived: 0, packetsSent: 0, unknownPeerPackets: 0, rateLimited: 0, malformedPackets: 0,
            fragmentsRejected: 0, expiredTransfers: 0, invalidSignatures: 0, blockedDropped: 0, senderRateLimited: 0,
            duplicates: 0, tombstoned: 0, quotaRejected: 0, framesAccepted: 0, undecryptable: 0, acksSent: 0,
            wantGaveUp: 0, inflightFull: 0, queueFull: 0, peersBanned: 0, storedMessages: 0, storedBytes: 0
        )).flatMap { $0.rows.map(\.key) }
        for key in keys {
            let en = L10n.string(key, language: .english)
            let tr = L10n.string(key, language: .turkish)
            XCTAssertNotEqual(en, key, "\(key) is missing in English")
            XCTAssertNotEqual(tr, key, "\(key) is missing in Turkish")
            XCTAssertNotEqual(en, tr, "\(key) is the same in both languages")
        }
    }

    func testStringsWithArgumentsAndPluralsAreFilledIn() {
        XCTAssertEqual(L10n.string("net_storage_used %@ %@", language: .english, "8 MB", "32 MB"), "8 MB of 32 MB used")
        XCTAssertEqual(L10n.string("net_storage_used %@ %@", language: .turkish, "8 MB", "32 MB"), "32 MB içinden 8 MB kullanıldı")
        XCTAssertEqual(L10n.string("net_storage_messages %lld", language: .english, 1), "1 message")
        XCTAssertEqual(L10n.string("net_storage_messages %lld", language: .english, 5), "5 messages")
        XCTAssertEqual(L10n.string("notif_broadcast_many %lld", language: .english, 3), "3 new announcements")
        XCTAssertEqual(L10n.string("settings_about_app %@ %@", language: .english, "1.0", "42"), "App version 1.0 (42)")
        XCTAssertEqual(L10n.string("settings_about_core %@ %lld %lld", language: .english, "0.1.0", 2, 1), "Core 0.1.0, API 2, protocol 1")
        XCTAssertEqual(L10n.string("net_link_title %lld %@", language: .english, 2, "ab12c"), "Neighbour 2 (…ab12c)")
    }
}
