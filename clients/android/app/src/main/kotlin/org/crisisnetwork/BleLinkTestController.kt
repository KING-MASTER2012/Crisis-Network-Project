package org.crisisnetwork.android

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.crisis_core.CoreNotification

/**
 * The measuring half of the test bench behind [BleLinkTestActivity] (phases A2 and A3). Since A3 the
 * node and the Bluetooth transport belong to [MeshController] (and so to the foreground service);
 * this object only sends test announcements through the running node and counts what arrives.
 * It is a development tool, not product UI (the real screens arrive in A4/A5).
 *
 * The messages are public broadcasts, because they need no contact exchange (the QR flow is A5).
 */
object BleLinkTestController {
    /** Ten messages stay below the core's per-sender burst limit of 20 (refill 2 per second). */
    const val BURST_COUNT = 10
    const val BURST_PAYLOAD_BYTES = 1_000

    data class Counters(
        val receivedMessages: Int = 0,
        val receivedBytes: Long = 0,
        val firstReceivedMs: Long? = null,
        val lastReceivedMs: Long? = null,
    ) {
        /** Application payload bytes per second between the first and the last received message. */
        val receiveBytesPerSecond: Double?
            get() {
                val first = firstReceivedMs ?: return null
                val last = lastReceivedMs ?: return null
                return if (receivedMessages >= 2 && last > first) receivedBytes * 1000.0 / (last - first) else null
            }
    }

    private const val PREVIEW_CHARS = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableCounters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> = mutableCounters.asStateFlow()

    init {
        scope.launch {
            MeshController.notifications.collect { note ->
                if (note is CoreNotification.MessageReceived) onMessage(note)
            }
        }
    }

    /** One announcement with [text]. */
    fun sendBroadcast(text: String) {
        val node = MeshController.runtime ?: return
        scope.launch {
            try {
                node.publishBroadcast(text.encodeToByteArray())
                MeshController.log("sent: $text")
            } catch (t: Throwable) {
                MeshController.log("send failed: ${t.message}")
            }
        }
    }

    /**
     * [BURST_COUNT] announcements of [BURST_PAYLOAD_BYTES] bytes, to measure the link: the other
     * phone's counters show how long the batch took to arrive (read them there).
     */
    fun sendBurst() {
        val node = MeshController.runtime ?: return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                for (i in 1..BURST_COUNT) {
                    val header = "burst ${SystemClock.elapsedRealtime()} $i/$BURST_COUNT ".encodeToByteArray()
                    val payload = ByteArray(BURST_PAYLOAD_BYTES) { 'x'.code.toByte() }
                    header.copyInto(payload, endIndex = minOf(header.size, payload.size))
                    node.publishBroadcast(payload)
                }
                MeshController.log(
                    "burst published: $BURST_COUNT x $BURST_PAYLOAD_BYTES B in ${SystemClock.elapsedRealtime() - started} ms " +
                        "(delivery is measured on the other phone)",
                )
            } catch (t: Throwable) {
                MeshController.log("burst failed: ${t.message}")
            }
        }
    }

    /** Zeroes the receive counters here and the link counters of the transport. */
    fun resetCounters() {
        MeshController.resetLinkMetrics()
        mutableCounters.update { Counters() }
    }

    private fun onMessage(note: CoreNotification.MessageReceived) {
        val now = SystemClock.elapsedRealtime()
        mutableCounters.update {
            it.copy(
                receivedMessages = it.receivedMessages + 1,
                receivedBytes = it.receivedBytes + note.body.size,
                firstReceivedMs = it.firstReceivedMs ?: now,
                lastReceivedMs = now,
            )
        }
        MeshController.log("received ${note.body.size} B: ${note.body.decodeToString().take(PREVIEW_CHARS)}")
    }
}
