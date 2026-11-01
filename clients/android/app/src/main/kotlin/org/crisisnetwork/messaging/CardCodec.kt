package org.crisisnetwork.android.messaging

import uniffi.crisis_core.decodeCardText
import uniffi.crisis_core.encodeCardText

/**
 * The text form of a contact card, used in QR codes and when a card is shared as text.
 *
 * A card is 128 bytes (a public key and its signature: nothing secret). As text it is the prefix
 * `crisis:` followed by the card in URL-safe base64 without padding (171 characters), which fits a
 * QR code of version 8 at the lowest error correction level: few, large modules that scan easily.
 * [decode] is forgiving: it ignores white space and the case of the prefix, the prefix may be
 * missing, and standard base64 is accepted as well. The rules live in the Rust core (`appcodec`).
 */
object CardCodec {
    /** Length of a contact card in bytes. */
    const val CARD_LEN = 128

    const val PREFIX = "crisis:"

    fun encode(card: ByteArray): String = encodeCardText(card)

    /** The card bytes, or `null` when [text] is not a card (wrong characters or wrong length). */
    fun decode(text: String): ByteArray? = decodeCardText(text)
}
