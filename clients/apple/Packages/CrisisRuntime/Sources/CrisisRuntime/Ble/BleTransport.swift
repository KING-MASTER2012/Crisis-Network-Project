import Foundation

public enum BleTransportError: Error, Sendable, Equatable {
    /// ``BleTransport/start()`` was called twice, or after ``BleTransport/stop()``.
    case alreadyStarted
}

/// The Bluetooth Low Energy ``Transport``: the state machine between the core and the radio. It follows the BLE
/// link profile (`docs/src/protocol/ble-link.md`, profile 1.1 as the Android client speaks it) and is the Swift
/// counterpart of the Android `BleTransport`, reduced to what phase I2 needs: one connection at a time, a simple
/// back-off, the HELLO exchange and the removal of duplicate links. Power profiles, tie-break and state restoration
/// are phase I3.
///
/// * **One serial queue** owns all state. The ports (CoreBluetooth) are created with that queue and call back on it;
///   ``send(peer:bytes:)`` and ``disconnect(peer:)`` only enqueue and return.
/// * A link becomes visible to the core (``TransportEvent/peerSeen(peer:linkMtu:)``) once it is ready and its payload
///   size carries the smallest packet the core accepts; a link that does not get there is closed when the handshake
///   time is over.
/// * One send is in flight per link; the rest wait in a bounded queue. A platform that is busy is asked again when it
///   says it is ready (and at every tick); a send that does not complete within the send timeout closes the link.
/// * Apple shows no radio addresses and cannot put a manufacturer token in an advertisement, so a link is known by
///   the identifier CoreBluetooth gives, and an iPhone connects to every device it sees. Two devices that connected
///   to each other both ways notice it through the HELLO identifiers and keep one link.
public final class BleTransport: Transport, @unchecked Sendable {
    public let events: AsyncStream<TransportEvent>

    /// Diagnostics for the log and the test screen. Not replayed; the newest 512 are kept for a slow reader.
    public let diagnostics: AsyncStream<BleDiagnostic>

    /// The queue that owns the state (and the ports). For tests.
    let queue: DispatchQueue

    /// Called with every event handed to the core, on the queue. For tests.
    var eventObserver: ((TransportEvent) -> Void)?

    private let eventContinuation: AsyncStream<TransportEvent>.Continuation
    private let diagnosticContinuation: AsyncStream<BleDiagnostic>.Continuation
    private let config: BleConfig
    private let scanner: BleScanner?
    private let advertiser: BleAdvertiser?
    private let server: GattServerPort?
    private let connector: GattConnector?
    private let clockMs: @Sendable () -> Int64

    private var started = false
    private var stopped = false
    private var timer: DispatchSourceTimer?
    private var handles = HandleAllocator()
    private var sessions: [Int: Session] = [:]
    private var byHandle: [UInt64: Session] = [:]
    private var nextSessionId = 1
    private var clientAddresses: Set<String> = []
    private var candidates: [String: Candidate] = [:]
    private var cooldownUntil: [String: Int64] = [:]
    private var failureCounts: [String: Int] = [:]
    private let ownHello: UInt64

    private var centralState = BleRadioState.unknown
    private var peripheralState = BleRadioState.unknown
    private var scanning = false
    private var advertising = false
    private var serverReady = false
    private var connectAttempts: Int64 = 0
    private var connectFailures: Int64 = 0
    private var duplicateTotal: Int64 = 0

    private struct Candidate {
        var lastSeenMs: Int64
        var rssi: Int
    }

    private final class Session {
        let id: Int
        let link: GattLink
        var queue: SendQueue
        var handle: UInt64?
        var ready = false
        var active = false
        var payloadMtu = 0
        var reportedMtu = 0
        var ownHello: UInt64 = 0
        var peerHello: UInt64?
        var startedMs: Int64 = 0
        var activatedMs: Int64 = 0
        var sending = false
        var sendStartedMs: Int64?
        var busySinceMs: Int64?
        var consecutiveFailures = 0
        var metrics = BleLinkMetrics()

        init(id: Int, link: GattLink, queue: SendQueue) {
            self.id = id
            self.link = link
            self.queue = queue
        }
    }

