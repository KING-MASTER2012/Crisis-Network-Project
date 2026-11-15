import CrisisCore
import CrisisRuntime
import XCTest
@testable import CrisisNetwork_iOS

/// Chats and announcements (phase I6): the rules between the node's history and the screens, the model with a real core
/// underneath, paging, the read markers, and a message that really travels between two runtimes. The conventions
/// themselves (texts, byte budgets, lifetimes) are tested against the shared vectors in the `CrisisCore` package.
final class MessagingTests: XCTestCase {
    // MARK: Helpers

    private static let now: UInt64 = 1_000_000

    private func node(_ seedByte: UInt8) throws -> CrisisNode {
        try CrisisNode.inMemory(seed: Data(repeating: seedByte, count: 32), settings: defaultSettings(), nowMs: Self.now)
    }

    private func item(
        seq: UInt64,
        outgoing: Bool,
        kind: MessageKind = .direct,
        body: Data? = Data("hello".utf8),
        counterpart: UInt8 = 7,
        delivered: Bool = false
    ) -> HistoryItem {
        HistoryItem(
            seq: seq,
            id: Data(repeating: UInt8(truncatingIfNeeded: seq), count: 32),
            direction: outgoing ? .outgoing : .incoming,
            kind: kind,
            counterpart: Data(repeating: counterpart, count: 32),
            body: body,
            createdAtMs: 1_000,
            receivedAtMs: 2_000,
            deliveredAtMs: delivered ? 3_000 : nil
        )
    }

    /// A node of the core without a transport, as the model's backend.
    final class NodeBackend: MessagingBackend, @unchecked Sendable {
        let node: CrisisNode
        init(_ node: CrisisNode) { self.node = node }
        func conversations(limit: UInt32) async throws -> [ConversationInfo] {
            try node.conversations(limit: limit, nowMs: 1_000_000)
        }
        func conversationHistory(conversation: ConversationRef, beforeSeq: UInt64?, limit: UInt32) async throws -> ConversationPageInfo {
            try node.conversationHistory(conversation: conversation, beforeSeq: beforeSeq, limit: limit, nowMs: 1_000_000)
        }
        func markConversationRead(conversation: ConversationRef, upToSeq: UInt64?) async throws -> UInt64 {
            try node.markConversationRead(conversation: conversation, upToSeq: upToSeq)
        }
        func unreadCounts() async throws -> UnreadInfo { try node.unreadCounts(nowMs: 1_000_000) }
        func publishDirect(to: Data, plaintext: Data) async throws -> PublishOutcome {
            try node.publishDirect(nowMs: 1_000_000, to: to, plaintext: plaintext)
        }
        func publishBroadcast(payload: Data, lifetimeSecs: UInt32?) async throws -> PublishOutcome {
            if let lifetimeSecs {
                return try node.publishBroadcastWithLifetime(nowMs: 1_000_000, payload: payload, lifetimeSecs: lifetimeSecs)
            }
            return try node.publishBroadcast(nowMs: 1_000_000, payload: payload)
        }
    }

    @MainActor
    private func makeModel(_ backend: (any MessagingBackend)?) -> MessagingModel {
        MessagingModel(backend: { backend })
    }

    // MARK: Previews and rows

    func testAPreviewIsOneLineAndCutWithAnEllipsis() {
        XCTAssertEqual(MessageBook.preview(of: Data("two\nlines".utf8)), "two lines")
        XCTAssertEqual(MessageBook.preview(of: Data("  padded  ".utf8)), "padded")
        let long = MessageBook.preview(of: Data(String(repeating: "x", count: 300).utf8))
        XCTAssertEqual(long.count, MessageBook.previewChars + 1)
        XCTAssertTrue(long.hasSuffix("…"))
        XCTAssertEqual(MessageBook.preview(of: Data("a\u{7}b".utf8)), "ab")
    }

    func testAPrivateMessageBecomesARowWithItsStateAndSender() {
        let sent = MessageBook.row(from: item(seq: 4, outgoing: true, delivered: true))
        XCTAssertEqual(sent.text, "hello")
        XCTAssertTrue(sent.outgoing)
        XCTAssertEqual(sent.status, .delivered)
        XCTAssertNil(sent.senderHex)
        XCTAssertEqual(sent.timeMs, 2_000)

        let waiting = MessageBook.row(from: item(seq: 5, outgoing: true))
        XCTAssertEqual(waiting.status, .published)

        let received = MessageBook.row(from: item(seq: 6, outgoing: false, counterpart: 9))
        XCTAssertFalse(received.outgoing)
        XCTAssertNil(received.status)
        XCTAssertEqual(received.senderHex, String(repeating: "09", count: 32))
    }

