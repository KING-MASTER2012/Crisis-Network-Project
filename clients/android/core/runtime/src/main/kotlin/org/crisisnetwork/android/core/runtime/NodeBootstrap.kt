package org.crisisnetwork.android.core.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.crisisnetwork.android.core.ffi.EXPECTED_FFI_API_VERSION
import org.crisisnetwork.android.core.runtime.clock.CoreClock
import org.crisisnetwork.android.core.runtime.clock.FileHighWaterStore
import org.crisisnetwork.android.core.runtime.clock.MonotonicWallClock
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.failure.toCoreFailure
import org.crisisnetwork.android.core.runtime.secrets.AesGcmSecretCipher
import org.crisisnetwork.android.core.runtime.secrets.KeystoreAesKey
import org.crisisnetwork.android.core.runtime.secrets.NodeSecrets
import org.crisisnetwork.android.core.runtime.secrets.SecretVault
import org.crisisnetwork.android.core.runtime.secrets.VaultResult
import org.crisisnetwork.android.core.runtime.transport.Transport
import uniffi.crisis_core.CoreException
import uniffi.crisis_core.CrisisNode
import uniffi.crisis_core.NodeSettings
import uniffi.crisis_core.defaultSettings
import uniffi.crisis_core.generateDatabaseKey
import uniffi.crisis_core.generateSeed
import uniffi.crisis_core.requireApiVersion
import java.io.File

/** Whether the database is encrypted. A key is never ignored silently: plaintext is always a deliberate choice. */
enum class EncryptionPolicy {
    /** Encrypted or nothing: a core without SQLCipher makes opening fail with [CoreFailure.EncryptionUnavailable]. */
    Required,

    /** Encrypted when the core has SQLCipher, plaintext when it has not. For development builds only. */
    IfAvailable,

    /** Never encrypted. Tests. */
    Disabled,
    ;

    companion object {
        /**
         * Release builds require encryption; debuggable builds use it when the core has it
         * (`-Pcrisis.sqlcipher=true`), so that day-to-day development does not need OpenSSL.
         *
         * A development device that switches between a build with and a build without SQLCipher
         * cannot read the other build's database ([CoreFailure.DatabaseUnreadable]): clear the
         * app data.
         */
        fun forApp(context: Context): EncryptionPolicy {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return if (debuggable) IfAvailable else Required
        }
    }
}

/** Result of [NodeBootstrap.open]. */
sealed interface OpenResult {
    /** The runtime exists but is not started yet: call [NodeRuntime.start]. */
    class Ready(val runtime: NodeRuntime) : OpenResult

    class Failed(val failure: CoreFailure) : OpenResult
}

/**
 * Brings a node up: checks the interface version, unwraps the identity seed and the database
 * key from the Keystore-backed vault, opens the database and returns a [NodeRuntime].
 *
 * Everything lives under one root directory, which on a device is `noBackupFilesDir/crisis`
 * ([defaultRoot]): the database, the sealed secrets and the clock mark. Android never copies
 * that directory to a backup or to another device.
 */
object NodeBootstrap {
    const val DEFAULT_KEY_ALIAS = "org.crisisnetwork.android.vault.v1"

    fun defaultRoot(context: Context): File = File(context.noBackupFilesDir, "crisis")

    /** Opens (or creates) the node. Runs on [dispatcher]; never call it from the main thread directly. */
    suspend fun open(
        rootDir: File,
        transport: Transport,
        encryption: EncryptionPolicy,
        settings: NodeSettings? = null,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        clock: CoreClock? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OpenResult =
        withContext(dispatcher) {
            try {
                requireApiVersion(EXPECTED_FFI_API_VERSION)
            } catch (t: Throwable) {
                return@withContext OpenResult.Failed(t.toCoreFailure())
            }

            rootDir.mkdirs()
            val vault =
                SecretVault(
                    dir = File(rootDir, "vault"),
                    cipher = AesGcmSecretCipher { KeystoreAesKey.getOrCreate(keyAlias) },
                    newSeed = { generateSeed() },
                    newDatabaseKey = { generateDatabaseKey() },
                )
            val secrets =
                when (val loaded = vault.loadOrCreate()) {
                    is VaultResult.Ready -> loaded.secrets
                    is VaultResult.Unavailable ->
                        return@withContext OpenResult.Failed(
                            CoreFailure.SecretsUnavailable(loaded.reason, loaded.resettable),
                        )
                }

            val effectiveClock =
                clock
                    ?: MonotonicWallClock(
                        wallMs = System::currentTimeMillis,
                        elapsedMs = SystemClock::elapsedRealtime,
                        store = FileHighWaterStore(File(rootDir, "clock.mark")),
                    )

            try {
                val node =
                    openNode(
                        secrets = secrets,
                        databasePath = File(rootDir, "node.db").absolutePath,
                        settings = settings ?: defaultSettings(),
                        nowMs = effectiveClock.nowMs().coerceAtLeast(0L).toULong(),
                        policy = encryption,
                    )
                OpenResult.Ready(NodeRuntime(node, transport, effectiveClock, dispatcher))
            } catch (t: Throwable) {
                OpenResult.Failed(t.toCoreFailure())
            } finally {
                secrets.wipe()
            }
        }

    /**
     * Deletes the stored identity (seed and database key) and the database. The user loses the
     * identity and all messages. Call it only after an explicit confirmation, for example when
     * the open result is [CoreFailure.SecretsUnavailable] with `resettable`.
     */
    suspend fun resetIdentity(
        rootDir: File,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            rootDir.deleteRecursively()
            KeystoreAesKey.delete(keyAlias)
        }
    }

    private fun openNode(
        secrets: NodeSecrets,
        databasePath: String,
        settings: NodeSettings,
        nowMs: ULong,
        policy: EncryptionPolicy,
    ): CrisisNode =
        when (policy) {
            EncryptionPolicy.Disabled -> CrisisNode.open(secrets.seed, databasePath, null, settings, nowMs)
            EncryptionPolicy.Required -> CrisisNode.open(secrets.seed, databasePath, secrets.databaseKey, settings, nowMs)
            EncryptionPolicy.IfAvailable ->
                try {
                    CrisisNode.open(secrets.seed, databasePath, secrets.databaseKey, settings, nowMs)
                } catch (e: CoreException.EncryptionUnavailable) {
                    CrisisNode.open(secrets.seed, databasePath, null, settings, nowMs)
                }
        }
}