    init(
        config: BleConfig,
        queue: DispatchQueue,
        scanner: BleScanner?,
        advertiser: BleAdvertiser?,
        server: GattServerPort?,
        connector: GattConnector?,
        clockMs: @escaping @Sendable () -> Int64,
        helloSource: @escaping @Sendable () -> UInt64
    ) {
        self.config = config
        self.queue = queue
        self.scanner = config.role.runsCentral ? scanner : nil
        self.connector = config.role.runsCentral ? connector : nil
        self.advertiser = config.role.runsPeripheral ? advertiser : nil
        self.server = config.role.runsPeripheral ? server : nil
        self.clockMs = clockMs
        ownHello = helloSource()
        (events, eventContinuation) = AsyncStream.makeStream(of: TransportEvent.self)
        (diagnostics, diagnosticContinuation) = AsyncStream.makeStream(of: BleDiagnostic.self, bufferingPolicy: .bufferingNewest(512))
    }

    /// Milliseconds on a clock that never goes backwards (it does not count the time the device sleeps, which is
    /// right for timeouts).
    static func monotonicMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    // MARK: - Transport

    public func start() throws {
        try queue.sync {
            guard !started, !stopped else { throw BleTransportError.alreadyStarted }
            started = true
            wirePorts()
            connector?.setHello(ownHello)
            server?.setHello(ownHello)
            if config.role.runsPeripheral {
                server?.open()
                advertiser?.start()
            }
            if config.role.runsCentral {
                scanner?.start()
            }
            startTimer()
        }
    }

    public func stop() {
        queue.sync {
            guard !stopped else { return }
            stopped = true
            timer?.cancel()
            timer = nil
            scanner?.stop()
            advertiser?.stop()
            server?.close()
            for session in Array(sessions.values) {
                closeSession(session, "the transport stopped", fault: false, cooldownMs: 0)
            }
            candidates.removeAll()
            eventContinuation.finish()
            diagnosticContinuation.finish()
        }
    }

    public func send(peer: UInt64, bytes: Data) {
        queue.async { [self] in
            guard !stopped, let session = byHandle[peer], session.active else { return }
            guard !bytes.isEmpty else { return }
            guard bytes.count <= session.payloadMtu else {
                dropped(session, .tooLarge)
                return
            }
            guard session.queue.offer(bytes) else {
                dropped(session, .queueFull)
                return
            }
            pump(session)
        }
    }

    public func disconnect(peer: UInt64) {
        queue.async { [self] in
            guard !stopped, let session = byHandle[peer] else { return }
            closeSession(session, "closed by the core", fault: false, cooldownMs: config.reconnectCooldownMs)
        }
    }

    // MARK: - Snapshots

    /// The links, usable or still being set up.
    public func linkInfos() -> [BleLinkInfo] {
        queue.sync {
            sessions.values.sorted { $0.id < $1.id }.map { session in
                BleLinkInfo(
                    id: session.id,
                    handle: session.handle,
                    role: session.link.role,
                    address: session.link.address,
                    active: session.active,
                    payloadMtu: session.payloadMtu,
                    queuedBytes: session.queue.bytes,
                    peerHello: session.peerHello,
                    metrics: session.metrics
                )
            }
        }
    }

    public func stats() -> BleStats {
        queue.sync {
            let now = clockMs()
            return BleStats(
                role: config.role,
                centralState: centralState,
                peripheralState: peripheralState,
                scanning: scanning,
                advertising: advertising,
                serverReady: serverReady,
                candidates: candidates.count,
                backedOff: cooldownUntil.values.filter { $0 > now }.count,
                connectAttempts: connectAttempts,
                connectFailures: connectFailures,
                duplicateLinks: duplicateTotal
            )
        }
    }

    /// Zeroes the counters of every link.
    public func resetLinkMetrics() {
        queue.sync {
            for session in sessions.values { session.metrics = BleLinkMetrics() }
        }
    }

    // MARK: - Wiring

    private func wirePorts() {
        scanner?.onScanEvent = { [weak self] event in self?.handleScan(event) }
        advertiser?.onAdvertiseEvent = { [weak self] event in self?.handleAdvertise(event) }
        server?.onServerEvent = { [weak self] event in self?.handleServer(event) }
        server?.onIncoming = { [weak self] link in self?.onIncoming(link) }
    }

    private func startTimer() {
        let source = DispatchSource.makeTimerSource(queue: queue)
        let interval = DispatchTimeInterval.milliseconds(Int(max(config.tickMs, 1)))
        source.schedule(deadline: .now() + interval, repeating: interval)
        source.setEventHandler { [weak self] in self?.housekeeping() }
        source.resume()
        timer = source
    }

