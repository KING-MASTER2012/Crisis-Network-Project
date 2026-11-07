import CrisisCore
import Foundation

/// Lifecycle of a ``NodeRuntime``.
public enum RuntimeState: Sendable, Equatable {
    case created
    case running
    case stopped

    /// The node hit a fatal failure and shut itself down.
    case failed(CoreFailure)
}

/// Runs one `CrisisNode` as an actor.
///
/// * **One actor owns the node.** Transport events, publish calls, queries and timer ticks all run one after another
///   on the actor, so the core never sees concurrent calls. Every core call is synchronous and short (SQLite);
///   a call that arrives before ``start()`` runs first.
/// * **The core is driven by its own deadline.** Each `CoreOutput` carries `nextTimerMs`; the runtime ticks when it
///   is reached (see ``TimerPolicy``). Timing uses the clock, the only time source the core ever sees.
/// * **Outputs are routed**: transport commands go to the ``Transport``, notifications to ``notifications()``. The
///   transport is told to send without blocking.
/// * **Errors are mapped** to ``CoreFailure``. A non-fatal one fails the single call that caused it (or appears on
///   ``failures()`` for events); a fatal one stops the runtime (``RuntimeState/failed(_:)``).
///
/// iOS does not run timers while the app is suspended: in the background the runtime ticks when the Bluetooth stack
/// wakes the app (phase I3), not by itself.
///
/// Create it through ``NodeBootstrap``, call ``start()``, and always call ``stop()``.
public actor NodeRuntime {
    private var node: CrisisNode?
    private let transport: any Transport
    private let clock: CoreClock
    private var timer = TimerPolicy()
    private var timerTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?
    private var stateValue: RuntimeState = .created
    private var transportStarted = false
    private var released = false
    private var notificationSubscribers: [UUID: AsyncStream<CoreNotification>.Continuation] = [:]
    private var failureSubscribers: [UUID: AsyncStream<CoreFailure>.Continuation] = [:]
    private var stateSubscribers: [UUID: AsyncStream<RuntimeState>.Continuation] = [:]

    /// This node's address (32 bytes).
    public nonisolated let nodeId: Data
    public nonisolated let nodeIdHex: String

    /// This node's shareable contact card (128 bytes).
    public nonisolated let contactCard: Data
    public nonisolated let safetyNumber: String

    public init(node: CrisisNode, transport: any Transport, clock: CoreClock) {
        self.node = node
        self.transport = transport
        self.clock = clock
        nodeId = node.nodeId()
        nodeIdHex = node.nodeIdHex()
        contactCard = node.contactCard()
        safetyNumber = node.safetyNumber()
    }

    // MARK: - Lifecycle

    public var state: RuntimeState { stateValue }

    /// Starts the transport and the timer.
    public func start() throws {
        guard stateValue == .created, node != nil else {
            throw CoreFailure.invalidArgument(detail: "the runtime was already started")
        }
        let events = transport.events
        eventTask = Task { [weak self] in
            for await event in events {
                guard let self else { break }
                await self.handle(event)
            }
        }
        do {
            try transport.start()
            transportStarted = true
        } catch {
            let failure = CoreFailure.unexpected(description: "the transport did not start: \(error)")
            finish(.failed(failure))
            throw failure
        }
        setState(.running)
        tick()
    }

    /// Stops the transport, closes the node and persists the clock. Safe to call more than once and in any state.
    public func stop() {
        finish(.stopped)
    }

    /// Things the user interface should show. Not replayed: read the history for what happened earlier.
    public func notifications() -> AsyncStream<CoreNotification> {
        let (stream, continuation) = AsyncStream.makeStream(of: CoreNotification.self, bufferingPolicy: .bufferingNewest(256))
        if released {
            continuation.finish()
            return stream
        }
        let id = UUID()
        notificationSubscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.dropNotificationSubscriber(id) }
        }
        return stream
    }

    /// Failures of transport events and of the runtime itself (failures of a call are thrown to its caller).
    public func failures() -> AsyncStream<CoreFailure> {
        let (stream, continuation) = AsyncStream.makeStream(of: CoreFailure.self, bufferingPolicy: .bufferingNewest(16))
        if released {
            continuation.finish()
            return stream
        }
        let id = UUID()
        failureSubscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.dropFailureSubscriber(id) }
        }
        return stream
    }

    /// The current state first, then every change. The stream ends when the runtime is released.
    public func states() -> AsyncStream<RuntimeState> {
        let (stream, continuation) = AsyncStream.makeStream(of: RuntimeState.self, bufferingPolicy: .bufferingNewest(8))
        continuation.yield(stateValue)
        if released {
            continuation.finish()
            return stream
        }
        let id = UUID()
        stateSubscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.dropStateSubscriber(id) }
        }
        return stream
    }

    // MARK: - Publishing

    /// Publishes a public announcement. `lifetimeSecs` `nil` uses the default lifetime.
    public func publishBroadcast(payload: Data, lifetimeSecs: UInt32? = nil) throws -> PublishOutcome {
        try execute { node, now in
            let outcome: PublishOutcome
            if let lifetimeSecs {
                outcome = try node.publishBroadcastWithLifetime(nowMs: now, payload: payload, lifetimeSecs: lifetimeSecs)
            } else {
                outcome = try node.publishBroadcast(nowMs: now, payload: payload)
            }
            return (outcome, outcome.output)
        }
    }

    /// Publishes a message encrypted for the contact `to` (a node id); the contact must have been added.
    public func publishDirect(to: Data, plaintext: Data) throws -> PublishOutcome {
        try execute { node, now in
            let outcome = try node.publishDirect(nowMs: now, to: to, plaintext: plaintext)
            return (outcome, outcome.output)
        }
    }

    /// Publishes a message encrypted for the owner of `card` without saving the card.
    public func publishDirectToCard(card: Data, plaintext: Data) throws -> PublishOutcome {
        try execute { node, now in
            let outcome = try node.publishDirectToCard(nowMs: now, card: card, plaintext: plaintext)
            return (outcome, outcome.output)
        }
    }

    // MARK: - Contacts and reading

    public func addContact(card: Data) throws -> CardDetails {
        try query { node, now in try node.addContact(nowMs: now, card: card) }
    }

    public func contacts(limit: UInt32) throws -> [ContactInfo] {
        try query { node, _ in try node.contacts(limit: limit) }
    }

    public func setTrust(nodeId: Data, trust: TrustLevel) throws {
        try query { node, now in try node.setTrust(nowMs: now, nodeId: nodeId, trust: trust) }
    }

    public func history(afterSeq: UInt64, limit: UInt32) throws -> HistoryPageInfo {
        try query { node, now in try node.history(afterSeq: afterSeq, limit: limit, nowMs: now) }
    }

    /// The conversations that hold at least one live message, the newest first: the announcements (one
    /// conversation) and one per node with direct messages. Each has a preview and an unread count.
    public func conversations(limit: UInt32) throws -> [ConversationInfo] {
        try query { node, now in try node.conversations(limit: limit, nowMs: now) }
    }

    /// One page of a conversation, read backwards from its newest message: start with `beforeSeq` `nil`, then pass
    /// the page's `olderBeforeSeq` until it is `nil`. The entries of a page are oldest first.
    public func conversationHistory(conversation: ConversationRef, beforeSeq: UInt64?, limit: UInt32) throws -> ConversationPageInfo {
        try query { node, now in
            try node.conversationHistory(conversation: conversation, beforeSeq: beforeSeq, limit: limit, nowMs: now)
        }
    }

    /// Marks a conversation as read up to `upToSeq`, or up to its newest message when `nil`. The marker never moves
    /// backwards. Returns the marker as it stands afterwards.
    public func markConversationRead(conversation: ConversationRef, upToSeq: UInt64? = nil) throws -> UInt64 {
        try query { node, _ in try node.markConversationRead(conversation: conversation, upToSeq: upToSeq) }
    }

    /// Unread incoming messages over all conversations, for a badge.
    public func unreadCounts() throws -> UnreadInfo {
        try query { node, now in try node.unreadCounts(nowMs: now) }
    }

    public func connectedPeers() throws -> [UInt64] {
        try query { node, _ in try node.connectedPeers() }
    }

    public func stats() throws -> NodeStats {
        try query { node, _ in try node.stats() }
    }

    // MARK: - Running core calls

    private var isActive: Bool { stateValue == .running }

    private func query<T>(_ block: (CrisisNode, UInt64) throws -> T) throws -> T {
        try execute { node, now in (try block(node, now), nil) }
    }

    /// Runs one core call made for a caller and applies its output. A failure is thrown to the caller; a fatal one
    /// also stops the runtime.
    private func execute<T>(_ block: (CrisisNode, UInt64) throws -> (T, CoreOutput?)) throws -> T {
        guard let node, stateValue == .created || stateValue == .running else {
            throw CoreFailure.runtimeStopped
        }
        do {
            let (value, output) = try block(node, nowForCore())
            if let output {
                apply(output)
                scheduleTimer()
            }
            return value
        } catch {
            let failure = CoreFailure(error)
            if failure.isFatal {
                emitFailure(failure)
                finish(.failed(failure))
            }
            throw failure
        }
    }

    // MARK: - Events and the timer

    private func handle(_ event: TransportEvent) {
        switch event {
        case let .peerSeen(peer, linkMtu):
            step { node, now in try node.peerSeen(nowMs: now, peer: peer, linkMtu: UInt32(max(linkMtu, 0))) }
        case let .peerLost(peer):
            step { node, now in try node.peerLost(nowMs: now, peer: peer) }
        case let .packetReceived(peer, bytes):
            step { node, now in try node.packetReceived(nowMs: now, peer: peer, bytes: bytes) }
        }
    }

    private func tick() {
        timer.onTick(nowMs: clock.nowMs())
        step { node, now in try node.tick(nowMs: now) }
    }

    private func timerFired() {
        guard isActive else { return }
        tick()
    }

    /// Runs one core call made for an event, applies its output, and reports a failure instead of throwing.
    private func step(_ block: (CrisisNode, UInt64) throws -> CoreOutput) {
        defer { scheduleTimer() }
        guard let node, isActive else { return }
        do {
            apply(try block(node, nowForCore()))
        } catch {
            let failure = CoreFailure(error)
            emitFailure(failure)
            if failure.isFatal { finish(.failed(failure)) }
        }
    }

    private func apply(_ output: CoreOutput) {
        for command in output.transport {
            switch command {
            case let .sendPacket(peer, bytes):
                transport.send(peer: peer, bytes: bytes)
            case let .disconnect(peer, _):
                transport.disconnect(peer: peer)
            }
        }
        for notification in output.notifications {
            for subscriber in notificationSubscribers.values { subscriber.yield(notification) }
        }
        timer.onOutput(nextTimerMs: output.nextTimerMs.map { $0 > UInt64(Int64.max) ? Int64.max : Int64($0) })
    }

    /// Replaces the pending timer by one for the current deadline.
    private func scheduleTimer() {
        timerTask?.cancel()
        timerTask = nil
        guard isActive else { return }
        let waitMs = timer.waitMs(nowMs: clock.nowMs())
        timerTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
            } catch {
                return
            }
            await self?.timerFired()
        }
    }

    // MARK: - Shutting down

    /// Releases everything once; later calls do nothing, so a runtime that failed stays `failed` after `stop()`.
    private func finish(_ outcome: RuntimeState) {
        guard !released else { return }
        released = true
        timerTask?.cancel()
        timerTask = nil
        eventTask?.cancel()
        eventTask = nil
        if transportStarted { transport.stop() }
        node = nil // closes the database
        clock.flush()
        setState(outcome)
        for subscriber in notificationSubscribers.values { subscriber.finish() }
        for subscriber in failureSubscribers.values { subscriber.finish() }
        for subscriber in stateSubscribers.values { subscriber.finish() }
        notificationSubscribers.removeAll()
        failureSubscribers.removeAll()
        stateSubscribers.removeAll()
    }

    private func setState(_ newState: RuntimeState) {
        stateValue = newState
        for subscriber in stateSubscribers.values { subscriber.yield(newState) }
    }

    private func emitFailure(_ failure: CoreFailure) {
        for subscriber in failureSubscribers.values { subscriber.yield(failure) }
    }

    private func dropNotificationSubscriber(_ id: UUID) { notificationSubscribers[id] = nil }
    private func dropFailureSubscriber(_ id: UUID) { failureSubscribers[id] = nil }
    private func dropStateSubscriber(_ id: UUID) { stateSubscribers[id] = nil }

    private func nowForCore() -> UInt64 {
        UInt64(max(clock.nowMs(), 0))
    }
}
