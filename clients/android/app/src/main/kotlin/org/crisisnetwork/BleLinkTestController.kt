package org.crisisnetwork.android

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.crisisnetwork.android.core.ble.BleConfig
import org.crisisnetwork.android.core.ble.BleDiagnostic
import org.crisisnetwork.android.core.ble.BleRole
import org.crisisnetwork.android.core.ble.BleTransport
import org.crisisnetwork.android.core.ble.LinkInfo
import org.crisisnetwork.android.core.ble.android.BleSupport
import org.crisisnetwork.android.core.runtime.EncryptionPolicy
import org.crisisnetwork.android.core.runtime.NodeBootstrap
import org.crisisnetwork.android.core.runtime.NodeRuntime
import org.crisisnetwork.android.core.runtime.OpenResult
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.coreInfo

/**
 * Phase A2 test bench behind [BleLinkTestActivity]: runs the real node over the real BLE
 * transport so that two phones can be checked end to end and the link measured. It is a
 * development tool, not product UI (the real screens arrive in A4/A5), and it lives in the app
 * process rather than in the activity so that rotating the screen does not stop the node.
 *
 * The messages are public broadcasts, because they need no contact exchange (the QR flow is A5).
 */
object BleLinkTestController {
    /** Ten messages stay below the core's per-sender burst limit of 20 (refill 2 per second). */
    const val BURST_COUNT = 10
    const val BURST_PAYLOAD_BYTES = 1_000

