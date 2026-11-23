import Combine
import CrisisCore
import CrisisRuntime
import Foundation

/// What the chat and announcement screens need from a running node. ``NodeRuntime`` is the real one; the tests use a
/// node of their own, without a transport.
protocol MessagingBackend: Sendable {
    func conversations(limit: UInt32) async throws -> [ConversationInfo]
    func conversationHistory(conversation: ConversationRef, beforeSeq: UInt64?, limit: UInt32) async throws -> ConversationPageInfo
    func markConversationRead(conversation: ConversationRef, upToSeq: UInt64?) async throws -> UInt64
    func unreadCounts() async throws -> UnreadInfo
    func publishDirect(to: Data, plaintext: Data) async throws -> PublishOutcome
    func publishBroadcast(payload: Data, lifetimeSecs: UInt32?) async throws -> PublishOutcome
}

extension NodeRuntime: MessagingBackend {}

/// What the core accepts, as the screens need it.
struct Limits: Equatable, Sendable {
    /// Largest payload of a message in bytes.
    var maxPayloadBytes: Int

    /// Longest lifetime of a message in seconds.
    var maxLifetimeSecs: Int64

    static func fromCore() -> Limits {
        let info = coreInfo()
        return Limits(maxPayloadBytes: Int(info.maxPayloadBytes), maxLifetimeSecs: Int64(info.maxLifetimeSecs))
    }

    /// The most bytes of a private message's text: the sealed box adds a key and a tag.
    var directLimit: Int { Int(directByteLimit(maxPayloadBytes: UInt32(clamping: max(maxPayloadBytes, 0)))) }

    func announcementLimit(_ severity: AnnouncementSeverity) -> Int {
        Int(announcementByteLimit(maxPayloadBytes: UInt32(clamping: max(maxPayloadBytes, 0)), severity: severity))
    }
}

/// Runs a read, merges the calls that arrive meanwhile into one more read, and lets every caller wait until the data it
/// asked for is read.
@MainActor
final class RefreshCoalescer {
    private var task: Task<Void, Never>?
    private var again = false

    func run(_ work: @escaping @MainActor () async -> Void) async {
        if let running = task {
            again = true
            await running.value
            return
        }
        let new = Task { @MainActor [weak self] in
            guard let self else { return }
            repeat {
                self.again = false
                await work()
            } while self.again
            self.task = nil
        }
        task = new
        await new.value
    }
}

/// Everything the chat and announcement screens read and do (phase I6), in one holder for the whole app, like
/// ``ContactsModel``. The same job and the same rules as the Android `Messaging` object:
///
/// * The **messages** are the core's. This holder keeps only what is on screen: the list of chats, the unread counts,
///   the open chat and the announcements feed. They are read again when the node starts or stops and whenever its data
///   changes (a message arrived, was published or delivered).
/// * An open conversation is a **window** over the newest messages that grows backwards one page at a time
///   (``loadOlderChat()``, ``loadOlderFeed()``). A refresh reads the whole window again, so that the delivery marks of
///   older messages stay right.
/// * While a conversation is open, new incoming messages are **marked read** at once.
/// * The node is only open while the mesh runs, so messages can only be read and sent then (`ready`).
@MainActor
final class MessagingModel: ObservableObject {
    static let shared = MessagingModel(
        backend: { BleLinkTestModel.shared.activeRuntime },
        badge: { AppBadge.set($0) },
        ownNodeId: { BleLinkTestModel.shared.activeRuntime?.nodeId }
    )

    /// How many conversations the list holds at most.
    static let chatLimit: UInt32 = 500

    /// `true` while the node is open: messages can be read and sent.
    @Published private(set) var ready = false
    @Published private(set) var limits: Limits
    @Published private(set) var chats: [ChatRow] = []
    @Published private(set) var unread = Unread()
    @Published private(set) var chat = ChatState()
    @Published private(set) var feed = FeedState()

    private let backendProvider: @MainActor () -> (any MessagingBackend)?
    private let coalescer = RefreshCoalescer()
    private var observation: AnyCancellable?
    private var chatWindow: ConversationWindow?
    private var feedWindow: ConversationWindow?

    // Opening and closing a screen changes an epoch, so that a read that was running meanwhile does not publish into a
    // screen that is gone (or into another one that took its place).
    private var chatEpoch = 0
    private var feedEpoch = 0
    private var loadingOlderChat = false
    private var loadingOlderFeed = false

    /// Told the total of unread messages whenever it is read (the badge of the app icon).
    private let badge: @MainActor (Int) -> Void

