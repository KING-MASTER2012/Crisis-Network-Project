package org.crisisnetwork.android.core.runtime.secrets

import org.crisisnetwork.android.core.runtime.AtomicFiles
import java.io.File

/** The two secrets a node needs. Call [wipe] as soon as they were handed to the core. */
class NodeSecrets(val seed: ByteArray, val databaseKey: ByteArray) {
    /**
     * Overwrites these arrays. This clears *these* copies only: the JVM may have copied the bytes
     * before (garbage collector, marshalling to the native library), see the security notes in
     * the Android README.
     */
    fun wipe() {
        seed.fill(0)
        databaseKey.fill(0)
    }
}

sealed interface VaultResult {
    class Ready(val secrets: NodeSecrets) : VaultResult

    /**
     * The secrets cannot be provided. [resettable] is `true` when stored files exist but cannot
     * be decrypted; nothing was overwritten or deleted.
     */
    class Unavailable(val reason: String, val cause: Throwable?, val resettable: Boolean) : VaultResult
}

/**
 * Stores the identity seed and the database key, each sealed with [cipher], in [dir]
 * (use a directory under `noBackupFilesDir`).
 *
 * Rules:
 *
 * * A missing file is created with fresh random bytes.
 * * A file that exists but cannot be opened is **never overwritten**: replacing a lost identity
 *   silently would be data loss the user did not agree to. The result is
 *   [VaultResult.Unavailable]; only [resetIdentity] (an explicit user decision) clears it.
 */
class SecretVault(
    private val dir: File,
    private val cipher: SecretCipher,
    private val newSeed: () -> ByteArray,
    private val newDatabaseKey: () -> ByteArray,
) {
    @Synchronized
    fun loadOrCreate(): VaultResult {
        val seed =
            try {
                obtain(SEED_FILE, SEED_LABEL, newSeed)
            } catch (e: VaultException) {
                return e.toResult()
            }
        val key =
            try {
                obtain(DB_KEY_FILE, DB_KEY_LABEL, newDatabaseKey)
            } catch (e: VaultException) {
                seed.fill(0)
                return e.toResult()
            }
        return VaultResult.Ready(NodeSecrets(seed, key))
    }

    /** Deletes the stored secrets. The next [loadOrCreate] makes a new identity. */
    @Synchronized
    fun resetIdentity() {
        File(dir, SEED_FILE).delete()
        File(dir, DB_KEY_FILE).delete()
    }

    private class VaultException(
        message: String,
        cause: Throwable?,
        val resettable: Boolean,
    ) : Exception(message, cause) {
        fun toResult() = VaultResult.Unavailable(message ?: "secrets unavailable", cause, resettable)
    }

    private fun obtain(name: String, label: String, create: () -> ByteArray): ByteArray {
        val file = File(dir, name)
        if (file.isFile) {
            val opened =
                try {
                    cipher.open(file.readBytes(), label)
                } catch (e: Exception) {
                    throw VaultException("the stored $name cannot be decrypted", e, resettable = true)
                }
            if (opened.isEmpty()) throw VaultException("the stored $name is empty", null, resettable = true)
            return opened
        }
        return try {
            dir.mkdirs()
            val fresh = create()
            AtomicFiles.write(file, cipher.seal(fresh, label))
            fresh
        } catch (e: Exception) {
            throw VaultException("could not create $name", e, resettable = false)
        }
    }

    private companion object {
        const val SEED_FILE = "seed.bin"
        const val DB_KEY_FILE = "dbkey.bin"
        const val SEED_LABEL = "crisis/vault/v1/seed"
        const val DB_KEY_LABEL = "crisis/vault/v1/database-key"
    }
}