    data class UiState(
        val role: BleRole = BleRole.Peripheral,
        val running: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val safetyNumber: String? = null,
        val links: List<LinkInfo> = emptyList(),
        val receivedMessages: Int = 0,
        val receivedBytes: Long = 0,
        val firstReceivedMs: Long? = null,
        val lastReceivedMs: Long? = null,
        val log: List<String> = emptyList(),
    ) {
        /** Application payload bytes per second between the first and the last received message. */
        val receiveBytesPerSecond: Double?
            get() {
                val first = firstReceivedMs ?: return null
                val last = lastReceivedMs ?: return null
                return if (receivedMessages >= 2 && last > first) receivedBytes * 1000.0 / (last - first) else null
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var runtime: NodeRuntime? = null
    private var transport: BleTransport? = null
    private var jobs: List<Job> = emptyList()

    fun setRole(role: BleRole) {
        if (!mutableState.value.running && !mutableState.value.busy) mutableState.update { it.copy(role = role) }
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    /** Opens the node, starts the transport and the observers. The permissions must be granted already. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (mutableState.value.running || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                val config = BleConfig(role = mutableState.value.role, minLinkMtu = coreInfo().minLinkMtu.toInt())
                val ble = BleSupport.createTransport(app, config)
                val opened = NodeBootstrap.open(NodeBootstrap.defaultRoot(app), ble, EncryptionPolicy.forApp(app))
                val node =
                    when (opened) {
                        is OpenResult.Ready -> opened.runtime
                        is OpenResult.Failed -> {
                            mutableState.update { it.copy(busy = false, error = "Could not open the node: ${opened.failure}") }
                            return@launch
                        }
                    }
                try {
                    node.start()
                } catch (t: Throwable) {
                    runCatching { node.stop() }
                    mutableState.update { it.copy(busy = false, error = "Could not start Bluetooth: ${t.message}") }
                    return@launch
                }
                runtime = node
                transport = ble
                mutableState.update {
                    UiState(role = it.role, running = true, safetyNumber = node.safetyNumber, log = listOf("started as ${it.role}"))
                }
                observe(node, ble)
            } catch (t: Throwable) {
                mutableState.update { it.copy(busy = false, error = "${t::class.simpleName}: ${t.message}") }
            }
        }
    }

    fun stop() {
        val node = runtime ?: return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            runCatching { node.stop() }
            runtime = null
            transport = null
            mutableState.update { it.copy(running = false, busy = false, links = emptyList(), log = it.log.take(MAX_LOG - 1) + "stopped") }
        }
    }

    /** One announcement with [text]. */
    fun sendBroadcast(text: String) {
        val node = runtime ?: return
        scope.launch {
            try {
                node.publishBroadcast(text.encodeToByteArray())
                log("sent: $text")
            } catch (t: Throwable) {
                log("send failed: ${t.message}")
            }
        }
    }

    /**
     * [BURST_COUNT] announcements of [BURST_PAYLOAD_BYTES] bytes, to measure the link: the other
     * phone's counters show how long the batch took to arrive (read them there).
     */
    fun sendBurst() {
        val node = runtime ?: return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                for (i in 1..BURST_COUNT) {
                    val header = "burst ${SystemClock.elapsedRealtime()} $i/$BURST_COUNT ".encodeToByteArray()
                    val payload = ByteArray(BURST_PAYLOAD_BYTES) { 'x'.code.toByte() }
                    header.copyInto(payload, endIndex = minOf(header.size, payload.size))
                    node.publishBroadcast(payload)
                }
                log("burst published: $BURST_COUNT x $BURST_PAYLOAD_BYTES B in ${SystemClock.elapsedRealtime() - started} ms (delivery is measured on the other phone)")
            } catch (t: Throwable) {
                log("burst failed: ${t.message}")
            }
        }
    }

    /** Zeroes the receive counters here and the link counters of the transport. */
    fun resetCounters() {
        transport?.resetMetrics()
        mutableState.update { it.copy(receivedMessages = 0, receivedBytes = 0, firstReceivedMs = null, lastReceivedMs = null) }
    }

    // ----- observers ---------------------------------------------------------------------------------

    private fun observe(node: NodeRuntime, ble: BleTransport) {
        jobs =
            listOf(
                scope.launch {
                    node.notifications.collect { note ->
                        when (note) {
                            is CoreNotification.MessageReceived -> onMessage(note)
                            is CoreNotification.PeerConnected -> log("core: peer ${note.peer} accepted")
                            is CoreNotification.PeerDisconnected -> log("core: peer ${note.peer} gone")
                            is CoreNotification.PeerBanned -> log("core: peer ${note.peer} BANNED")
                            is CoreNotification.PublishRejected -> log("core: publish rejected (${note.reason})")
                            else -> Unit
                        }
                    }
                },
                scope.launch { ble.diagnostics.collect { log("ble: ${describe(it)}") } },
                scope.launch { node.failures.collect { log("failure: $it") } },
                scope.launch {
                    while (isActive) {
                        val links = ble.linkInfo()
                        mutableState.update { it.copy(links = links) }
                        delay(POLL_MS)
                    }
                },
            )
    }

    private fun onMessage(note: CoreNotification.MessageReceived) {
        val now = SystemClock.elapsedRealtime()
        mutableState.update {
            it.copy(
                receivedMessages = it.receivedMessages + 1,
                receivedBytes = it.receivedBytes + note.body.size,
                firstReceivedMs = it.firstReceivedMs ?: now,
                lastReceivedMs = now,
            )
        }
        val text = note.body.decodeToString().take(PREVIEW_CHARS)
        log("received ${note.body.size} B: $text")
    }

    private fun describe(d: BleDiagnostic): String =
        when (d) {
            is BleDiagnostic.LinkUp -> "link up ${d.address} ${d.role} handle=${d.handle} mtu=${d.payloadMtu}"
            is BleDiagnostic.LinkDown -> "link down ${d.address} ${d.role} handle=${d.handle}: ${d.reason}"
            else -> d.toString()
        }

    private fun log(line: String) {
        mutableState.update { it.copy(log = (listOf(line) + it.log).take(MAX_LOG)) }
    }

    private const val MAX_LOG = 60
    private const val POLL_MS = 1_000L
    private const val PREVIEW_CHARS = 40
}
