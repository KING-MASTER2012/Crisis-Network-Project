package org.crisisnetwork.android.core.ffi

import uniffi.crisis_core.CoreException
import uniffi.crisis_core.CoreInfo
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.CoreOutput
import uniffi.crisis_core.CrisisNode
import uniffi.crisis_core.MessageKind
import uniffi.crisis_core.TransportCommand
import uniffi.crisis_core.coreInfo
import uniffi.crisis_core.defaultSettings
import uniffi.crisis_core.generateDatabaseKey
import uniffi.crisis_core.requireApiVersion
import java.io.File

/** The interface version of the core this module was written against (`FFI_API_VERSION`). */
const val EXPECTED_FFI_API_VERSION: UInt = 2u

/** One check of the self-test. */
data class SmokeStep(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

/** Result of [runCoreSmoke]. */
data class SmokeReport(
    /** Build information of the loaded core, or `null` when the library could not be loaded. */
    val info: CoreInfo?,
    val steps: List<SmokeStep>,
    /**
     * `true` when the core was built with SQLCipher, `false` when it refuses database keys,
     * `null` when the check did not run to completion.
     */
    val encryptionAvailable: Boolean?,
) {
    val passed: Boolean get() = info != null && steps.isNotEmpty() && steps.all { it.ok }

    /** A plain-text rendering, for logs and assertion messages. */
    fun describe(): String = steps.joinToString("\n") { "${if (it.ok) "PASS" else "FAIL"} ${it.name}: ${it.detail}" }
}

/**
 * Exercises the native core end to end on the device: library loading, version handshake,
 * a direct message between two in-memory nodes, an on-disk database that survives a reopen,
 * and the encrypted-database behaviour of this build.
 *
 * It is a diagnostic, not a unit test: it runs on a device or emulator and is used both by the
 * app's self-test screen and by the instrumented test. [workDir] receives temporary database
 * files and is emptied before and after the run.
 *
 * Time handling here is deliberately simple (wall clock, simulated for the loopback). The real
 * clock policy belongs to the runtime layer (phase A1).
 */
fun runCoreSmoke(workDir: File, expectedApiVersion: UInt = EXPECTED_FFI_API_VERSION): SmokeReport {
    val steps = mutableListOf<SmokeStep>()
    var info: CoreInfo? = null
    var encryption: Boolean? = null

    workDir.mkdirs()
    clearDirectory(workDir)

    step(steps, "load the native library and read core info") {
        val loaded = coreInfo()
        info = loaded
        "core ${loaded.crateVersion}, ffi api ${loaded.apiVersion}, protocol ${loaded.protocolVersion}, " +
            "schema ${loaded.storageSchemaVersion}, min link mtu ${loaded.minLinkMtu}"
    }

    step(steps, "interface version handshake") {
        requireApiVersion(expectedApiVersion)
        "api version $expectedApiVersion accepted"
    }

    step(steps, "two nodes exchange a direct message") {
        val settings = defaultSettings()
        val start = System.currentTimeMillis().toULong()
        val body = "smoke: meet at the square".encodeToByteArray()
        CrisisNode.inMemory(seedOf(1), settings, start).use { a ->
            CrisisNode.inMemory(seedOf(2), settings, start).use { b ->
                val pair = LoopbackPair(a, b, start)
                a.addContact(pair.now, b.contactCard())
                b.addContact(pair.now, a.contactCard())
                val sent = a.publishDirect(pair.now, b.nodeId(), body)
                pair.absorb(fromA = true, output = sent.output)
                pair.settle()

                val received = pair.notesB
                    .filterIsInstance<CoreNotification.MessageReceived>()
                    .firstOrNull { it.id.contentEquals(sent.messageId) }
                check(received != null) { "the recipient did not receive the message" }
                check(received.kind == MessageKind.DIRECT) { "unexpected message kind ${received.kind}" }
                check(received.body.contentEquals(body)) { "the received body differs from the sent body" }
                val delivered = pair.notesA
                    .filterIsInstance<CoreNotification.Delivered>()
                    .any { it.id.contentEquals(sent.messageId) }
                check(delivered) { "the sender never learned that the message arrived" }
                "received, decrypted and acknowledged after ${pair.now - start} ms of simulated time"
            }
        }
    }

    step(steps, "on-disk database survives a reopen") {
        val path = File(workDir, "plain.db").absolutePath
        val seed = seedOf(3)
        val now = System.currentTimeMillis().toULong()
        val payload = "smoke: stored on disk".encodeToByteArray()
        val id = CrisisNode.open(seed, path, null, defaultSettings(), now).use { node ->
            node.publishBroadcast(now, payload).messageId
        }
        CrisisNode.open(seed, path, null, defaultSettings(), now).use { node ->
            val page = node.history(0UL, 50u, now)
            val found = page.entries.firstOrNull { it.id.contentEquals(id) }
            check(found != null) { "the message was lost across a reopen" }
            check(found.body?.contentEquals(payload) == true) { "the stored body changed" }
        }
        "message persisted in an unencrypted database"
    }

    step(steps, "encrypted database") {
        val file = File(workDir, "encrypted.db")
        val seed = seedOf(4)
        val key = generateDatabaseKey()
        val now = System.currentTimeMillis().toULong()
        val payload = "smoke: stored encrypted".encodeToByteArray()

        val firstOpen = try {
            CrisisNode.open(seed, file.absolutePath, key, defaultSettings(), now)
        } catch (e: CoreException.EncryptionUnavailable) {
            null
        }

        if (firstOpen == null) {
            encryption = false
            "this build has no SQLCipher: a key is refused instead of silently storing plaintext " +
                "(build with -Pcrisis.sqlcipher=true to enable it)"
        } else {
            encryption = true
            val id = firstOpen.use { it.publishBroadcast(now, payload).messageId }

            check(!startsWithSqliteMagic(file)) { "the database file is plaintext SQLite although a key was given" }

            CrisisNode.open(seed, file.absolutePath, key, defaultSettings(), now).use { node ->
                val page = node.history(0UL, 50u, now)
                check(page.entries.any { it.id.contentEquals(id) }) { "the message is missing after reopening with the key" }
            }

            val wrongKeyRefused = try {
                CrisisNode.open(seed, file.absolutePath, generateDatabaseKey(), defaultSettings(), now).close()
                false
            } catch (e: CoreException.WrongKeyOrNotADatabase) {
                true
            }
            check(wrongKeyRefused) { "a wrong key was accepted" }
            "SQLCipher active: file is not plaintext, right key opens it, wrong key is refused"
        }
    }

    clearDirectory(workDir)
    return SmokeReport(info = info, steps = steps, encryptionAvailable = encryption)
}

/** Runs [block] and records its outcome; any `Throwable` (including a failed library load) is a failed step. */
private inline fun step(steps: MutableList<SmokeStep>, name: String, block: () -> String) {
    steps += try {
        SmokeStep(name, ok = true, detail = block())
    } catch (t: Throwable) {
        SmokeStep(name, ok = false, detail = "${t::class.simpleName}: ${t.message}")
    }
}

private fun seedOf(n: Int): ByteArray = ByteArray(32) { n.toByte() }

private fun clearDirectory(dir: File) {
    dir.listFiles()?.forEach { it.delete() }
}

/** `true` when the file begins with the plaintext SQLite header (`SQLite format 3`). */
private fun startsWithSqliteMagic(file: File): Boolean {
    val magic = "SQLite format 3".encodeToByteArray()
    val head = ByteArray(magic.size)
    val read = file.inputStream().use { it.read(head) }
    return read == magic.size && head.contentEquals(magic)
}

/**
 * The radio of the self-test: wires two nodes together and plays the transport. Node `a`
 * sees `b` as neighbour 2 and `b` sees `a` as neighbour 1, like the Rust end-to-end test.
 */
private class LoopbackPair(
    private val a: CrisisNode,
    private val b: CrisisNode,
    start: ULong,
) {
    var now: ULong = start
        private set
    val notesA = mutableListOf<CoreNotification>()
    val notesB = mutableListOf<CoreNotification>()

    /** Packets in flight: (sent by a?, bytes). */
    private val queue = ArrayDeque<Pair<Boolean, ByteArray>>()

    init {
        absorb(fromA = true, output = a.peerSeen(now, PEER_B_AS_SEEN_BY_A, LINK_MTU))
        absorb(fromA = false, output = b.peerSeen(now, PEER_A_AS_SEEN_BY_B, LINK_MTU))
    }

    /** Takes the effects of an output produced by `a` ([fromA]) or by `b`. */
    fun absorb(fromA: Boolean, output: CoreOutput) {
        for (command in output.transport) {
            if (command is TransportCommand.SendPacket) queue.addLast(fromA to command.bytes)
        }
        (if (fromA) notesA else notesB).addAll(output.notifications)
    }

    /** Delivers packets and ticks both nodes until the network is quiet. */
    fun settle(steps: Int = 300) {
        repeat(steps) {
            now += STEP_MS
            val packets = queue.toList()
            queue.clear()
            for ((fromA, bytes) in packets) {
                if (fromA) {
                    absorb(fromA = false, output = b.packetReceived(now, PEER_A_AS_SEEN_BY_B, bytes))
                } else {
                    absorb(fromA = true, output = a.packetReceived(now, PEER_B_AS_SEEN_BY_A, bytes))
                }
            }
            absorb(fromA = true, output = a.tick(now))
            absorb(fromA = false, output = b.tick(now))
        }
    }

    private companion object {
        const val PEER_B_AS_SEEN_BY_A: ULong = 2UL
        const val PEER_A_AS_SEEN_BY_B: ULong = 1UL
        const val LINK_MTU: UInt = 185u
        const val STEP_MS: ULong = 50UL
    }
}