    func testAnAnnouncementRowReadsItsSeverityAndAMessageWithoutABodyIsUnreadable() {
        let body = encodeAnnouncement(text: "Water at the school", severity: .important)
        let announcement = MessageBook.row(from: item(seq: 1, outgoing: false, kind: .broadcast, body: body))
        XCTAssertEqual(announcement.text, "Water at the school")
        XCTAssertEqual(announcement.severity, .important)

        let plain = MessageBook.row(from: item(seq: 2, outgoing: false, kind: .broadcast, body: Data("plain".utf8)))
        XCTAssertEqual(plain.text, "plain")
        XCTAssertEqual(plain.severity, .normal)

        let unreadable = MessageBook.row(from: item(seq: 3, outgoing: false, body: nil))
        XCTAssertNil(unreadable.text)
    }

    func testOnlyDirectConversationsAreChats() {
        let direct = ConversationInfo(
            conversation: .direct(peer: Data(repeating: 7, count: 32)),
            last: item(seq: 9, outgoing: true),
            messageCount: 3,
            unread: 2
        )
        let row = MessageBook.chatRow(from: direct)
        XCTAssertEqual(row?.peerHex, String(repeating: "07", count: 32))
        XCTAssertEqual(row?.preview, "hello")
        XCTAssertEqual(row?.lastOutgoing, true)
        XCTAssertEqual(row?.unread, 2)
        XCTAssertEqual(row?.lastTimeMs, 2_000)

        let feed = ConversationInfo(conversation: .announcements, last: nil, messageCount: 0, unread: 0)
        XCTAssertNil(MessageBook.chatRow(from: feed))
    }

    func testAMessageOfTodayShowsOnlyItsTime() {
        let calendar = Calendar(identifier: .gregorian)
        let noon: UInt64 = 1_700_000_000_000
        XCTAssertTrue(MessageBook.isToday(noon, now: noon + 60_000, calendar: calendar))
        XCTAssertFalse(MessageBook.isToday(noon, now: noon + 3 * 86_400_000, calendar: calendar))
    }

    // MARK: The window over a conversation

    func testAWindowRemembersWhatWasUnreadWhenItOpenedAndPagesBackwards() {
        var window = ConversationWindow(ref: .announcements)
        XCTAssertFalse(window.loaded)
        let newest = [
            item(seq: 10, outgoing: false),
            item(seq: 11, outgoing: true),
            item(seq: 12, outgoing: false),
        ]
        window.replace(with: ConversationPageInfo(entries: newest, olderBeforeSeq: 10, readUpToSeq: 10))
        XCTAssertTrue(window.loaded)
        XCTAssertEqual(window.firstUnreadSeq, 12)
        XCTAssertTrue(window.hasUnread)
        XCTAssertEqual(window.olderBefore, 10)

        window.didMarkRead(upTo: 12)
        XCTAssertFalse(window.hasUnread)
        // The "new messages" line stays where it was while the screen is open.
        window.replace(with: ConversationPageInfo(entries: newest, olderBeforeSeq: 10, readUpToSeq: 12))
        XCTAssertEqual(window.firstUnreadSeq, 12)

        window.prepend(ConversationPageInfo(entries: [item(seq: 8, outgoing: true), item(seq: 9, outgoing: true)], olderBeforeSeq: nil, readUpToSeq: 12))
        XCTAssertEqual(window.entries.map(\.seq), [8, 9, 10, 11, 12])
        XCTAssertNil(window.olderBefore)
    }

    func testARefreshReadsAsManyMessagesAsAreLoadedPlusRoom() {
        var window = ConversationWindow(ref: .announcements)
        XCTAssertEqual(window.refreshSize, ConversationWindow.pageSize)
        let many = (1...100).map { item(seq: UInt64($0), outgoing: true) }
        window.replace(with: ConversationPageInfo(entries: many, olderBeforeSeq: nil, readUpToSeq: 0))
        XCTAssertEqual(window.refreshSize, 108)
        let huge = (1...2_500).map { item(seq: UInt64($0), outgoing: true) }
        window.replace(with: ConversationPageInfo(entries: huge, olderBeforeSeq: nil, readUpToSeq: 0))
        XCTAssertEqual(window.refreshSize, ConversationWindow.maxSize)
    }

