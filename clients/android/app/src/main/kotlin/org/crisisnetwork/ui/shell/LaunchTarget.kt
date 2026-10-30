package org.crisisnetwork.android.ui.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a tap on a notification leads (phase A6). */
sealed interface LaunchTarget {
    data class Chat(val peerHex: String) : LaunchTarget

    data object Announcements : LaunchTarget

    companion object {
        const val ACTION_OPEN_CHAT = "org.crisisnetwork.android.action.OPEN_CHAT"
        const val ACTION_OPEN_ANNOUNCEMENTS = "org.crisisnetwork.android.action.OPEN_ANNOUNCEMENTS"
        const val EXTRA_PEER = "org.crisisnetwork.android.extra.PEER"

        /**
         * The target an intent asks for, or `null` for anything else. The node id must be well-formed
         * (the rule of [Overlay]); an intent from another app cannot open a screen with a broken id.
         */
        fun of(action: String?, peerHex: String?): LaunchTarget? =
            when (action) {
                ACTION_OPEN_CHAT -> (peerHex?.let { Overlay.parse("chat:$it") } as? Overlay.Chat)?.let { Chat(it.peerHex) }
                ACTION_OPEN_ANNOUNCEMENTS -> Announcements
                else -> null
            }
    }
}

/**
 * The one waiting request to open a screen: [MainActivity] posts it, the shell opens the screen and
 * consumes it. A request that arrives before the shell exists (the introduction is still showing)
 * waits.
 */
object LaunchRequests {
    private val flow = MutableStateFlow<LaunchTarget?>(null)

    val pending: StateFlow<LaunchTarget?> = flow.asStateFlow()

    fun post(target: LaunchTarget) {
        flow.value = target
    }

    /** Clears the request, unless a newer one replaced it meanwhile. */
    fun consume(target: LaunchTarget) {
        flow.compareAndSet(target, null)
    }
}
