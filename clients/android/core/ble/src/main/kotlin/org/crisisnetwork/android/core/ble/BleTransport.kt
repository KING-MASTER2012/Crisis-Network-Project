package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.crisisnetwork.android.core.runtime.transport.Transport
import org.crisisnetwork.android.core.runtime.transport.TransportEvent
import kotlin.math.max
import kotlin.math.min

/**
 * The BLE link layer of the core's [Transport] (phases A2 and A3).
 *
 * It turns GATT connections into the three things the core understands: `PeerSeen` (a neighbour is
 * usable, with the size of the packets its link carries), `PeerLost`, and `PacketReceived`; and it
 * turns the core's `send` and `disconnect` into per-link queues and GATT operations.
 *
 * * **One coroutine owns all state.** Callbacks from the Bluetooth stack (any thread), commands
 *   from the runtime and timers are messages in one mailbox and are handled one after another, so
 *   the state machine needs no locks and behaves the same in a JVM test with fakes.
 * * **A link is reported to the core only when it is usable**: services found, notifications on,
 *   and an MTU that carries at least [BleConfig.minLinkMtu]. Only then it gets a handle; handles
 *   are never reused (see [HandleAllocator]).
 * * **`send` never blocks.** A packet goes into the link's bounded [SendQueue]; one send is in
 *   flight per link and the next starts when the platform reports completion. A packet that is
 *   too large, or that does not fit the queue, is dropped (the mesh repairs losses).
 *
 * Discovery and topology (phase A3):
 *
 * * **Advertising and scanning run together.** The advertisement carries a rotating random token
 *   ([BleAdvertisement]); of two phones that see each other the one with the smaller token
 *   initiates ([TieBreak]), so they share one link. A device without a usable token is connected
 *   to as before; when we have no link at all for [BleConfig.tieBreakFallbackMs] we also connect
 *   to devices we would otherwise leave alone.
 * * **Connecting is paced.** At most [BleConfig.maxConcurrentConnects] attempts at a time, spaced by
 *   [BleConfig.connectSpacingMs]; the strongest candidate first. One slot of the link limit is kept
 *   free for incoming connections.
 * * **Failures back off.** An address that fails again and again waits twice as long each time
 *   (GATT status 133 and the like), up to [BleConfig.maxBackoffMs]; a device that lacks our service
 *   waits at least [BleConfig.setupFailedBackoffMs]; several failures in a row pause all new
 *   connections for a moment. A link that lived [BleConfig.stableLinkMs] starts clean.
 * * **Scan starts are rationed.** Android refuses more than 5 scan starts per 30 s: restarts (mode
 *   change, refresh, recovery) wait for their turn.
 * * **Links rotate only under pressure.** When the limit is reached and a neighbour is waiting,
 *   the oldest outgoing link is swapped for it at most once per
 *   [BleTuning.rotationIntervalMs].
 * * **Power profile and screen state** ([setPowerProfile], [setScreenOn]) change the scan and
 *   advertise modes, the link limit and the requested connection interval at run time.
 *
 * Packets are not framed: one core packet is exactly one GATT write (central to peripheral) or
 * one notification (peripheral to central). The core already fits every packet to the MTU
 * reported with `PeerSeen`, which is the ATT MTU minus 3 (capped at 512).
 *
 * @param clockMs a monotonic millisecond clock: throughput counters, back-off, timers.
 * @param tokenSource produces the advertised tokens (unsigned 32-bit values); replaced in tests.
 */