    func testTheLimitsFollowTheCore() {
        let limits = Limits.fromCore()
        XCTAssertEqual(limits.maxPayloadBytes, Int(coreInfo().maxPayloadBytes))
        XCTAssertEqual(limits.directLimit, limits.maxPayloadBytes - Int(conventionInfo().directSealOverhead))
        XCTAssertEqual(limits.announcementLimit(.normal), limits.maxPayloadBytes)
        XCTAssertEqual(limits.announcementLimit(.important), limits.maxPayloadBytes - Int(announcementOverhead(severity: .important)))
    }

    // MARK: The model with a real core

    @MainActor
    func testAPrivateMessageIsStoredAndShowsInTheChatAndTheList() async throws {
        let mine = try node(1), theirs = try node(2)
        _ = try mine.addContact(nowMs: Self.now, card: theirs.contactCard())
        let model = makeModel(NodeBackend(mine))
        let hex = theirs.nodeIdHex()

        model.openChat(hex)
        let result = await model.sendDirect(peerHex: hex, text: "  hello there \n")
        XCTAssertEqual(result, .sent)

        XCTAssertTrue(model.ready)
        XCTAssertEqual(model.chat.messages.map(\.text), ["hello there"])
        XCTAssertEqual(model.chat.messages.first?.outgoing, true)
        XCTAssertEqual(model.chat.messages.first?.status, .published)
        XCTAssertEqual(model.chats.map(\.peerHex), [hex])
        XCTAssertEqual(model.chats.first?.preview, "hello there")
        XCTAssertEqual(model.chats.first?.lastOutgoing, true)
        XCTAssertEqual(model.unread, Unread())
    }

    @MainActor
    func testSendingFailsWithAReasonThePersonCanUnderstand() async throws {
        let mine = try node(1), theirs = try node(2)
        _ = try mine.addContact(nowMs: Self.now, card: theirs.contactCard())
        let model = makeModel(NodeBackend(mine))
        let hex = theirs.nodeIdHex()

        let empty = await model.sendDirect(peerHex: hex, text: " \n ")
        XCTAssertEqual(empty, .empty)
        let tooLong = await model.sendDirect(peerHex: hex, text: String(repeating: "x", count: model.limits.directLimit + 1))
        XCTAssertEqual(tooLong, .tooLong)
        let fits = await model.sendDirect(peerHex: hex, text: String(repeating: "x", count: model.limits.directLimit))
        XCTAssertEqual(fits, .sent)
        // Bytes count, not characters: a two-byte letter takes two.
        let twoBytes = await model.sendDirect(peerHex: hex, text: String(repeating: "ş", count: model.limits.directLimit / 2 + 1))
        XCTAssertEqual(twoBytes, .tooLong)
        let stranger = await model.sendDirect(peerHex: String(repeating: "ab", count: 32), text: "hi")
        XCTAssertEqual(stranger, .unknownContact)
        let notHex = await model.sendDirect(peerHex: "not hex", text: "hi")
        XCTAssertEqual(notHex, .unknownContact)

        let off = makeModel(nil)
        let meshOff = await off.sendDirect(peerHex: hex, text: "hi")
        XCTAssertEqual(meshOff, .meshOff)
        let announcementOff = await off.publishAnnouncement(text: "hi", severity: .normal, lifetimeSecs: 900)
        XCTAssertEqual(announcementOff, .meshOff)
        XCTAssertFalse(off.ready)
    }

    @MainActor
    func testAnAnnouncementIsPublishedWithItsSeverityAndShowsInTheFeed() async throws {
        let mine = try node(1)
        let model = makeModel(NodeBackend(mine))
        model.openFeed()

        let first = await model.publishAnnouncement(text: " Water at the school ", severity: .important, lifetimeSecs: 900)
        XCTAssertEqual(first, .sent)
        let second = await model.publishAnnouncement(text: "All clear", severity: .normal, lifetimeSecs: 3_600)
        XCTAssertEqual(second, .sent)

        // Newest first.
        XCTAssertEqual(model.feed.messages.map(\.text), ["All clear", "Water at the school"])
        XCTAssertEqual(model.feed.messages.map(\.severity), [.normal, .important])
        XCTAssertTrue(model.feed.messages.allSatisfy { $0.outgoing && $0.senderHex == nil })
        XCTAssertTrue(model.feed.loaded)
        XCTAssertTrue(model.chats.isEmpty, "announcements are not a chat")
    }

