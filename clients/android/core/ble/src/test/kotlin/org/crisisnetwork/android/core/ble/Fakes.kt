@file:OptIn(ExperimentalCoroutinesApi::class)

package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.crisisnetwork.android.core.runtime.transport.TransportEvent

/** A link the test drives by hand: it records what the transport does and emits what the test says. */
internal class FakeGattLink(
    override val role: LinkRole,
    override val address: String,
) : GattLink {
    private val channel = Channel<GattLinkEvent>(Channel.UNLIMITED)
    override val events: Flow<GattLinkEvent> = channel.receiveAsFlow()

    val sent = mutableListOf<ByteArray>()
    var closeCount = 0

    /** The next this many calls of [send] are refused (as the platform does when it is busy). */
    var refusals = 0

    override fun send(bytes: ByteArray): Boolean {
        if (refusals > 0) {
            refusals -= 1
            return false
        }
        sent += bytes
        return true
    }

    override fun close() {
        closeCount += 1
    }

    fun emit(event: GattLinkEvent) {
        channel.trySend(event)
    }
}

internal class FakeScanner : BleScanner {
    private val channel = Channel<ScanEvent>(Channel.UNLIMITED)
    override val events: Flow<ScanEvent> = channel.receiveAsFlow()

    var startCount = 0
    var stopCount = 0
    var failOnStart: Throwable? = null

    override fun start() {
        startCount += 1
        failOnStart?.let { throw it }
    }

    override fun stop() {
        stopCount += 1
    }

    fun sight(address: String, rssi: Int = -60) {
        channel.trySend(ScanEvent.Sighting(address, rssi))
    }

    fun fail(code: Int) {
        channel.trySend(ScanEvent.Failed(code))
    }
}

internal class FakeAdvertiser : BleAdvertiser {
    private val channel = Channel<AdvertiseEvent>(Channel.UNLIMITED)
    override val events: Flow<AdvertiseEvent> = channel.receiveAsFlow()

    var startCount = 0
    var stopCount = 0

    override fun start() {
        startCount += 1
    }

    override fun stop() {
        stopCount += 1
    }
}

internal class FakeServer : GattServerPort {
    private val incomingChannel = Channel<GattLink>(Channel.UNLIMITED)
    private val eventChannel = Channel<ServerEvent>(Channel.UNLIMITED)
    override val incoming: Flow<GattLink> = incomingChannel.receiveAsFlow()
    override val events: Flow<ServerEvent> = eventChannel.receiveAsFlow()

    var openCount = 0
    var closeCount = 0

    override fun open() {
        openCount += 1
    }

    override fun close() {
        closeCount += 1
    }

    /** A central connects to us. */
    fun accept(address: String): FakeGattLink = FakeGattLink(LinkRole.Server, address).also { incomingChannel.trySend(it) }
}

internal class FakeConnector : GattConnector {
    val connects = mutableListOf<String>()
    val created = mutableListOf<FakeGattLink>()
    var failNext: Throwable? = null

    override fun connect(address: String): GattLink {
        connects += address
        failNext?.let {
            failNext = null
            throw it
        }
        return FakeGattLink(LinkRole.Client, address).also { created += it }
    }
}

/** A transport wired to fakes, with the transport events and diagnostics collected into lists. */
internal class Harness(testScope: TestScope, config: BleConfig = BleConfig()) {
    val scanner = FakeScanner()
    val advertiser = FakeAdvertiser()
    val connector = FakeConnector()
    val server = FakeServer()
    val transport =
        BleTransport(
            scanner = scanner,
            advertiser = advertiser,
            connector = connector,
            server = server,
            config = config,
            dispatcher = StandardTestDispatcher(testScope.testScheduler),
            clockMs = { testScope.currentTime },
        )

    val events = mutableListOf<TransportEvent>()
    val diagnostics = mutableListOf<BleDiagnostic>()

    private val scope: TestScope = testScope

    init {
        // Unconfined: collecting starts at once, so nothing emitted later can be missed.
        val collectors = UnconfinedTestDispatcher(testScope.testScheduler)
        testScope.backgroundScope.launch(collectors) { transport.events.collect { events += it } }
        testScope.backgroundScope.launch(collectors) { transport.diagnostics.collect { diagnostics += it } }
    }

    /** Lets everything that is due run. */
    fun settle() = scope.runCurrent()

    /** Starts the transport and connects a client link to [address] that is ready with [attMtu]. */
    fun connectClient(address: String, attMtu: Int = 185): FakeGattLink {
        scanner.sight(address)
        settle()
        val link = connector.created.last { it.address == address }
        link.emit(GattLinkEvent.Ready(attMtu))
        settle()
        return link
    }
}