class BleTransport(
    private val scanner: BleScanner,
    private val advertiser: BleAdvertiser,
    private val connector: GattConnector,
    private val server: GattServerPort,
    private val config: BleConfig = BleConfig(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val tokenSource: () -> Long = TokenSources::secure,
) : Transport {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mailbox = Channel<Msg>(Channel.UNLIMITED)
    private val eventChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    private val diagnosticFlow =
        MutableSharedFlow<BleDiagnostic>(extraBufferCapacity = DIAGNOSTIC_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val events: Flow<TransportEvent> = eventChannel.receiveAsFlow()

    /** What went wrong or is worth knowing. Not replayed. */
    val diagnostics: SharedFlow<BleDiagnostic> = diagnosticFlow.asSharedFlow()

    private enum class Phase { Created, Running, Stopped }

    private var phase = Phase.Created // guarded by `this`

    @Volatile
    private var stopping = false

    private var feeders: List<Job> = emptyList() // guarded by `this`

    // ----- state owned by the actor -------------------------------------------------------

    private val sessions = LinkedHashMap<Int, Session>()
    private val byHandle = HashMap<ULong, Session>()
    private val clientAddresses = HashSet<String>()
    private val handles = HandleAllocator()
    private var nextSessionId = 1

    // Discovery and connection policy.
    private val candidates = LinkedHashMap<String, Candidate>()
    private val backoffUntil = HashMap<String, Long>()
    private val failures = HashMap<String, Failure>()
    private var connectPausedUntilMs = 0L
    private var nextConnectAllowedMs = 0L
    private var connectGateScheduled = false
    private var consecutiveConnectFailures = 0
    private var nextRotationMs = 0L

    // Power.
    private var profile = config.profile
    private var screenOn = true

    // Scanning.
    private var scanning = false
    private var scanMode: ScanMode? = null
    private var scanStartedMs = 0L
    private val scanStartTimes = ArrayDeque<Long>()
    private var scanBlockedUntilMs = 0L
    private var scanFailures = 0

    // Advertising and the token.
    private var advertising = false
    private var advertiseMode: AdvertiseMode? = null
    private var advertiseRetryAtMs = 0L
    private var advertiseFailures = 0
    private var ownToken = tokenSource()
    private var tokenAdvertised = true
    private var nextTokenRotationMs = 0L

    // Counters for BleStats.
    private var connectAttempts = 0L
    private var connectFailureTotal = 0L
    private var linkRotations = 0L
    private var tokenRotations = 0L
    private var scanStartTotal = 0L

    // ----- Transport ------------------------------------------------------------------------

    @Synchronized
    override fun start() {
        check(phase == Phase.Created) { "the BLE transport was already started" }
        phase = Phase.Running
        val role = config.role
        val jobs = mutableListOf<Job>()
        if (role.central) {
            jobs += scope.launch { scanner.events.collect { mailbox.trySend(Msg.Scan(it)) } }
        }
        if (role.peripheral) {
            jobs += scope.launch { advertiser.events.collect { mailbox.trySend(Msg.Advertise(it)) } }
            jobs += scope.launch { server.events.collect { mailbox.trySend(Msg.ServerState(it)) } }
            jobs += scope.launch { server.incoming.collect { mailbox.trySend(Msg.Incoming(it)) } }
        }
        jobs +=
            scope.launch {
                while (isActive) {
                    delay(config.tickMs)
                    mailbox.trySend(Msg.Tick)
                }
            }
        feeders = jobs
        try {
            val now = clockMs()
            nextTokenRotationMs = now + config.tokenRotationMs
            nextRotationMs = now + profile.tuning.rotationIntervalMs
            // The GATT service must exist before anybody can connect to the advertisement.
            if (role.peripheral) {
                server.open()
                val mode = profile.tuning.advertiseMode
                advertiser.start(mode, currentAdvertisement())
                advertiseMode = mode
            }
            if (role.central) {
                val mode = desiredScanMode()
                scanner.start(mode)
                scanning = true
                scanMode = mode
                scanStartedMs = now
                scanStartTimes.addLast(now)
                scanStartTotal += 1
            }
        } catch (t: Throwable) {
            // The runtime does not call stop() when start() fails: clean up here.
            phase = Phase.Stopped
            stopping = true
            jobs.forEach { it.cancel() }
            stopPorts()
            mailbox.close()
            eventChannel.close()
            scope.cancel()
            throw t
        }
        scope.launch { runActor() }
    }

    @Synchronized
    override fun stop() {
        if (phase == Phase.Stopped) return
        val wasRunning = phase == Phase.Running
        phase = Phase.Stopped
        stopping = true
        feeders.forEach { it.cancel() }
        stopPorts()
        mailbox.close() // the actor drains what is queued, closes the links and ends
        if (!wasRunning) {
            eventChannel.close()
            scope.cancel()
        }
    }

    override fun send(peer: ULong, bytes: ByteArray) {
        mailbox.trySend(Msg.Send(peer, bytes))
    }

    override fun disconnect(peer: ULong) {
        mailbox.trySend(Msg.Disconnect(peer))
    }

    // ----- power and diagnostics API ------------------------------------------------------------

    /** Switches the power profile; scan and advertise modes, link limit and link priority follow at once. */
    fun setPowerProfile(profile: PowerProfile) {
        mailbox.trySend(Msg.SetProfile(profile))
    }

    /** Tells the transport whether the screen is on: the scan mode and the link priority depend on it. */
    fun setScreenOn(on: Boolean) {
        mailbox.trySend(Msg.SetScreen(on))
    }

    /** A snapshot of every link, connecting or usable. Empty once the transport is stopped. */
    suspend fun linkInfo(): List<LinkInfo> {
        val reply = CompletableDeferred<List<LinkInfo>>()
        if (mailbox.trySend(Msg.Snapshot(reply)).isFailure) return emptyList()
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { reply.await() } ?: emptyList()
    }

    /** A snapshot of the discovery and power state, or `null` once the transport is stopped. */
    suspend fun stats(): BleStats? {
        val reply = CompletableDeferred<BleStats>()
        if (mailbox.trySend(Msg.StatsRequest(reply)).isFailure) return null
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { reply.await() }
    }

    /** Zeroes the throughput counters of all links (for a measurement run). */
    fun resetMetrics() {
        mailbox.trySend(Msg.ResetMetrics)
    }

    // ----- the actor ----------------------------------------------------------------------------

    private sealed interface Msg {
        class Scan(val event: ScanEvent) : Msg

        class Advertise(val event: AdvertiseEvent) : Msg

        class ServerState(val event: ServerEvent) : Msg

        class Incoming(val link: GattLink) : Msg

        class FromLink(val id: Int, val event: GattLinkEvent) : Msg

        class HandshakeTimeout(val id: Int) : Msg

        class SendTimeout(val id: Int, val token: Long) : Msg

        class RetryPump(val id: Int) : Msg

        class CooldownOver(val address: String) : Msg

        class Send(val handle: ULong, val bytes: ByteArray) : Msg

        class Disconnect(val handle: ULong) : Msg

        class Snapshot(val reply: CompletableDeferred<List<LinkInfo>>) : Msg

        class StatsRequest(val reply: CompletableDeferred<BleStats>) : Msg

        class SetProfile(val profile: PowerProfile) : Msg

        class SetScreen(val on: Boolean) : Msg

        data object Tick : Msg

        data object ConnectGate : Msg

        data object ResetMetrics : Msg
    }

    /** A device that was seen advertising and that we may connect to. */
    private class Candidate(
        val address: String,
        val advertisement: BleAdvertisement?,
        val rssi: Int,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
    )

    /** Consecutive failures of one address. */
    private class Failure(val count: Int, val lastMs: Long)

    private class Session(val id: Int, val link: GattLink, val queue: SendQueue) {
        var handle: ULong? = null
        var active = false
        var attReady = false
        var payloadMtu = 0
        var reportedMtu = 0
        var activatedMs = 0L

        // Why the platform ended a link that never became usable (for the back-off).
        var failReason: CloseReason? = null

        var inFlight = false
        var sendStartedMs = 0L
        var sendToken = 0L
        var attempts = 0
        var retryPending = false
        var consecutiveFailures = 0

        var collector: Job? = null
        var handshakeTimer: Job? = null
        var sendTimer: Job? = null
        var retryTimer: Job? = null

        var metrics = LinkMetrics()
    }

    private suspend fun runActor() {
        try {
            for (message in mailbox) {
                try {
                    handle(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    report(BleDiagnostic.InternalError("${t::class.simpleName}: ${t.message}"))
                }
            }
        } finally {
            for (session in sessions.values.toList()) closeSession(session, "transport stopped")
            eventChannel.close()
            scope.cancel()
        }
    }

    private fun handle(message: Msg) {
        when (message) {
            is Msg.Scan -> onScan(message.event)
            is Msg.Advertise -> onAdvertise(message.event)
            is Msg.ServerState ->
                report(
                    when (val e = message.event) {
                        ServerEvent.Ready -> BleDiagnostic.ServerReady
                        is ServerEvent.Failed -> BleDiagnostic.ServerFailed(e.status)
                    },
                )
            is Msg.Incoming -> onIncoming(message.link)
            is Msg.FromLink -> sessions[message.id]?.let { onLinkEvent(it, message.event) }
            is Msg.HandshakeTimeout -> sessions[message.id]?.let { onHandshakeTimeout(it) }
            is Msg.SendTimeout ->
                sessions[message.id]?.let {
                    if (it.inFlight && it.sendToken == message.token) closeSession(it, "send timed out")
                }
            is Msg.RetryPump ->
                sessions[message.id]?.let {
                    it.retryPending = false
                    pump(it)
                }
            is Msg.CooldownOver -> onCooldownOver(message.address)
            is Msg.Send -> onSend(message.handle, message.bytes)
            is Msg.Disconnect -> byHandle[message.handle]?.let { closeSession(it, "closed by the core", fault = false) }
            is Msg.Snapshot -> message.reply.complete(sessions.values.map { it.info() })
            is Msg.StatsRequest -> message.reply.complete(stats(clockMs()))
            is Msg.SetProfile -> onPower(newProfile = message.profile, newScreen = null)
            is Msg.SetScreen -> onPower(newProfile = null, newScreen = message.on)
            Msg.Tick -> onTick()
            Msg.ConnectGate -> connectGateScheduled = false
            Msg.ResetMetrics -> sessions.values.forEach { it.metrics = LinkMetrics() }
        }
        // Whatever just happened may have freed a slot, ended a back-off or opened the pause.
        maybeConnect()
    }

    // ----- scanning -----------------------------------------------------------------------------

    private fun desiredScanMode(): ScanMode = if (screenOn) profile.tuning.scanMode else profile.tuning.screenOffScanMode

    /**
     * Makes the scan match the wish ([desiredScanMode]) and starts it when it is not running, within
     * the platform's budget of scan starts. Cheap when nothing has to change; a start that is not
     * allowed yet is retried by the next tick.
     */
    private fun ensureScan(force: Boolean = false) {
        if (!config.role.central || stopping) return
        val mode = desiredScanMode()
        if (scanning && scanMode == mode && !force) return
        val now = clockMs()
        if (now < scanBlockedUntilMs) return
        while (scanStartTimes.isNotEmpty() && now - scanStartTimes.first() >= SCAN_WINDOW_MS) scanStartTimes.removeFirst()
        if (scanStartTimes.size >= config.maxScanStartsPer30s) {
            val wait = SCAN_WINDOW_MS - (now - scanStartTimes.first()) + SCAN_WINDOW_MARGIN_MS
            scanBlockedUntilMs = now + wait
            report(BleDiagnostic.ScanBlocked(wait))
            return
        }
        if (scanning) runCatching { scanner.stop() }
        try {
            scanner.start(mode)
            scanning = true
            scanMode = mode
            scanStartedMs = now
            scanStartTimes.addLast(now)
            scanStartTotal += 1
            report(BleDiagnostic.ScanStarted(mode))
        } catch (t: Throwable) {
            scanning = false
            scanFailures += 1
            scanBlockedUntilMs = now + scanBackoffMs()
            report(BleDiagnostic.ScanFailed(SCAN_START_EXCEPTION))
        }
    }

    private fun onScanFailed(code: Int) {
        report(BleDiagnostic.ScanFailed(code))
        if (code == SCAN_FAILED_ALREADY_STARTED) {
            scanning = true
            return
        }
        scanning = false
        scanFailures += 1
        val wait = if (code == SCAN_FAILED_TOO_FREQUENTLY) SCAN_WINDOW_MS else scanBackoffMs()
        scanBlockedUntilMs = clockMs() + wait
    }

    private fun scanBackoffMs(): Long {
        val doublings = min(max(scanFailures - 1, 0), MAX_DOUBLINGS)
        return min(config.scanFailureBackoffMs shl doublings, config.maxBackoffMs)
    }

    // ----- advertising and the token ---------------------------------------------------------------

    private fun currentAdvertisement(): BleAdvertisement? =
        if (tokenAdvertised) BleAdvertisement(ownToken, canInitiate = config.role.central) else null

    /** The token we advertise, or `null` when we advertise none (then the peers cannot decide, so we initiate). */
    private fun advertisedToken(): Long? = if (config.role.peripheral && tokenAdvertised) ownToken else null

    /** (Re)starts advertising with the current mode and token. A failure is retried by the tick. */
    private fun startAdvertising() {
        if (!config.role.peripheral || stopping) return
        val mode = profile.tuning.advertiseMode
        try {
            advertiser.start(mode, currentAdvertisement())
            advertiseMode = mode
        } catch (t: Throwable) {
            advertising = false
            advertiseFailures += 1
            advertiseRetryAtMs = clockMs() + advertiseBackoffMs()
            report(BleDiagnostic.AdvertiseFailed(ADVERTISE_START_EXCEPTION))
        }
    }

    private fun advertiseBackoffMs(): Long {
        val doublings = min(max(advertiseFailures - 1, 0), MAX_DOUBLINGS)
        return min(config.scanFailureBackoffMs shl doublings, config.maxBackoffMs)
    }

    private fun onAdvertise(event: AdvertiseEvent) {
        when (event) {
            AdvertiseEvent.Started -> {
                advertising = true
                advertiseFailures = 0
                report(BleDiagnostic.AdvertiseStarted)
            }
            is AdvertiseEvent.Failed -> {
                report(BleDiagnostic.AdvertiseFailed(event.code))
                when (event.code) {
                    ADVERTISE_ALREADY_STARTED -> advertising = true
                    ADVERTISE_FEATURE_UNSUPPORTED -> advertising = false
                    ADVERTISE_DATA_TOO_LARGE -> {
                        // Without the token the peers cannot tell who should initiate: we do.
                        advertising = false
                        if (tokenAdvertised) {
                            tokenAdvertised = false
                            startAdvertising()
                        }
                    }
                    else -> {
                        advertising = false
                        advertiseFailures += 1
                        advertiseRetryAtMs = clockMs() + advertiseBackoffMs()
                    }
                }
            }
        }
    }

    private fun rotateToken(now: Long) {
        ownToken = tokenSource()
        tokenRotations += 1
        nextTokenRotationMs = now + config.tokenRotationMs
        report(BleDiagnostic.TokenRotated)
        startAdvertising()
    }

    // ----- power ------------------------------------------------------------------------------------

    private fun onPower(newProfile: PowerProfile?, newScreen: Boolean?) {
        newProfile?.let { profile = it }
        newScreen?.let { screenOn = it }
        report(BleDiagnostic.PowerChanged(profile, screenOn))
        nextRotationMs = clockMs() + profile.tuning.rotationIntervalMs
        ensureScan()
        if (config.role.peripheral && advertiseMode != profile.tuning.advertiseMode) startAdvertising()
        val priority = linkPriority()
        for (session in sessions.values) {
            if (session.active && session.link.role == LinkRole.Client) runCatching { session.link.requestPriority(priority) }
        }
    }

    private fun linkPriority(): LinkPriority =
        if (screenOn) profile.tuning.linkPriority else profile.tuning.screenOffLinkPriority

    /** Links allowed right now: the smaller of the profile's limit and the configured ceiling. */
    private fun effectiveMaxLinks(): Int = min(profile.tuning.maxLinks, config.maxLinks)

    /** Outgoing links allowed: one slot stays free for incoming connections when the limit is 3 or more. */
    private fun maxOutgoing(): Int = effectiveMaxLinks().let { if (it >= MIN_LIMIT_FOR_RESERVE) it - 1 else it }

    // ----- the tick ------------------------------------------------------------------------------------

    private fun onTick() {
        val now = clockMs()
        candidates.values.removeAll { now - it.lastSeenMs > config.candidateTtlMs }
        backoffUntil.values.removeAll { it <= now }
        failures.values.removeAll { now - it.lastMs > FAILURE_MEMORY_MS }
        if (now >= nextTokenRotationMs) rotateToken(now)
        if (advertiseRetryAtMs != 0L && now >= advertiseRetryAtMs) {
            advertiseRetryAtMs = 0L
            startAdvertising()
        }
        if (config.role.central) {
            ensureScan()
            // Android demotes a scan that ran for 30 minutes: start a fresh one before that.
            if (scanning && now - scanStartedMs >= config.scanRefreshMs) ensureScan(force = true)
            rotateLinksIfNeeded(now)
        }
    }

    // ----- discovery and new links ------------------------------------------------------------

    private fun onScan(event: ScanEvent) {
        when (event) {
            is ScanEvent.Failed -> onScanFailed(event.code)
            is ScanEvent.Sighting -> {
                if (stopping || !config.role.central) return
                scanFailures = 0
                val address = event.address
                if (address in clientAddresses) return
                val now = clockMs()
                val firstSeen = candidates[address]?.firstSeenMs ?: now
                candidates[address] = Candidate(address, event.advertisement, event.rssi, firstSeen, now)
                if (candidates.size > MAX_CANDIDATES) {
                    candidates.values.minByOrNull { it.lastSeenMs }?.let { candidates.remove(it.address) }
                }
            }
        }
    }

    /** Whether we may open a connection to [candidate]: the tie-break, or the fallback when we are isolated. */
    private fun mayInitiate(candidate: Candidate, now: Long): Boolean {
        if (TieBreak.shouldInitiate(advertisedToken(), candidate.advertisement)) return true
        return sessions.isEmpty() && now - candidate.firstSeenMs >= config.tieBreakFallbackMs
    }

    /** The best candidate that is allowed right now (capacity is checked by the caller): the strongest signal. */
    private fun pickCandidate(now: Long): Candidate? =
        candidates.values
            .filter { it.address !in clientAddresses && now >= (backoffUntil[it.address] ?: 0L) && mayInitiate(it, now) }
            .maxWithOrNull(compareBy<Candidate>({ it.rssi }, { -it.firstSeenMs }))

    private fun clientCount(): Int = sessions.values.count { it.link.role == LinkRole.Client }

    private fun connectingCount(): Int = sessions.values.count { it.link.role == LinkRole.Client && !it.active }

    /** Opens connections to the waiting candidates as far as the limits, the pacing and the pause allow. */
    private fun maybeConnect() {
        if (stopping || !config.role.central || candidates.isEmpty()) return
        while (true) {
            val now = clockMs()
            if (now < connectPausedUntilMs) {
                scheduleConnectGate(connectPausedUntilMs - now)
                return
            }
            if (sessions.size >= effectiveMaxLinks() || clientCount() >= maxOutgoing()) return
            if (connectingCount() >= config.maxConcurrentConnects) return
            val pick = pickCandidate(now) ?: return
            if (now < nextConnectAllowedMs) {
                scheduleConnectGate(nextConnectAllowedMs - now)
                return
            }
            connectTo(pick)
        }
    }

    private fun scheduleConnectGate(delayMs: Long) {
        if (connectGateScheduled) return
        connectGateScheduled = true
        scope.launch {
            delay(max(delayMs, 1L))
            mailbox.trySend(Msg.ConnectGate)
        }
    }

    private fun connectTo(candidate: Candidate) {
        val address = candidate.address
        candidates.remove(address)
        connectAttempts += 1
        val link =
            try {
                connector.connect(address)
            } catch (t: Throwable) {
                report(BleDiagnostic.ConnectFailed(address, CloseReason.SetupFailed, null))
                recordFailure(address, CloseReason.SetupFailed)
                nextConnectAllowedMs = clockMs() + config.connectSpacingMs
                return
            }
        register(link)
    }

    private fun onIncoming(link: GattLink) {
        if (stopping) {
            runCatching { link.close() }
            return
        }
        if (sessions.size >= effectiveMaxLinks()) {
            report(BleDiagnostic.LinkRefused(link.address, link.role))
            runCatching { link.close() }
            return
        }
        register(link)
    }

    private fun register(link: GattLink) {
        val id = nextSessionId++
        val session = Session(id, link, SendQueue(config.maxQueuedBytes))
        sessions[id] = session
        if (link.role == LinkRole.Client) clientAddresses += link.address
        session.collector = scope.launch { link.events.collect { mailbox.trySend(Msg.FromLink(id, it)) } }
        session.handshakeTimer =
            scope.launch {
                delay(config.handshakeTimeoutMs)
                mailbox.trySend(Msg.HandshakeTimeout(id))
            }
    }

    // ----- link rotation ---------------------------------------------------------------------------------

    /**
     * When we cannot open another connection (the limit is reached) and a neighbour is waiting, the
     * oldest link that we opened ourselves is closed once per rotation interval, so that the
     * waiting neighbour gets its turn. Links that were opened by the other side are never dropped
     * from here: that is the other side's decision. With free capacity nothing is rotated.
     */
    private fun rotateLinksIfNeeded(now: Long) {
        if (now < nextRotationMs) return
        nextRotationMs = now + profile.tuning.rotationIntervalMs
        if (sessions.size < effectiveMaxLinks() && clientCount() < maxOutgoing()) return
        if (pickCandidate(now) == null) return
        val victim =
            sessions.values
                .filter { it.active && it.link.role == LinkRole.Client && now - it.activatedMs >= config.minLinkAgeForRotationMs }
                .minByOrNull { it.activatedMs }
                ?: return
        val address = victim.link.address
        linkRotations += 1
        report(BleDiagnostic.LinkRotated(address))
        closeSession(victim, "rotated to make room for a waiting neighbour", fault = false)
        startCooldown(address, config.rotationPauseMs)
    }

    // ----- events of one link -------------------------------------------------------------------

    private fun onLinkEvent(session: Session, event: GattLinkEvent) {
        when (event) {
            is GattLinkEvent.Ready -> {
                session.attReady = true
                session.payloadMtu = BleConfig.payloadForAttMtu(event.attMtu)
                tryActivate(session)
            }
            is GattLinkEvent.MtuChanged -> onMtuChanged(session, BleConfig.payloadForAttMtu(event.attMtu))
            is GattLinkEvent.Received -> onReceived(session, event.bytes)
            is GattLinkEvent.SendCompleted -> onSendCompleted(session, event.success)
            is GattLinkEvent.Closed -> {
                if (!session.active && session.link.role == LinkRole.Client) {
                    report(BleDiagnostic.ConnectFailed(session.link.address, event.reason, event.status))
                    session.failReason = event.reason
                }
                val status = event.status?.let { " status $it" }.orEmpty()
                closeSession(session, "closed by the platform (${event.reason}$status)", closeLink = false)
            }
        }
    }

    /** The link becomes visible to the core once it is ready and its MTU carries the smallest packet. */
    private fun tryActivate(session: Session) {
        if (session.active || !session.attReady || session.payloadMtu < config.minLinkMtu) return
        session.handshakeTimer?.cancel()
        val handle = handles.next()
        val now = clockMs()
        session.handle = handle
        session.active = true
        session.activatedMs = now
        session.reportedMtu = session.payloadMtu
        byHandle[handle] = session
        if (session.link.role == LinkRole.Client) {
            nextConnectAllowedMs = now + config.connectSpacingMs
            consecutiveConnectFailures = 0
            runCatching { session.link.requestPriority(linkPriority()) }
        }
        report(BleDiagnostic.LinkUp(session.link.address, session.link.role, handle, session.payloadMtu))
        eventChannel.trySend(TransportEvent.PeerSeen(handle, session.payloadMtu))
        pump(session)
    }

    private fun onMtuChanged(session: Session, payload: Int) {
        session.payloadMtu = payload
        if (!session.active) {
            tryActivate(session)
            return
        }
        if (payload == session.reportedMtu) return
        if (payload < config.minLinkMtu) {
            report(BleDiagnostic.MtuTooSmall(session.link.address, payload))
            closeSession(session, "MTU fell below the minimum")
            return
        }
        // The core accepts a repeated PeerSeen for a known handle as "the link parameters changed".
        session.reportedMtu = payload
        session.handle?.let { eventChannel.trySend(TransportEvent.PeerSeen(it, payload)) }
    }

    private fun onReceived(session: Session, bytes: ByteArray) {
        val handle = session.handle
        if (!session.active || handle == null || bytes.isEmpty()) return
        val now = clockMs()
        val m = session.metrics
        session.metrics =
            m.copy(
                packetsReceived = m.packetsReceived + 1,
                bytesReceived = m.bytesReceived + bytes.size,
                firstReceiveMs = m.firstReceiveMs ?: now,
                lastReceiveMs = now,
            )
        eventChannel.trySend(TransportEvent.PacketReceived(handle, bytes))
    }

    private fun onHandshakeTimeout(session: Session) {
        if (session.active) return
        val address = session.link.address
        if (session.attReady && session.payloadMtu < config.minLinkMtu) {
            report(BleDiagnostic.MtuTooSmall(address, session.payloadMtu))
        } else {
            report(BleDiagnostic.HandshakeTimeout(address, session.link.role, session.payloadMtu))
        }
        closeSession(session, "handshake timed out")
    }

    // ----- sending ------------------------------------------------------------------------------

    private fun onSend(handle: ULong, bytes: ByteArray) {
        val session = byHandle[handle] ?: return // the link is gone: dropped silently, by contract
        if (bytes.isEmpty()) return
        if (bytes.size > session.reportedMtu) {
            drop(session, handle, DropReason.TooLarge)
            return
        }
        if (!session.queue.offer(bytes)) {
            drop(session, handle, DropReason.QueueFull)
            return
        }
        pump(session)
    }

    private fun drop(session: Session, handle: ULong, reason: DropReason) {
        session.metrics = session.metrics.copy(packetsDropped = session.metrics.packetsDropped + 1)
        report(BleDiagnostic.PacketDropped(handle, reason))
    }

    /** Starts the next send if the link is idle. The packet stays at the head of the queue until it completes. */
    private fun pump(session: Session) {
        if (!session.active || session.inFlight || session.retryPending) return
        val packet = session.queue.peek() ?: return
        val accepted =
            try {
                session.link.send(packet)
            } catch (t: Throwable) {
                false
            }
        if (accepted) {
            session.inFlight = true
            session.attempts = 0
            session.sendStartedMs = clockMs()
            val token = ++session.sendToken
            session.sendTimer =
                scope.launch {
                    delay(config.sendTimeoutMs)
                    mailbox.trySend(Msg.SendTimeout(session.id, token))
                }
            return
        }
        session.attempts += 1
        if (session.attempts >= config.maxSendAttempts) {
            closeSession(session, "the platform kept refusing sends")
            return
        }
        session.retryPending = true
        session.retryTimer =
            scope.launch {
                delay(config.sendRetryDelayMs)
                mailbox.trySend(Msg.RetryPump(session.id))
            }
    }

    private fun onSendCompleted(session: Session, success: Boolean) {
        if (!session.inFlight) return
        session.inFlight = false
        session.sendTimer?.cancel()
        val packet = session.queue.removeFirst()
        val busy = (clockMs() - session.sendStartedMs).coerceAtLeast(0L)
        val m = session.metrics
        if (success && packet != null) {
            session.consecutiveFailures = 0
            session.metrics =
                m.copy(
                    packetsSent = m.packetsSent + 1,
                    bytesSent = m.bytesSent + packet.size,
                    txBusyMs = m.txBusyMs + busy,
                )
        } else {
            session.consecutiveFailures += 1
            session.metrics = m.copy(packetsDropped = m.packetsDropped + 1, txBusyMs = m.txBusyMs + busy)
            session.handle?.let { report(BleDiagnostic.PacketDropped(it, DropReason.SendFailed)) }
            if (session.consecutiveFailures >= config.maxConsecutiveSendFailures) {
                closeSession(session, "sends kept failing")
                return
            }
        }
        pump(session)
    }

    // ----- closing --------------------------------------------------------------------------------

    /**
     * Ends a session at once, whatever the platform does afterwards: the link is released, the
     * handle (if the core knew it) is reported lost, and events that still arrive for it are ignored
     * because the session is gone.
     *
     * A client link also decides what happens to its address: a link that was [fault]-free (closed
     * by the core, rotated) or that lived [BleConfig.stableLinkMs] is left alone for the plain
     * cooldown; anything else counts as a failure and backs off exponentially (see [recordFailure]).
     */
    private fun closeSession(session: Session, reason: String, closeLink: Boolean = true, fault: Boolean = true) {
        if (sessions.remove(session.id) == null) return
        session.collector?.cancel()
        session.handshakeTimer?.cancel()
        session.sendTimer?.cancel()
        session.retryTimer?.cancel()
        session.queue.clear()
        val address = session.link.address
        val role = session.link.role
        if (role == LinkRole.Client) {
            clientAddresses.remove(address)
            if (!stopping) {
                val now = clockMs()
                if (!session.active) nextConnectAllowedMs = now + config.connectSpacingMs
                when {
                    !fault -> startCooldown(address, config.reconnectCooldownMs)
                    session.active && now - session.activatedMs >= config.stableLinkMs -> {
                        failures.remove(address)
                        startCooldown(address, config.reconnectCooldownMs)
                    }
                    else -> recordFailure(address, session.failReason ?: CloseReason.ConnectFailed)
                }
            }
        }
        val handle = session.handle
        if (handle != null) {
            byHandle.remove(handle)
            eventChannel.trySend(TransportEvent.PeerLost(handle))
        }
        if (closeLink) runCatching { session.link.close() }
        report(BleDiagnostic.LinkDown(address, role, handle, reason))
    }

    /**
     * An outgoing link to [address] failed or lived too briefly. The address waits
     * [BleConfig.reconnectCooldownMs], doubled for every failure in a row (up to
     * [BleConfig.maxBackoffMs]); a device without our service waits at least
     * [BleConfig.setupFailedBackoffMs]. Several failures in a row, whichever the address, also pause
     * all new connections briefly: repeated GATT 133 usually means the Bluetooth stack needs a moment.
     */
    private fun recordFailure(address: String, reason: CloseReason) {
        val now = clockMs()
        val previous = failures[address]
        val count = (if (previous != null && now - previous.lastMs <= FAILURE_MEMORY_MS) previous.count else 0) + 1
        failures[address] = Failure(count, now)
        connectFailureTotal += 1
        var delayMs = config.reconnectCooldownMs
        var step = 1
        while (step < count && delayMs in 1 until config.maxBackoffMs) {
            delayMs *= 2
            step += 1
        }
        if (reason == CloseReason.SetupFailed) delayMs = max(delayMs, config.setupFailedBackoffMs)
        delayMs = min(delayMs, config.maxBackoffMs)
        startCooldown(address, delayMs)
        report(BleDiagnostic.ConnectBackoff(address, delayMs, count))
        consecutiveConnectFailures += 1
        if (consecutiveConnectFailures >= config.stackRecoveryThreshold) {
            consecutiveConnectFailures = 0
            connectPausedUntilMs = now + config.stackRecoveryPauseMs
            report(BleDiagnostic.ConnectionsPaused(config.stackRecoveryPauseMs))
        }
    }

    private fun onCooldownOver(address: String) {
        val until = backoffUntil[address] ?: return
        if (clockMs() >= until) backoffUntil.remove(address)
    }

    private fun startCooldown(address: String, delayMs: Long) {
        if (delayMs <= 0L) return
        backoffUntil[address] = max(backoffUntil[address] ?: 0L, clockMs() + delayMs)
        scope.launch {
            delay(delayMs)
            mailbox.trySend(Msg.CooldownOver(address))
        }
    }

    private fun stopPorts() {
        runCatching { scanner.stop() }
        runCatching { advertiser.stop() }
        runCatching { server.close() }
    }

    private fun report(diagnostic: BleDiagnostic) {
        diagnosticFlow.tryEmit(diagnostic)
    }

    private fun stats(now: Long): BleStats {
        val token = advertisedToken()
        return BleStats(
            profile = profile,
            screenOn = screenOn,
            scanning = scanning,
            scanMode = scanMode,
            advertising = advertising,
            ownToken = token,
            effectiveMaxLinks = effectiveMaxLinks(),
            candidates = candidates.size,
            heldByTieBreak = candidates.values.count { !TieBreak.shouldInitiate(token, it.advertisement) },
            backedOff = backoffUntil.values.count { it > now },
            connectAttempts = connectAttempts,
            connectFailures = connectFailureTotal,
            linkRotations = linkRotations,
            tokenRotations = tokenRotations,
            scanStarts = scanStartTotal,
        )
    }

    private fun Session.info() =
        LinkInfo(
            handle = handle,
            role = link.role,
            address = link.address,
            active = active,
            payloadMtu = if (active) reportedMtu else payloadMtu,
            queuedBytes = queue.bytes,
            metrics = metrics,
        )

    private companion object {
        const val DIAGNOSTIC_BUFFER = 64
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        const val MAX_CANDIDATES = 64
        const val MIN_LIMIT_FOR_RESERVE = 3
        const val MAX_DOUBLINGS = 4

        /** Failures older than this are forgotten: the address starts from a clean slate. */
        const val FAILURE_MEMORY_MS = 10 * 60_000L

        /** Android allows 5 scan starts in this window. */
        const val SCAN_WINDOW_MS = 30_000L
        const val SCAN_WINDOW_MARGIN_MS = 100L

        // Platform error codes (ScanCallback / AdvertiseCallback), kept here so that the state
        // machine stays free of Android classes.
        const val SCAN_FAILED_ALREADY_STARTED = 1
        const val SCAN_FAILED_TOO_FREQUENTLY = 6
        const val SCAN_START_EXCEPTION = -1
        const val ADVERTISE_DATA_TOO_LARGE = 1
        const val ADVERTISE_ALREADY_STARTED = 3
        const val ADVERTISE_FEATURE_UNSUPPORTED = 5
        const val ADVERTISE_START_EXCEPTION = -1
    }
}