    private func emit(_ event: TransportEvent) {
        eventObserver?(event)
        eventContinuation.yield(event)
    }

    private func report(_ diagnostic: BleDiagnostic) {
        diagnosticContinuation.yield(diagnostic)
    }

    // MARK: - Events of the ports

    private func handleScan(_ event: ScanEvent) {
        guard started, !stopped else { return }
        switch event {
        case .started:
            scanning = true
            report(.scanStarted)
        case let .radioState(state):
            centralState = state
            if state != .poweredOn { scanning = false }
            report(.centralState(state))
        case let .failed(code):
            scanning = false
            report(.scanFailed(code: code))
        case let .sighting(address, rssi):
            guard config.role.runsCentral, !clientAddresses.contains(address) else { return }
            let now = clockMs()
            candidates[address] = Candidate(lastSeenMs: now, rssi: rssi)
            connectIfPossible(now)
        }
    }

    private func handleAdvertise(_ event: AdvertiseEvent) {
        guard started, !stopped else { return }
        switch event {
        case .started:
            advertising = true
            report(.advertiseStarted)
        case let .radioState(state):
            peripheralState = state
            if state != .poweredOn {
                advertising = false
                serverReady = false
            }
            report(.peripheralState(state))
        case let .failed(code):
            advertising = false
            report(.advertiseFailed(code: code))
        }
    }

    private func handleServer(_ event: ServerEvent) {
        guard started, !stopped else { return }
        switch event {
        case .ready:
            serverReady = true
            report(.serverReady)
        case let .failed(code):
            serverReady = false
            report(.serverFailed(code: code))
        }
    }

    // MARK: - Connecting

    private func outgoingCount() -> Int {
        sessions.values.filter { $0.link.role == .client }.count
    }

    private func connectingCount() -> Int {
        sessions.values.filter { $0.link.role == .client && !$0.active }.count
    }

    /// One slot stays free for incoming connections when the limit is 3 or more.
    private func maxOutgoing() -> Int {
        config.maxLinks >= 3 ? config.maxLinks - 1 : config.maxLinks
    }

    private func pickCandidate(_ now: Int64) -> (address: String, candidate: Candidate)? {
        candidates
            .filter { entry in
                now - entry.value.lastSeenMs <= config.candidateTtlMs
                    && (cooldownUntil[entry.key] ?? 0) <= now
                    && !clientAddresses.contains(entry.key)
            }
            .max { $0.value.rssi < $1.value.rssi }
            .map { (address: $0.key, candidate: $0.value) }
    }

    private func connectIfPossible(_ now: Int64) {
        guard started, !stopped, config.role.runsCentral, let connector else { return }
        while connectingCount() < config.maxConcurrentConnects,
              sessions.count < config.maxLinks,
              outgoingCount() < maxOutgoing()
        {
            guard let pick = pickCandidate(now) else { return }
            candidates[pick.address] = nil
            connectAttempts += 1
            guard let link = connector.connect(address: pick.address) else {
                connectFailures += 1
                report(.connectFailed(address: pick.address, reason: "the device is unknown or Bluetooth is not on"))
                recordFailure(address: pick.address, now: now)
                continue
            }
            register(link, now: now)
        }
    }

    private func onIncoming(_ link: GattLink) {
        guard started, !stopped else {
            link.close()
            return
        }
        guard sessions.count < config.maxLinks else {
            report(.linkRefused(address: link.address, role: link.role))
            link.close()
            return
        }
        register(link, now: clockMs())
    }

    private func register(_ link: GattLink, now: Int64) {
        let id = nextSessionId
        nextSessionId += 1
        let session = Session(id: id, link: link, queue: SendQueue(maxBytes: config.maxQueuedBytes))
        session.ownHello = ownHello
        session.startedMs = now
        sessions[id] = session
        if link.role == .client { clientAddresses.insert(link.address) }
        link.onEvent = { [weak self] event in self?.onLinkEvent(id, event) }
    }

    // MARK: - Events of one link