    @MainActor
    func testAnAnnouncementWithAWrongLifetimeOrTooLongIsRefusedBeforeTheCore() async throws {
        let model = makeModel(NodeBackend(try node(1)))
        let zero = await model.publishAnnouncement(text: "x", severity: .normal, lifetimeSecs: 0)
        guard case .failed = zero else { return XCTFail("a lifetime of zero was accepted: \(zero)") }
        let beyond = await model.publishAnnouncement(text: "x", severity: .normal, lifetimeSecs: model.limits.maxLifetimeSecs + 1)
        guard case .failed = beyond else { return XCTFail("a lifetime beyond the maximum was accepted: \(beyond)") }
        let longest = await model.publishAnnouncement(text: "x", severity: .normal, lifetimeSecs: model.limits.maxLifetimeSecs)
        XCTAssertEqual(longest, .sent)
        let tooLong = await model.publishAnnouncement(
            text: String(repeating: "x", count: model.limits.announcementLimit(.emergency) + 1),
            severity: .emergency,
            lifetimeSecs: 900
        )
        XCTAssertEqual(tooLong, .tooLong)
        let empty = await model.publishAnnouncement(text: "\u{7}  ", severity: .normal, lifetimeSecs: 900)
        XCTAssertEqual(empty, .empty)
    }

    @MainActor
    func testAnOpenChatPagesBackwardsAndStopsAtTheFirstMessage() async throws {
        let mine = try node(1), theirs = try node(2)
        _ = try mine.addContact(nowMs: Self.now, card: theirs.contactCard())
        let model = makeModel(NodeBackend(mine))
        let hex = theirs.nodeIdHex()
        let total = ConversationWindow.pageSize + 5
        for number in 1...total {
            let sent = await model.sendDirect(peerHex: hex, text: "message \(number)")
            XCTAssertEqual(sent, .sent)
        }

        model.openChat(hex)
        await model.refresh()
        XCTAssertEqual(model.chat.messages.count, ConversationWindow.pageSize)
        XCTAssertTrue(model.chat.hasOlder)
        XCTAssertEqual(model.chat.messages.last?.text, "message \(total)")

        await model.loadOlderChat()
        XCTAssertEqual(model.chat.messages.count, total)
        XCTAssertFalse(model.chat.hasOlder)
        XCTAssertEqual(model.chat.messages.first?.text, "message 1")
        // Oldest first, without gaps.
        XCTAssertEqual(model.chat.messages.map(\.seq), model.chat.messages.map(\.seq).sorted())

        // A new message keeps the older ones that were loaded.
        let more = await model.sendDirect(peerHex: hex, text: "newest")
        XCTAssertEqual(more, .sent)
        XCTAssertEqual(model.chat.messages.count, total + 1)
        XCTAssertEqual(model.chat.messages.last?.text, "newest")
    }

    @MainActor
    func testClosingAChatForgetsItButOnlyForThePersonItWasOpenedFor() async throws {
        let mine = try node(1)
        let model = makeModel(NodeBackend(mine))
        let first = String(repeating: "aa", count: 32), second = String(repeating: "bb", count: 32)
        model.openChat(first)
        model.openChat(second)
        // The screen of the first chat disappears after the second one was opened: the second stays.
        model.closeChat(first)
        XCTAssertEqual(model.chat.peerHex, second)
        model.closeChat(second)
        XCTAssertNil(model.chat.peerHex)
        model.openChat("not hex")
        XCTAssertNil(model.chat.peerHex)
    }

    // MARK: A message that travels

