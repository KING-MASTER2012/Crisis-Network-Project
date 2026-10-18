package org.crisisnetwork.android.core.runtime.secrets

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES-256 key in the Android Keystore that wraps the node's secrets. The key never leaves
 * the Keystore; it needs no user authentication (the node must start without the user), and it
 * is hardware backed (StrongBox when the device has one, otherwise the TEE) where available.
 *
 * A restored backup or a device-to-device transfer cannot bring the key along, which is why
 * nothing of the node is backed up (see `data_extraction_rules.xml`).
 */
object KeystoreAesKey {
    private const val PROVIDER = "AndroidKeyStore"

    /** Returns the key named [alias], creating it on first use. */
    fun getOrCreate(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: ProviderException) {
                // StrongBoxUnavailableException is a ProviderException: fall back to the TEE.
            }
        }
        return generate(alias, strongBox = false)
    }

    /** Deletes the key named [alias]. Secrets sealed with it can no longer be opened. */
    fun delete(alias: String) {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val builder =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