    private func onLinkEvent(_ id: Int, _ event: GattLinkEvent) {
        guard let session = sessions[id] else { return }
        switch event {
        case let .ready(payloadMtu):
            session.ready = true
            session.payloadMtu = min(max(payloadMtu, 0), BleProfile.maxPayload)
            tryActivate(session)
        case let .peerHello(peerId):
            onPeerHello(session, peerId)
        case let .mtuChanged(payloadMtu):
            onMtuChanged(session, min(max(payloadMtu, 0), BleProfile.maxPayload))
        case let .received(bytes):
            onReceived(session, bytes)
        case let .sendCompleted(success):
            onSendCompleted(session, success: success)
        case .readyToSend:
            pump(session)
        case let .closed(reason):
            if !session.active, session.link.role == .client {
                connectFailures += 1
                report(.connectFailed(address: session.link.address, reason: "\(reason)"))
            }
            closeSession(session, "closed by the platform (\(reason))", fault: !session.active, closeLink: false)
        }
    }

    /// The link becomes visible to the core once it is ready and its payload size carries the smallest packet.
    private func tryActivate(_ session: Session) {
        guard !session.active, session.ready, session.payloadMtu >= config.minLinkMtu else { return }
        let now = clockMs()
        let handle = handles.next()
        session.handle = handle
        session.active = true
        session.activatedMs = now
        session.reportedMtu = session.payloadMtu
        byHandle[handle] = session
        report(.linkUp(address: session.link.address, role: session.link.role, handle: handle, payloadMtu: session.payloadMtu))
        emit(.peerSeen(peer: handle, linkMtu: session.payloadMtu))
        pump(session)
    }

    private func onMtuChanged(_ session: Session, _ payload: Int) {
        session.payloadMtu = payload
        if session.active {
            // Only a larger value is reported again: packets are checked against the current size anyway.
            if payload > session.reportedMtu, let handle = session.handle {
                session.reportedMtu = payload
                emit(.peerSeen(peer: handle, linkMtu: payload))
            }
        } else {
            tryActivate(session)
        }
    }

    private func onReceived(_ session: Session, _ bytes: Data) {
        guard session.active, let handle = session.handle, !bytes.isEmpty else { return }
        let now = clockMs()
        session.metrics.packetsReceived += 1
        session.metrics.bytesReceived += Int64(bytes.count)
        if session.metrics.firstReceiveMs == nil { session.metrics.firstReceiveMs = now }
        session.metrics.lastReceiveMs = now
        emit(.packetReceived(peer: handle, bytes: bytes))
    }

    // MARK: - Duplicate links (profile 1.1)

    /// Both ends of a link know each other's identifier. Two links that lead to the same neighbour (we connected to it
    /// and it connected to us) are one too many: the link whose client has the lower identifier stays. Both devices
    /// reach the same verdict from the same two numbers. A device that cannot drop a central (an iPhone as the
    /// peripheral) silences its end of the extra link; the other side, which sees the same link as a client it
    /// opened, closes it for real.
    private func onPeerHello(_ session: Session, _ id: UInt64) {
        guard session.peerHello == nil else { return }
        session.peerHello = id
        let twins = sessions.values.filter { $0.peerHello == id }
        guard twins.count >= 2, let keep = twins.min(by: { compareLinks($0, $1) }) else { return }
        for twin in twins where twin !== keep {
            // The same client opened both: it closes the extra one itself, and the server side must not close the
            // other link by mistake.
            if twin.link.role == .server, clientHello(twin) == clientHello(keep) { continue }
            closeDuplicate(twin)
        }
    }

    private func clientHello(_ session: Session) -> UInt64 {
        session.link.role == .client ? session.ownHello : (session.peerHello ?? 0)
    }

    /// `true` when `a` is the link to keep rather than `b`.
    private func compareLinks(_ a: Session, _ b: Session) -> Bool {
        let clientA = clientHello(a)
        let clientB = clientHello(b)
        if clientA != clientB { return clientA < clientB }
        return a.id < b.id
    }

    private func closeDuplicate(_ session: Session) {
        duplicateTotal += 1
        report(.duplicateLink(address: session.link.address, role: session.link.role))
        // Without a long pause the next scan would open the same link again, and it would be closed again.
        closeSession(session, "duplicate link to a neighbour that is already connected", fault: false, cooldownMs: config.duplicateCooldownMs)
    }

    // MARK: - Sending

    private func dropped(_ session: Session, _ reason: DropReason) {
        session.metrics.packetsDropped += 1
        if let handle = session.handle { report(.packetDropped(handle: handle, reason: reason)) }
    }

