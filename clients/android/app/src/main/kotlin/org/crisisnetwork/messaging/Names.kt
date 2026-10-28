package org.crisisnetwork.android.messaging

import android.content.SharedPreferences

/** Where nicknames are kept. A map in tests, `SharedPreferences` in the app. */
interface KeyValueStore {
    fun all(): Map<String, String>

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** [KeyValueStore] on `SharedPreferences` (private to the app, excluded from backups). */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun all(): Map<String, String> = prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * Nicknames: what the user calls a contact, kept on this phone only (the core knows keys, not
 * names). Keyed by the node id in hexadecimal.
 */
class NicknameStore(private val store: KeyValueStore) {
    fun loadAll(): Map<String, String> = store.all()

    /** Saves [raw] for [nodeIdHex] (an empty text removes the nickname) and returns what was saved, if anything. */
    fun set(nodeIdHex: String, raw: String): String? {
        val nickname = normalize(raw)
        if (nickname == null) store.remove(nodeIdHex) else store.put(nodeIdHex, nickname)
        return nickname
    }

    companion object {
        const val MAX_LENGTH = 40

        /** One line, no control characters, trimmed, at most [MAX_LENGTH] characters; `null` when nothing is left. */
        fun normalize(raw: String): String? {
            val oneLine = TextSanitizer.clean(raw).replace('\n', ' ').replace('\t', ' ').trim()
            return oneLine.take(MAX_LENGTH).trim().ifEmpty { null }
        }
    }
}

/** How a node is called on screen. */
object Names {
    /** The first eight hexadecimal digits of a node id: enough to tell nodes apart at a glance. */
    fun shortId(nodeIdHex: String): String = nodeIdHex.take(8)
}

/** What a sent message has been through so far. Received messages have no status. */
enum class DeliveryStatus { Published, Delivered }

object MessageStatus {
    /**
     * The status of a message: `null` for one received from someone else. A direct message the
     * user sent is delivered once the recipient's acknowledgement came back, otherwise it is only
     * published (stored and spreading). An announcement never gets an acknowledgement.
     */
    fun of(outgoing: Boolean, direct: Boolean, delivered: Boolean): DeliveryStatus? =
        when {
            !outgoing -> null
            direct && delivered -> DeliveryStatus.Delivered
            else -> DeliveryStatus.Published
        }
}
