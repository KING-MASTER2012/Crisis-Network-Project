package org.crisisnetwork.android.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.crisis_core.conventionInfo

/**
 * Runs the shared test vectors of the Rust core (`core/testdata/appcodec.json`) against the app's
 * conventions on a device. The expected values were produced by an independent implementation, so a
 * disagreement means the core and the app really differ.
 */
@RunWith(AndroidJUnit4::class)
class ConventionVectorsTest {
    private val vectors: JSONObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("appcodec.json")) { "appcodec.json is not packed with the tests" }
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun cases(name: String): List<JSONObject> {
        val list: JSONArray = vectors.getJSONArray(name)
        check(list.length() > 0) { "$name has no cases" }
        return List(list.length()) { list.getJSONObject(it) }
    }

    private fun bytes(hex: String): ByteArray = checkNotNull(Hex.decode(hex)) { "bad hex $hex" }

    private fun severity(level: Int): Severity = Severity.entries[level]

    private fun unit(name: String): LifetimeUnit = LifetimeUnit.valueOf(name.replaceFirstChar { it.uppercase() })

    @Test
    fun sanitize() {
        for (case in cases("sanitize")) assertEquals("$case", case.getString("output"), TextSanitizer.clean(case.getString("input")))
    }

    @Test
    fun announcementEncode() {
        for (case in cases("announcement_encode")) {
            val payload = AnnouncementCodec.encode(case.getString("text"), severity(case.getInt("severity")))
            assertArrayEquals("$case", bytes(case.getString("hex")), payload)
        }
    }

    @Test
    fun announcementDecode() {
        for (case in cases("announcement_decode")) {
            val decoded = AnnouncementCodec.decode(bytes(case.getString("hex")))
            assertEquals("$case", case.getString("text"), decoded.text)
            assertEquals("$case", severity(case.getInt("severity")), decoded.severity)
        }
    }

    @Test
    fun cardEncode() {
        for (case in cases("card_encode")) assertEquals("$case", case.getString("text"), CardCodec.encode(bytes(case.getString("hex"))))
    }

    @Test
    fun cardDecode() {
        for (case in cases("card_decode")) {
            val decoded = CardCodec.decode(case.getString("input"))
            if (case.isNull("hex")) assertNull("$case", decoded) else assertArrayEquals("$case", bytes(case.getString("hex")), decoded)
        }
    }

    @Test
    fun nicknames() {
        for (case in cases("nickname")) {
            val expected = if (case.isNull("output")) null else case.getString("output")
            assertEquals("$case", expected, NicknameStore.normalize(case.getString("input")))
        }
    }

    @Test
    fun lifetimes() {
        for (case in cases("lifetime_split")) {
            assertEquals("$case", case.getLong("value") to unit(case.getString("unit")), Lifetimes.split(case.getLong("secs")))
        }
        for (case in cases("lifetime_to_seconds")) {
            assertEquals("$case", case.getLong("secs"), Lifetimes.toSeconds(case.getLong("value"), unit(case.getString("unit"))))
        }
        for (case in cases("lifetime_presets")) {
            val expected = case.getJSONArray("presets").let { list -> List(list.length()) { list.getLong(it) } }
            assertEquals("$case", expected, Lifetimes.presets(case.getLong("max_secs")))
        }
    }

    @Test
    fun byteBudgets() {
        for (case in cases("byte_budget")) {
            val max = case.getInt("max_payload")
            val actual =
                when (case.getString("kind")) {
                    "direct_limit" -> ByteBudget.directLimit(max)
                    else -> ByteBudget.announcementLimit(max, severity(case.getInt("severity")))
                }
            assertEquals("$case", case.getInt("limit"), actual)
        }
        for (case in cases("byte_count")) {
            val count = ByteBudget.count(case.getString("text"), case.getInt("limit"))
            assertEquals("$case", case.getInt("used"), count.used)
            assertEquals("$case", case.getInt("remaining"), count.remaining.coerceAtLeast(0))
            assertEquals("$case", case.getBoolean("over"), count.over)
            assertEquals("$case", case.getBoolean("near_limit"), count.nearLimit)
        }
    }

    @Test
    fun deliveryStatus() {
        for (case in cases("message_status")) {
            val expected =
                when (if (case.isNull("status")) null else case.getString("status")) {
                    null -> null
                    "published" -> DeliveryStatus.Published
                    else -> DeliveryStatus.Delivered
                }
            assertEquals("$case", expected, MessageStatus.of(case.getBoolean("outgoing"), case.getBoolean("direct"), case.getBoolean("delivered")))
        }
    }

    @Test
    fun shortIds() {
        for (case in cases("short_id")) assertEquals("$case", case.getString("output"), Names.shortId(case.getString("input")))
    }

    @Test
    fun theConstantsOfTheAppEqualTheCore() {
        val info = conventionInfo()
        assertEquals(info.cardLen.toInt(), CardCodec.CARD_LEN)
        assertEquals(info.cardPrefix, CardCodec.PREFIX)
        assertEquals(info.maxNicknameChars.toInt(), NicknameStore.MAX_LENGTH)
        assertEquals(info.directSealOverhead.toInt(), ByteBudget.DIRECT_SEAL_OVERHEAD)
        assertEquals(info.minLifetimeSecs, Lifetimes.MIN_SECS)
        assertEquals(info.defaultLifetimeSecs, Lifetimes.DEFAULT_SECS)
        assertEquals(info.shortIdLen.toInt(), 8)
    }
}