    /// Starts the next packet when none is in flight.
    private func pump(_ session: Session) {
        while session.active, !session.sending, let head = session.queue.peek() {
            if head.count > session.payloadMtu {
                session.queue.removeFirst()
                dropped(session, .tooLarge)
                continue
            }
            let now = clockMs()
            switch session.link.send(head) {
            case .accepted:
                session.sending = true
                session.sendStartedMs = now
                session.busySinceMs = nil
                return
            case .busy:
                if session.busySinceMs == nil { session.busySinceMs = now }
                return
            case .failed:
                session.queue.removeFirst()
                dropped(session, .sendFailed)
                session.consecutiveFailures += 1
                if session.consecutiveFailures >= config.maxConsecutiveSendFailures {
                    closeSession(session, "sends keep failing", fault: true)
                    return
                }
            }
        }
    }

    private func onSendCompleted(_ session: Session, success: Bool) {
        guard session.sending else { return }
        session.sending = false
        session.sendStartedMs = nil
        let head = session.queue.removeFirst()
        if success {
            session.consecutiveFailures = 0
            session.metrics.packetsSent += 1
            session.metrics.bytesSent += Int64(head?.count ?? 0)
        } else {
            dropped(session, .sendFailed)
            session.consecutiveFailures += 1
            if session.consecutiveFailures >= config.maxConsecutiveSendFailures {
                closeSession(session, "sends keep failing", fault: true)
                return
            }
        }
        pump(session)
    }

    // MARK: - Closing

    private func recordFailure(address: String, now: Int64) {
        let failures = (failureCounts[address] ?? 0) + 1
        failureCounts[address] = failures
        let shift = min(failures - 1, 10)
        let delay = min(config.reconnectCooldownMs << Int64(shift), config.maxBackoffMs)
        cooldownUntil[address] = now + delay
        report(.connectBackoff(address: address, delayMs: delay, failures: failures))
    }

    /// Removes a session, tells the core when it knew it, and decides how soon its address may be tried again.
    /// `cooldownMs` overrides the back-off.
    private func closeSession(_ session: Session, _ why: String, fault: Bool, closeLink: Bool = true, cooldownMs: Int64? = nil) {
        guard sessions.removeValue(forKey: session.id) != nil else { return }
        let now = clockMs()
        session.link.onEvent = nil
        if closeLink { session.link.close() }
        session.queue.clear()
        let role = session.link.role
        let address = session.link.address
        if role == .client { clientAddresses.remove(address) }
        if let handle = session.handle {
            byHandle[handle] = nil
            if session.active { emit(.peerLost(peer: handle)) }
        }
        report(.linkDown(address: address, role: role, handle: session.handle, reason: why))
        if role == .client {
            if let cooldownMs {
                if cooldownMs > 0 { cooldownUntil[address] = now + cooldownMs }
            } else if session.active, !fault, now - session.activatedMs >= config.stableLinkMs {
                failureCounts[address] = nil
                cooldownUntil[address] = now + config.reconnectCooldownMs
            } else {
                recordFailure(address: address, now: now)
            }
        }
        connectIfPossible(now)
    }

    // MARK: - Housekeeping

    /// Timeouts, the polling of payload sizes and new connections. Runs on the queue every ``BleConfig/tickMs``.
    func housekeeping() {
        guard started, !stopped else { return }
        let now = clockMs()
        for session in Array(sessions.values) {
            guard sessions[session.id] != nil else { continue }
            if !session.active {
                if now - session.startedMs >= config.handshakeTimeoutMs {
                    if session.ready, session.payloadMtu < config.minLinkMtu {
                        report(.mtuTooSmall(address: session.link.address, payloadMtu: session.payloadMtu))
                    } else {
                        report(.handshakeTimeout(address: session.link.address, role: session.link.role, payloadMtu: session.payloadMtu))
                    }
                    if session.link.role == .client { connectFailures += 1 }
                    closeSession(session, "the link was not usable in time", fault: true)
                    continue
                }
            } else {
                if let sentAt = session.sendStartedMs, now - sentAt >= config.sendTimeoutMs {
                    closeSession(session, "a send did not complete in time", fault: true)
                    continue
                }
                if let busyAt = session.busySinceMs, now - busyAt >= config.sendTimeoutMs {
                    closeSession(session, "the platform stayed busy", fault: true)
                    continue
                }
            }
            if session.ready {
                session.link.pollMtu()
                guard sessions[session.id] != nil else { continue }
            }
            if session.active { pump(session) }
        }
        candidates = candidates.filter { now - $0.value.lastSeenMs <= config.candidateTtlMs }
        cooldownUntil = cooldownUntil.filter { $0.value > now }
        connectIfPossible(now)
    }
}
