package org.crisisnetwork.android.core.runtime.secrets

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretVaultTest {
    private lateinit var dir: File
    private val key: SecretKey = newKey()
    private var counter = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vault").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun vault(secretKey: SecretKey = key) =
        SecretVault(
            dir = File(dir, "vault"),
            cipher = AesGcmSecretCipher { secretKey },
            newSeed = { ByteArray(32) { (counter + it).toByte() }.also { counter += 1 } },
            newDatabaseKey = { ByteArray(32) { (100 + counter + it).toByte() }.also { counter += 1 } },
        )

    private fun ready(result: VaultResult): NodeSecrets = (result as VaultResult.Ready).secrets

    @Test
    fun createsSecretsOnFirstUseAndReturnsTheSameOnesAfterwards() {
        val first = ready(vault().loadOrCreate())
        val second = ready(vault().loadOrCreate())
        assertEquals(32, first.seed.size)
        assertEquals(32, first.databaseKey.size)
        assertArrayEquals(first.seed, second.seed)
        assertArrayEquals(first.databaseKey, second.databaseKey)
        assertFalse(first.seed.contentEquals(first.databaseKey))
    }

    @Test
    fun theFilesOnDiskDoNotContainThePlainSecrets() {
        val secrets = ready(vault().loadOrCreate())
        val onDisk = File(dir, "vault/seed.bin").readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(String(secrets.seed, Charsets.ISO_8859_1)))
    }

    @Test
    fun aWrongKeyIsReportedAndNothingIsOverwritten() {
        ready(vault().loadOrCreate())
        val before = File(dir, "vault/seed.bin").readBytes()
        val result = vault(newKey()).loadOrCreate()
        assertTrue(result is VaultResult.Unavailable)
        assertTrue((result as VaultResult.Unavailable).resettable)
        assertArrayEquals(before, File(dir, "vault/seed.bin").readBytes())
    }

    @Test
    fun aTamperedFileIsReportedNotReplaced() {
        ready(vault().loadOrCreate())
        val file = File(dir, "vault/seed.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(vault().loadOrCreate() is VaultResult.Unavailable)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aTruncatedFileIsReported() {
        ready(vault().loadOrCreate())
        File(dir, "vault/dbkey.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(vault().loadOrCreate() is VaultResult.Unavailable)
    }

    @Test
    fun aSealedSeedCannotBePassedOffAsTheDatabaseKey() {
        ready(vault().loadOrCreate())
        File(dir, "vault/seed.bin").copyTo(File(dir, "vault/dbkey.bin"), overwrite = true)
        assertTrue(vault().loadOrCreate() is VaultResult.Unavailable)
    }

    @Test
    fun resetMakesANewIdentity() {
        val before = ready(vault().loadOrCreate())
        vault().resetIdentity()
        val after = ready(vault().loadOrCreate())
        assertFalse(before.seed.contentEquals(after.seed))
    }

    @Test
    fun wipeClearsTheArrays() {
        val secrets = ready(vault().loadOrCreate())
        secrets.wipe()
        assertTrue(secrets.seed.all { it == 0.toByte() })
        assertTrue(secrets.databaseKey.all { it == 0.toByte() })
        // The stored copy is untouched.
        assertNotEquals(0, ready(vault().loadOrCreate()).seed.sum())
    }

    @Test
    fun aVaultThatCannotWriteReportsItAsNotResettable() {
        // A regular file where the directory should be makes creation fail.
        File(dir, "vault").writeText("in the way")
        val result = vault().loadOrCreate()
        assertTrue(result is VaultResult.Unavailable)
        assertFalse((result as VaultResult.Unavailable).resettable)
    }

    private companion object {
        fun newKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
