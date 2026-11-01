package org.crisisnetwork.android.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device: the rules under test live in the Rust core, which is only loaded there
 * (the JVM unit tests cannot load the native library).
 */
@RunWith(AndroidJUnit4::class)
class NamesTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()

        override fun all(): Map<String, String> = map.toMap()

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun nicknamesAreSavedTrimmedAndRemovedWhenEmpty() {
        val backing = MapStore()
        val store = NicknameStore(backing)
        assertEquals("Ayşe", store.set("aa", "  Ayşe \n"))
        assertEquals(mapOf("aa" to "Ayşe"), store.loadAll())
        assertEquals("Ayşe K.", store.set("aa", "Ayşe K."))
        assertNull(store.set("aa", "   "))
        assertEquals(emptyMap<String, String>(), store.loadAll())
    }

    @Test
    fun aNicknameIsOneShortCleanLine() {
        assertEquals("a b", NicknameStore.normalize("a\nb"))
        assertEquals("ab", NicknameStore.normalize("a\u0000b"))
        assertEquals(NicknameStore.MAX_LENGTH, NicknameStore.normalize("x".repeat(100))!!.length)
        assertNull(NicknameStore.normalize("\u0007"))
    }

    @Test
    fun shortIdsAreEightDigits() {
        assertEquals("0123abcd", Names.shortId("0123abcd4567ef89"))
        assertEquals("ab", Names.shortId("ab"))
    }

    @Test
    fun onlyMessagesIWroteHaveAStatus() {
        assertNull(MessageStatus.of(outgoing = false, direct = true, delivered = false))
        assertNull(MessageStatus.of(outgoing = false, direct = false, delivered = false))
        assertEquals(DeliveryStatus.Published, MessageStatus.of(outgoing = true, direct = true, delivered = false))
        assertEquals(DeliveryStatus.Delivered, MessageStatus.of(outgoing = true, direct = true, delivered = true))
        // An announcement is never acknowledged: it stays published.
        assertEquals(DeliveryStatus.Published, MessageStatus.of(outgoing = true, direct = false, delivered = false))
    }
}
