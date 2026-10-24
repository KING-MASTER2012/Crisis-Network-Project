package org.crisisnetwork.android.ui.onboarding

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.core.runtime.EncryptionPolicy
import org.crisisnetwork.android.core.runtime.NodeBootstrap
import org.crisisnetwork.android.core.runtime.OpenResult
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.transport.Transport
import org.crisisnetwork.android.core.runtime.transport.TransportEvent

/** A transport that does nothing: opens the node for what needs only the node (the identity) and no radio. */
internal object NullTransport : Transport {
    override val events: Flow<TransportEvent> = emptyFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override fun send(peer: ULong, bytes: ByteArray) = Unit

    override fun disconnect(peer: ULong) = Unit
}

sealed interface IdentityResult {
    /** The identity exists. Both values are public: they are what a contact card shows. */
    data class Created(val safetyNumber: String, val nodeIdHex: String) : IdentityResult

    data class Failed(val failure: CoreFailure) : IdentityResult
}

/**
 * Creates the identity of this phone (phase A4): the first time the node is opened, the core's
 * key pair is generated from a fresh random seed, which the vault seals with a Keystore key that
 * never leaves the hardware-backed store; it is the same step that every later start performs.
 * Nothing is sent anywhere and no account exists.
 *
 * The node is opened without a radio and closed again at once; the mesh itself is started by
 * `MeshService`. While the mesh is running, the identity of the running node is returned instead
 * (the database must not be opened twice).
 */
object IdentityCreator {
    suspend fun create(context: Context): IdentityResult {
        val app = context.applicationContext
        if (MeshController.state.value.status != MeshController.Status.Stopped) {
            val running = MeshController.runtime
            return if (running != null) {
                IdentityResult.Created(running.safetyNumber, running.nodeId.toHex())
            } else {
                IdentityResult.Failed(CoreFailure.RuntimeStopped)
            }
        }
        return when (val opened = NodeBootstrap.open(NodeBootstrap.defaultRoot(app), NullTransport, EncryptionPolicy.forApp(app))) {
            is OpenResult.Failed -> IdentityResult.Failed(opened.failure)
            is OpenResult.Ready -> {
                val node = opened.runtime
                val result = IdentityResult.Created(node.safetyNumber, node.nodeId.toHex())
                node.stop()
                result
            }
        }
    }

    /** Deletes the identity and everything stored with it. Only after the user confirmed: the identity cannot be recovered. */
    suspend fun reset(context: Context) {
        NodeBootstrap.resetIdentity(NodeBootstrap.defaultRoot(context.applicationContext))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
