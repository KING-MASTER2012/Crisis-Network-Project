package org.crisisnetwork.android.messaging

import uniffi.crisis_core.inspectContactCard

/**
 * The introduction: how one scan of a QR code is enough to become contacts of each other.
 *
 * A private message can only be written to a person whose contact card is known. When A shows the QR
 * code and B scans it, only B knows A's card; A would have to scan B's code as well. To avoid that, B's
 * phone sends A a private message whose text is B's own card in its text form (see [CardCodec]).
 * A's phone recognises it, adds the card and never shows the message.
 *
 * A message counts as an introduction only when the card inside belongs to its **sender** (the sender
 * is authenticated by the signature of the message), so nobody can introduce somebody else. Whether a
 * received introduction is acted on is decided by [Messaging] (only while the person's own code is on
 * screen or was a moment ago).
 */
object IntroCodec {
    /** A card as text is 178 characters; anything much longer is an ordinary message. */
    private const val MAX_BYTES = 400

    /** The body of the introduction that carries [card] (the 128 card bytes). */
    fun encode(card: ByteArray): ByteArray = CardCodec.encode(card).toByteArray(Charsets.UTF_8)

    /** A cheap test that rejects ordinary messages without calling the core: a short text starting with the card prefix. */
    fun looksLikeIntro(body: ByteArray): Boolean {
        if (body.isEmpty() || body.size > MAX_BYTES) return false
        return String(body, Charsets.UTF_8).trimStart().startsWith(CardCodec.PREFIX, ignoreCase = true)
    }

    /** The card bytes when [body] is an introduction from [owner] (the card's node id is [owner]), otherwise `null`. */
    fun parse(body: ByteArray, owner: ByteArray): ByteArray? {
        if (!looksLikeIntro(body)) return null
        val card = CardCodec.decode(String(body, Charsets.UTF_8)) ?: return null
        val details = runCatching { inspectContactCard(card) }.getOrNull() ?: return null
        return if (details.nodeId.contentEquals(owner)) card else null
    }
}
