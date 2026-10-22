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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.crisisnetwork.android.core.runtime.transport.Transport
import org.crisisnetwork.android.core.runtime.transport.TransportEvent

/**
 * The BLE link layer of the core's [Transport] (phase A2).
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
 * * **Discovery policy is deliberately simple** (phase A3 replaces it): connect to every device
 *   seen that is not linked yet, up to [BleConfig.maxLinks], and leave a failed address alone for
 *   [BleConfig.reconnectCooldownMs]. There is no tie-break between two phones that connect to
 *   each other, no rotation of links and no back-off per error code.
 *
 * Packets are not framed: one core packet is exactly one GATT write (central to peripheral) or
 * one notification (peripheral to central). The core already fits every packet to the MTU
 * reported with `PeerSeen`, which is the ATT MTU minus 3 (capped at 512).
 *
 * @param clockMs a monotonic millisecond clock, used only for the throughput counters.
 */
class BleTransport(
    private val scanner: BleScanner,
    private val advertiser: BleAdvertiser,
    private val connector: GattConnector,
    private val server: GattServerPort,
    private val config: BleConfig = BleConfig(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
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
    private val cooldown = HashSet<String>()
    private val handles = HandleAllocator()
    private var nextSessionId = 1

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
        feeders = jobs
        try {
            // The GATT service must exist before anybody can connect to the advertisement.
            if (role.peripheral) {
                server.open()
                advertiser.start()
            }
            if (role.central) scanner.start()
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

    // ----- diagnostics API --------------------------------------------------------------------

    /** A snapshot of every link, connecting or usable. Empty once the transport is stopped. */
    suspend fun linkInfo(): List<LinkInfo> {
        val reply = CompletableDeferred<List<LinkInfo>>()
        if (mailbox.trySend(Msg.Snapshot(reply)).isFailure) return emptyList()
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { reply.await() } ?: emptyList()
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

        data object ResetMetrics : Msg
    }

    private class Session(val id: Int, val link: GattLink, val queue: SendQueue) {
        var handle: ULong? = null
        var active = false
        var attReady = false
        var payloadMtu = 0
        var reportedMtu = 0

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
            is Msg.Advertise ->
                report(
                    when (val e = message.event) {
                        AdvertiseEvent.Started -> BleDiagnostic.AdvertiseStarted
                        is AdvertiseEvent.Failed -> BleDiagnostic.AdvertiseFailed(e.code)
                    },
                )
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
            is Msg.CooldownOver -> cooldown.remove(message.address)
            is Msg.Send -> onSend(message.handle, message.bytes)
            is Msg.Disconnect -> byHandle[message.handle]?.let { closeSession(it, "closed by the core") }
            is Msg.Snapshot -> message.reply.complete(sessions.values.map { it.info() })
            Msg.ResetMetrics -> sessions.values.forEach { it.metrics = LinkMetrics() }
        }
    }

    // ----- discovery and new links ------------------------------------------------------------

    private fun onScan(event: ScanEvent) {
        when (event) {
            is ScanEvent.Failed -> report(BleDiagnostic.ScanFailed(event.code))
            is ScanEvent.Sighting -> {
                val address = event.address
                if (stopping || !config.role.central) return
                if (address in clientAddresses || address in cooldown) return
                if (sessions.size >= config.maxLinks) return
                val link =
                    try {
                        connector.connect(address)
                    } catch (t: Throwable) {
                        report(BleDiagnostic.ConnectFailed(address, CloseReason.SetupFailed, null))
                        startCooldown(address)
                        return
                    }
                register(link)
            }
        }
    }

    private fun onIncoming(link: GattLink) {
        if (stopping) {
            runCatching { link.close() }
            return
        }
        if (sessions.size >= config.maxLinks) {
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
        session.handle = handle
        session.active = true
        session.reportedMtu = session.payloadMtu
        byHandle[handle] = session
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
     * because the session is gone. A client link also starts a cooldown so that the next scan result
     * for the same device does not reconnect in a tight loop.
     */
    private fun closeSession(session: Session, reason: String, closeLink: Boolean = true) {
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
            if (!stopping) startCooldown(address)
        }
        val handle = session.handle
        if (handle != null) {
            byHandle.remove(handle)
            eventChannel.trySend(TransportEvent.PeerLost(handle))
        }
        if (closeLink) runCatching { session.link.close() }
        report(BleDiagnostic.LinkDown(address, role, handle, reason))
    }

    private fun startCooldown(address: String) {
        if (config.reconnectCooldownMs <= 0L) return
        cooldown += address
        scope.launch {
            delay(config.reconnectCooldownMs)
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
    }
}
