package org.crisisnetwork.android.core.runtime.secrets

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sealing or opening a secret failed. */
class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Wraps small secrets (the identity seed, the database key) for storage on disk. The [label]
 * is bound to the ciphertext, so a sealed seed cannot be passed off as a sealed database key.
 */
interface SecretCipher {
    fun seal(plain: ByteArray, label: String): ByteArray

    fun open(sealed: ByteArray, label: String): ByteArray
}

/**
 * AES-256-GCM with a key supplied by [key]. On Android the key is a non-extractable Android
 * Keystore key ([KeystoreAesKey]); the unit tests use a software key.
 *
 * Layout of a sealed value: `version (1 byte) | iv (12 bytes) | ciphertext and tag`.
 */
class AesGcmSecretCipher(private val key: () -> SecretKey) : SecretCipher {
    override fun seal(plain: ByteArray, label: String): ByteArray =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(label.encodeToByteArray())
            val ciphertext = cipher.doFinal(plain)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LEN) throw SecretCipherException("unexpected IV length")
            byteArrayOf(FORMAT_VERSION) + iv + ciphertext
        } catch (e: SecretCipherException) {
            throw e
        } catch (e: Exception) {
            throw SecretCipherException("could not seal a secret", e)
        }

    override fun open(sealed: ByteArray, label: String): ByteArray {
        if (sealed.size < 1 + IV_LEN + TAG_LEN || sealed[0] != FORMAT_VERSION) {
            throw SecretCipherException("not a sealed secret of this version")
        }
        return try {
            val iv = sealed.copyOfRange(1, 1 + IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.updateAAD(label.encodeToByteArray())
            cipher.doFinal(sealed, 1 + IV_LEN, sealed.size - 1 - IV_LEN)
        } catch (e: Exception) {
            throw SecretCipherException("could not open a sealed secret", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_LEN = 12
        const val TAG_LEN = 16
    }
}