    /// This device's node id: the card inside an introduction that this device sent is its own.
    private let ownNodeId: @MainActor () -> Data?

    init(
        backend: @escaping @MainActor () -> (any MessagingBackend)?,
        limits: Limits = .fromCore(),
        badge: @escaping @MainActor (Int) -> Void = { _ in },
        ownNodeId: @escaping @MainActor () -> Data? = { nil }
    ) {
        backendProvider = backend
        self.limits = limits
        self.badge = badge
        self.ownNodeId = ownNodeId
    }

    /// `true` for an introduction (see ``Introduction``): a protocol message that carries a contact card and is never
    /// shown as a message. For a received one the card must belong to the sender, for a sent one to this device.
    func isIntroduction(_ item: HistoryItem) -> Bool {
        guard item.kind == .direct, let body = item.body, Introduction.looksLike(body) else { return false }
        let owner = item.direction == .outgoing ? ownNodeId() : item.counterpart
        guard let owner else { return false }
        return Introduction.parse(body, owner: owner) != nil
    }

    // MARK: Observing the node

    /// Reads again whenever `changes` fires (a burst of changes becomes one read). Called once, at launch.
    func observe(_ changes: AnyPublisher<Void, Never>) {
        observation = changes
            .debounce(for: .milliseconds(80), scheduler: DispatchQueue.main)
            .sink { [weak self] in
                guard let self else { return }
                Task { await self.refresh() }
            }
    }

    /// Reads again whenever the node of `mesh` starts, stops or its data changes.
    func observe(_ mesh: BleLinkTestModel) {
        observe(
            mesh.$running.map { _ in () }
                .merge(with: mesh.$dataVersion.map { _ in () })
                .eraseToAnyPublisher()
        )
    }

    // MARK: Opening and closing screens

    /// The chat screen with `peerHex` appeared: load it and keep it up to date and read.
    func openChat(_ peerHex: String) {
        guard let peer = Hex.decode(peerHex) else { return }
        chatEpoch += 1
        chatWindow = ConversationWindow(ref: .direct(peer: peer), peerHex: peerHex)
        chat = ChatState(peerHex: peerHex)
        Task { await refresh() }
    }

    /// The chat screen disappeared. With `peerHex` only the chat of that person is closed: another one may have been
    /// opened meanwhile (from another tab).
    func closeChat(_ peerHex: String? = nil) {
        if let peerHex, chatWindow?.peerHex != peerHex { return }
        chatEpoch += 1
        chatWindow = nil
        chat = ChatState()
    }

    /// The announcements screen appeared.
    func openFeed() {
        feedEpoch += 1
        feedWindow = ConversationWindow(ref: .announcements)
        feed = FeedState()
        Task { await refresh() }
    }

    func closeFeed() {
        feedEpoch += 1
        feedWindow = nil
        feed = FeedState()
    }

    // MARK: Reading

    /// Reads the chat list, the open conversations and the unread counts. Without a running node what is on screen is
    /// left as it was and `ready` is `false`. Returns when the data is current.
    func refresh() async {
        await coalescer.run { [weak self] in await self?.readOnce() }
    }

    private func readOnce() async {
        guard let backend = backendProvider() else {
            ready = false
            return
        }
        ready = true
        do {
            if var window = chatWindow {
                let epoch = chatEpoch
                try await reload(&window, backend)
                if epoch == chatEpoch {
                    chatWindow = window
                    chat = chatState(of: window)
                }
            }
            if var window = feedWindow {
                let epoch = feedEpoch
                try await reload(&window, backend)
                if epoch == feedEpoch {
                    feedWindow = window
                    feed = feedState(of: window)
                }
            }
            // After the windows: reading them may have changed the counters.
            var infos = try await backend.conversations(limit: Self.chatLimit)
            // An introduction is not a message: do not leave it counted as unread (only when nothing else is unread there).
            var markedIntroduction = false
            for info in infos {
                guard info.unread == 1, case .direct = info.conversation, let last = info.last, isIntroduction(last) else { continue }
                _ = try await backend.markConversationRead(conversation: info.conversation, upToSeq: nil)
                markedIntroduction = true
            }
            if markedIntroduction { infos = try await backend.conversations(limit: Self.chatLimit) }
            // A conversation whose newest message is an introduction has nothing to show yet.
            chats = infos.compactMap { info -> ChatRow? in
                if let last = info.last, isIntroduction(last) { return nil }
                return MessageBook.chatRow(from: info)
            }
            let counts = try await backend.unreadCounts()
            unread = Unread(
                chats: MessageBook.saturatedInt(counts.direct),
                announcements: MessageBook.saturatedInt(counts.announcements)
            )
            badge(unread.chats + unread.announcements)
        } catch {
            // A failed read leaves the previous state on screen; the next change tries again.
        }
    }

