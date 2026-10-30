package org.crisisnetwork.android.ui.shell

/**
 * The screens that open on top of the shell (a chat, a contact, the QR screens...). They form a
 * stack, so that Back from a contact opened from a chat returns to the chat. The stack is saved as
 * one text (a code per screen, separated by `|`), which survives rotation and process death.
 */
sealed interface Overlay {
    data class Chat(val peerHex: String) : Overlay

    data class Contact(val peerHex: String) : Overlay

    /** The user's own card as a QR code. */
    data object MyCard : Overlay

    /** The camera that reads the QR code of another phone. */
    data object Scan : Overlay

    /** Entering a card as text. */
    data object Paste : Overlay

    /** Choosing a contact to write to. */
    data object NewChat : Overlay

    /** Writing an announcement. */
    data object Compose : Overlay

    /** The network status: neighbours, storage, radio and the core's counters (phase A6). */
    data object Network : Overlay

    fun encode(): String =
        when (this) {
            is Chat -> "chat:$peerHex"
            is Contact -> "contact:$peerHex"
            MyCard -> "mycard"
            Scan -> "scan"
            Paste -> "paste"
            NewChat -> "newchat"
            Compose -> "compose"
            Network -> "network"
        }

    companion object {
        /** `null` for a code that is not one (an old saved state from another version). */
        fun parse(code: String): Overlay? =
            when {
                code.startsWith("chat:") && isHex(code.removePrefix("chat:")) -> Chat(code.removePrefix("chat:"))
                code.startsWith("contact:") && isHex(code.removePrefix("contact:")) -> Contact(code.removePrefix("contact:"))
                code == "mycard" -> MyCard
                code == "scan" -> Scan
                code == "paste" -> Paste
                code == "newchat" -> NewChat
                code == "compose" -> Compose
                code == "network" -> Network
                else -> null
            }

        private fun isHex(text: String): Boolean = text.isNotEmpty() && text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** Operations on the stack of [Overlay]s, kept apart from the user interface so that they can be tested. */
object OverlayStack {
    fun decode(saved: String): List<Overlay> = if (saved.isEmpty()) emptyList() else saved.split('|').mapNotNull { Overlay.parse(it) }

    fun encode(stack: List<Overlay>): String = stack.joinToString("|") { it.encode() }

    /** Opens [overlay] on top; opening the one that is already on top changes nothing. */
    fun push(stack: List<Overlay>, overlay: Overlay): List<Overlay> = if (stack.lastOrNull() == overlay) stack else stack + overlay

    fun pop(stack: List<Overlay>): List<Overlay> = stack.dropLast(1)

    /**
     * Opens the chat with [peerHex]. When that chat is already somewhere in the stack, the screens
     * above it are closed instead of stacking a second copy (contact, "send a message", back).
     */
    fun toChat(stack: List<Overlay>, peerHex: String): List<Overlay> {
        val chat = Overlay.Chat(peerHex)
        val index = stack.indexOf(chat)
        return if (index >= 0) stack.take(index + 1) else stack + chat
    }
}