    @MainActor
    func testAMessageFromAnotherRuntimeIsUnreadUntilTheChatIsOpenedAndTheSenderSeesItDelivered() async throws {
        let clock = MonotonicWallClock(store: MemoryHighWaterStore())
        let network = LoopbackNetwork()
        let transportA = network.endpoint(), transportB = network.endpoint()
        let start = UInt64(clock.nowMs())
        let a = NodeRuntime(
            node: try CrisisNode.inMemory(seed: Data(repeating: 1, count: 32), settings: defaultSettings(), nowMs: start),
            transport: transportA,
            clock: clock
        )
        let b = NodeRuntime(
            node: try CrisisNode.inMemory(seed: Data(repeating: 2, count: 32), settings: defaultSettings(), nowMs: start),
            transport: transportB,
            clock: clock
        )
        try await a.start()
        try await b.start()
        defer { Task { await a.stop(); await b.stop() } }
        network.connect(transportA, transportB)
        _ = try await a.addContact(card: b.contactCard)
        _ = try await b.addContact(card: a.contactCard)

        let atA = MessagingModel(backend: { a })
        let atB = MessagingModel(backend: { b })
        let aHex = a.nodeIdHex, bHex = b.nodeIdHex
        atA.openChat(bHex)

        let sent = await atA.sendDirect(peerHex: bHex, text: "are you safe?")
        XCTAssertEqual(sent, .sent)

        // B has not opened the chat: the message waits, unread, in the list and in the badge.
        let arrived = await eventually {
            await atB.refresh()
            return atB.unread.chats == 1
        }
        XCTAssertTrue(arrived, "the message did not reach the other runtime")
        XCTAssertEqual(atB.chats.map(\.peerHex), [aHex])
        XCTAssertEqual(atB.chats.first?.unread, 1)
        XCTAssertEqual(atB.chats.first?.preview, "are you safe?")
        XCTAssertEqual(atB.chats.first?.lastOutgoing, false)

        // Opening the chat shows it with the line above it and marks it read.
        atB.openChat(aHex)
        await atB.refresh()
        XCTAssertEqual(atB.chat.messages.map(\.text), ["are you safe?"])
        XCTAssertEqual(atB.chat.messages.first?.senderHex, aHex)
        XCTAssertEqual(atB.chat.firstUnreadSeq, atB.chat.messages.first?.seq)
        XCTAssertEqual(atB.unread.chats, 0)
        XCTAssertEqual(atB.chats.first?.unread, 0)

        // The acknowledgement comes back: the sender sees the message delivered.
        let delivered = await eventually {
            await atA.refresh()
            return atA.chat.messages.first?.status == .delivered
        }
        XCTAssertTrue(delivered, "the acknowledgement did not come back")
    }

    @MainActor
    private func eventually(timeoutMs: Int = 8_000, _ condition: @MainActor () async -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return await condition()
    }

    // MARK: Navigation and strings

    @MainActor
    func testOpeningAChatFromAContactSwitchesToTheChatDestination() {
        let navigator = AppNavigator()
        navigator.openChat("abcd")
        XCTAssertEqual(navigator.chatsPath, [.chat("abcd")])
        XCTAssertEqual(navigator.destinationRequest, .chats)
    }

    func testTheStringsOfTheChatAndAnnouncementScreensAreTranslated() {
        let keys = [
            "mesh_off_body", "chats_empty_title", "chats_empty_body", "announcements_empty_title", "announcements_empty_body",
            "msg_unreadable", "status_published", "status_delivered", "bytes_over", "unread_divider", "loading_older",
            "sev_important", "sev_emergency", "chats_new", "chat_details", "chat_empty", "chat_send", "chat_no_card",
            "chat_blocked", "send_err_empty", "send_err_mesh_off", "send_err_unknown_contact", "send_err_rejected",
            "send_err_failed", "newchat_none", "ann_write", "ann_compose_title", "ann_public_note", "ann_severity_title",
            "ann_lifetime_title", "ann_lifetime_hint", "lifetime_custom", "ann_publish", "ann_confirm_body", "unit_seconds",
            "unit_minutes", "unit_hours", "unit_days", "a11y_message_outgoing", "a11y_message_incoming",
        ]
        for key in keys {
            let english = L10n.string(key, language: .english)
            let turkish = L10n.string(key, language: .turkish)
            XCTAssertNotEqual(english, key, "\(key) is missing in English")
            XCTAssertNotEqual(turkish, key, "\(key) is missing in Turkish")
            if key != "sev_normal" { XCTAssertNotEqual(english, turkish, "\(key) is the same in both languages") }
        }
    }

    func testStringsWithArgumentsAndPluralsAreFilledIn() {
        XCTAssertEqual(L10n.string("bytes_counter %lld %lld", language: .english, 12, 512), "12 of 512 bytes")
        XCTAssertEqual(L10n.string("bytes_counter %lld %lld", language: .turkish, 12, 512), "512 bayttan 12")
        XCTAssertEqual(L10n.string("chat_preview_mine %@", language: .english, "hi"), "You: hi")
        XCTAssertEqual(L10n.string("ann_confirm_title %@", language: .english, "Emergency"), "Publish as “Emergency”?")
        XCTAssertEqual(L10n.string("duration_minutes %lld", language: .english, 1), "1 minute")
        XCTAssertEqual(L10n.string("duration_minutes %lld", language: .english, 15), "15 minutes")
        XCTAssertEqual(L10n.string("duration_days %lld", language: .turkish, 3), "3 gün")
    }
}