    /// Reads the window over the newest messages again and marks what is new as read.
    private func reload(_ window: inout ConversationWindow, _ backend: any MessagingBackend) async throws {
        let page = try await backend.conversationHistory(
            conversation: window.ref,
            beforeSeq: nil,
            limit: UInt32(window.refreshSize)
        )
        window.replace(with: page, hiding: { isIntroduction($0) })
        if window.hasUnread {
            let marker = try await backend.markConversationRead(conversation: window.ref, upToSeq: nil)
            window.didMarkRead(upTo: marker)
        }
    }

    private func chatState(of window: ConversationWindow) -> ChatState {
        ChatState(
            peerHex: window.peerHex,
            messages: window.entries.filter { !isIntroduction($0) }.map(MessageBook.row),
            hasOlder: window.olderBefore != nil,
            loaded: window.loaded,
            firstUnreadSeq: window.firstUnreadSeq
        )
    }

    private func feedState(of window: ConversationWindow) -> FeedState {
        FeedState(
            messages: window.entries.map(MessageBook.row).reversed(),
            hasOlder: window.olderBefore != nil,
            loaded: window.loaded,
            firstUnreadSeq: window.firstUnreadSeq
        )
    }

    // MARK: Paging backwards

    /// Loads the next older page of the open chat. Does nothing when there is none or one is loading.
    func loadOlderChat() async {
        guard !loadingOlderChat, let window = chatWindow, let before = window.olderBefore,
              let backend = backendProvider()
        else { return }
        loadingOlderChat = true
        defer { loadingOlderChat = false }
        let epoch = chatEpoch
        guard let page = try? await backend.conversationHistory(
            conversation: window.ref, beforeSeq: before, limit: UInt32(ConversationWindow.pageSize)
        ), epoch == chatEpoch, var current = chatWindow, current.olderBefore == before else { return }
        current.prepend(page)
        chatWindow = current
        chat = chatState(of: current)
    }

    /// Loads the next older page of the announcements feed.
    func loadOlderFeed() async {
        guard !loadingOlderFeed, let window = feedWindow, let before = window.olderBefore,
              let backend = backendProvider()
        else { return }
        loadingOlderFeed = true
        defer { loadingOlderFeed = false }
        let epoch = feedEpoch
        guard let page = try? await backend.conversationHistory(
            conversation: window.ref, beforeSeq: before, limit: UInt32(ConversationWindow.pageSize)
        ), epoch == feedEpoch, var current = feedWindow, current.olderBefore == before else { return }
        current.prepend(page)
        feedWindow = current
        feed = feedState(of: current)
    }

    // MARK: Sending

    /// Sends `text` to the contact `peerHex` as a private message.
    func sendDirect(peerHex: String, text: String) async -> SendResult {
        guard let peer = Hex.decode(peerHex) else { return .unknownContact }
        let clean = sanitizeText(text: text).trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return .empty }
        if clean.utf8.count > limits.directLimit { return .tooLong }
        return await publish { try await $0.publishDirect(to: peer, plaintext: Data(clean.utf8)) }
    }

    /// Publishes a public announcement that devices keep for `lifetimeSecs` seconds.
    func publishAnnouncement(text: String, severity: AnnouncementSeverity, lifetimeSecs: Int64) async -> SendResult {
        let clean = sanitizeText(text: text).trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return .empty }
        let payload = encodeAnnouncement(text: clean, severity: severity)
        if payload.count > limits.maxPayloadBytes { return .tooLong }
        guard lifetimeIsValid(secs: lifetimeSecs, maxSecs: limits.maxLifetimeSecs) else {
            return .failed(detail: "lifetime out of range")
        }
        return await publish { try await $0.publishBroadcast(payload: payload, lifetimeSecs: UInt32(clamping: lifetimeSecs)) }
    }

    private func publish(_ block: (any MessagingBackend) async throws -> PublishOutcome) async -> SendResult {
        guard let backend = backendProvider() else { return .meshOff }
        let result: SendResult
        do {
            let outcome = try await block(backend)
            let refused = outcome.output.notifications.contains { notification in
                if case .publishRejected = notification { return true }
                return false
            }
            result = refused ? .rejected : .sent
        } catch {
            switch CoreFailure(error) {
            case .unknownContact: result = .unknownContact
            case .runtimeStopped: result = .meshOff
            default: result = .failed(detail: "\(error)")
            }
        }
        await refresh()
        return result
    }
}
