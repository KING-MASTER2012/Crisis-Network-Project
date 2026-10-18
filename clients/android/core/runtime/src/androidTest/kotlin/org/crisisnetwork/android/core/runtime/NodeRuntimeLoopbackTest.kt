package org.crisisnetwork.android.core.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.crisisnetwork.android.core.runtime.clock.CoreClock
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.failure.CoreFailureException
import org.crisisnetwork.android.core.runtime.secrets.KeystoreAesKey
import org.crisisnetwork.android.core.runtime.transport.LoopbackNetwork
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.CrisisNode
import uniffi.crisis_core.MessageKind
import uniffi.crisis_core.defaultSettings
import java.io.File

/**
 * Phase A1 exit criterion, on a device or emulator with the real native core: two nodes, each
 * behind its own [NodeRuntime], exchange a direct message and its acknowledgement over the
 * in-memory loopback transport.
 */
@RunWith(AndroidJUnit4::class)
class NodeRuntimeLoopbackTest {
    private val clock = CoreClock { System.currentTimeMillis() }

    private fun newRuntime(seedByte: Int, transport: org.crisisnetwork.android.core.runtime.transport.Transport): NodeRuntime {
        val node = CrisisNode.inMemory(ByteArray(32) { seedByte.toByte() }, defaultSettings(), clock.nowMs().toULong())
        return NodeRuntime(node, transport, clock)
    }

    @Test
    fun twoNodesExchangeADirectMessageAndItsAck() {
        runBlocking {
            val network = LoopbackNetwork()
            val ta = network.endpoint()
            val tb = network.endpoint()
            val a = newRuntime(1, ta)
            val b = newRuntime(2, tb)
            a.start()
            b.start()
            try {
                a.addContact(b.contactCard)
                b.addContact(a.contactCard)

                val body = "meet at the square".encodeToByteArray()
                coroutineScope {
                    // Subscribe before anything can happen: notifications are not replayed.
                    val received =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(TIMEOUT_MS) {
                                b.notifications.filterIsInstance<CoreNotification.MessageReceived>().first()
                            }
                        }
                    val delivered =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(TIMEOUT_MS) {
                                a.notifications.filterIsInstance<CoreNotification.Delivered>().first()
                            }
                        }

                    network.connect(ta, tb, linkMtu = 185)
                    val outcome = a.publishDirect(b.nodeId, body)

                    val message = received.await()
                    assertEquals(MessageKind.DIRECT, message.kind)
                    assertArrayEquals(body, message.body)
                    assertArrayEquals(a.nodeId, message.sender)
                    assertArrayEquals(outcome.messageId, message.id)

                    val ack = delivered.await()
                    assertArrayEquals(outcome.messageId, ack.id)
                }

                assertEquals(0, ta.oversizedPacketsDropped)
                assertEquals(0, tb.oversizedPacketsDropped)
                assertEquals(1, a.connectedPeers().size)
            } finally {
                a.stop()
                b.stop()
            }
        }
    }

    @Test
    fun aBroadcastReachesTheNeighbourAndAppearsInItsHistory() {
        runBlocking {
            val network = LoopbackNetwork()
            val ta = network.endpoint()
            val tb = network.endpoint()
            val a = newRuntime(3, ta)
            val b = newRuntime(4, tb)
            a.start()
            b.start()
            try {
                val body = "water at the school".encodeToByteArray()
                coroutineScope {
                    val received =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(TIMEOUT_MS) {
                                b.notifications.filterIsInstance<CoreNotification.MessageReceived>().first()
                            }
                        }
                    network.connect(ta, tb)
                    a.publishBroadcast(body, lifetimeSecs = 3_600u)
                    assertEquals(MessageKind.BROADCAST, received.await().kind)
                }
                val page = b.history(0uL, 50u)
                assertTrue(page.entries.any { it.body?.contentEquals(body) == true })
            } finally {
                a.stop()
                b.stop()
            }
        }
    }

    @Test
    fun callsAfterStopFailWithRuntimeStopped() {
        runBlocking {
            val a = newRuntime(5, LoopbackNetwork().endpoint())
            a.start()
            a.stop()
            try {
                a.stats()
                fail("a stopped runtime must not accept calls")
            } catch (e: CoreFailureException) {
                assertEquals(CoreFailure.RuntimeStopped, e.failure)
            }
            assertEquals(RuntimeState.Stopped, a.state.value)
        }
    }

    @Test
    fun aCoreErrorFailsOnlyTheCallThatCausedItAndTheNodeKeepsRunning() {
        runBlocking {
            val a = newRuntime(6, LoopbackNetwork().endpoint())
            a.start()
            try {
                try {
                    // No contact card is known for this node.
                    a.publishDirect(ByteArray(32) { 9 }, "x".encodeToByteArray())
                    fail("expected UnknownContact")
                } catch (e: CoreFailureException) {
                    assertEquals(CoreFailure.UnknownContact, e.failure)
                }
                assertFalse(a.state.value is RuntimeState.Failed)
                assertNotNull(a.stats())
            } finally {
                a.stop()
            }
        }
    }

    @Test
    fun theIdentityAndTheDatabaseSurviveARestartThroughTheKeystoreVault() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "bootstrap-test").apply { deleteRecursively() }
            val alias = "org.crisisnetwork.android.test.vault"
            val policy = EncryptionPolicy.IfAvailable
            try {
                val first = openReady(root, alias, policy)
                val id = first.nodeId
                first.start()
                val body = "kept across restarts".encodeToByteArray()
                first.publishBroadcast(body)
                first.stop()

                val second = openReady(root, alias, policy)
                second.start()
                try {
                    assertArrayEquals(id, second.nodeId)
                    val page = second.history(0uL, 50u)
                    assertTrue(page.entries.any { it.body?.contentEquals(body) == true })
                } finally {
                    second.stop()
                }
            } finally {
                root.deleteRecursively()
                KeystoreAesKey.delete(alias)
            }
        }
    }

    private suspend fun openReady(root: File, alias: String, policy: EncryptionPolicy): NodeRuntime {
        val result =
            NodeBootstrap.open(
                rootDir = root,
                transport = LoopbackNetwork().endpoint(),
                encryption = policy,
                keyAlias = alias,
            )
        check(result is OpenResult.Ready) { "open failed: ${(result as? OpenResult.Failed)?.failure}" }
        return result.runtime
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
