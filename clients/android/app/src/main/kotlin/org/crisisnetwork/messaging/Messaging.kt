package org.crisisnetwork.android.messaging

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.notifications.MessageNotifier
import org.crisisnetwork.android.core.runtime.NodeRuntime
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.failure.toCoreFailure
import org.crisisnetwork.android.ui.UiPreferences
import uniffi.crisis_core.ContactInfo
import uniffi.crisis_core.ConversationInfo
import uniffi.crisis_core.ConversationRef
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.HistoryItem
import uniffi.crisis_core.MessageDirection
import uniffi.crisis_core.MessageKind
import uniffi.crisis_core.PublishFailure
import uniffi.crisis_core.PublishOutcome
import uniffi.crisis_core.TrustLevel
import uniffi.crisis_core.coreInfo
import uniffi.crisis_core.inspectContactCard

/** A person this phone knows: a contact card was added, the trust was changed, or the user gave a nickname. */
data class ContactRow(
    val hex: String,
    val trust: TrustLevel,
    /** `true` when the card is stored: only then can a message be sent to this node. */
    val hasCard: Boolean,
    val safetyNumber: String?,
    val lastSeenMs: Long,
)

/** One line of the list of chats. */
data class ChatRow(
    val peerHex: String,
    /** The text of the newest message, `null` when it cannot be shown. */
    val preview: String?,
    val lastTimeMs: Long,
    val lastOutgoing: Boolean,
    val unread: Int,
)

/** One message as a screen shows it. */
data class MessageRow(
    val seq: Long,
    val outgoing: Boolean,
    /** The text, `null` when the message cannot be read (it failed to decrypt, or was sent before this phone kept a copy). */
    val text: String?,
    val timeMs: Long,
    val status: DeliveryStatus?,
    /** The sender's node id for a message from someone else. */
    val senderHex: String?,
    val severity: Severity,
)

/** The open conversation with one person. Messages are oldest first. */
data class ChatState(
    val peerHex: String? = null,
    val messages: List<MessageRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loading: Boolean = false,
    /** The first message that was unread when the chat was opened: the "new messages" line goes above it. */
    val firstUnreadSeq: Long? = null,
    val loaded: Boolean = false,
)

/** The announcements feed. Messages are newest first. */
data class FeedState(
    val messages: List<MessageRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loading: Boolean = false,
    val firstUnreadSeq: Long? = null,
    val loaded: Boolean = false,
)

/** Unread incoming messages: in the chats together, and in the announcements. */
data class Unread(val chats: Int = 0, val announcements: Int = 0)

sealed interface SendResult {
    data object Sent : SendResult

    data object Empty : SendResult

    data object TooLong : SendResult

    /** The node is not open: messages can only be sent while the phone is part of the mesh. */
    data object MeshOff : SendResult

    data object UnknownContact : SendResult

    data class Rejected(val reason: PublishFailure) : SendResult

    data class Failed(val detail: String) : SendResult
}

sealed interface AddResult {
    data class Added(val hex: String) : AddResult

    /** The mesh is off: the card is kept and added as soon as the node runs. */
    data class Queued(val hex: String) : AddResult

    data object OwnCard : AddResult

    data object Invalid : AddResult

    data class Failed(val detail: String) : AddResult
}

/**
 * Everything the messaging screens read and do (phase A5), in one process-wide holder like
 * [MeshController], so that it survives screen rotation and does not depend on a screen being there.
 *
 * * **State** is exposed as flows: contacts, the list of chats, unread counts, the open chat, the
 *   announcements feed, nicknames. The data comes from the core, which keeps the messages; this
 *   holder keeps only what is on screen. It is refreshed whenever [MeshController.dataVersion]
 *   changes (a message arrived, was published or delivered) and when the node starts.
 * * **Paging.** The open conversation is a window over the newest messages that grows backwards by
 *   one page at a time ([loadOlderChat], [loadOlderFeed]). A refresh reloads the whole window, so
 *   that delivery marks of older messages stay right.
 * * **Reading.** While a conversation is open, new incoming messages are marked read at once.
 * * **Nicknames** and **cards added while the mesh is off** are kept in the app's private storage.
 *
 * The node is only open while the mesh runs (the foreground service owns it), so messages can
 * only be read and sent then; [ready] says whether that is the case.
 */
object Messaging {
    private const val PAGE = 40
    private const val MAX_WINDOW = 2_000
    private const val CONTACT_LIMIT = 1_000u
    private const val CHAT_LIMIT = 500u
    private const val PREVIEW_CHARS = 120
    private const val PREFS_NICKNAMES = "nicknames"
    private const val REFRESH_DELAY_MS = 80L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private var started = false
    private lateinit var nicknameStore: NicknameStore
    private lateinit var appContext: Context

    private val readyFlow = MutableStateFlow(false)
    private val limitsFlow = MutableStateFlow(Limits())
    private val namesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val contactsFlow = MutableStateFlow<List<ContactRow>>(emptyList())
    private val chatsFlow = MutableStateFlow<List<ChatRow>>(emptyList())
    private val unreadFlow = MutableStateFlow(Unread())
    private val chatFlow = MutableStateFlow(ChatState())
    private val feedFlow = MutableStateFlow(FeedState())

    /** `true` while the node is open: messages can be read and sent. */
    val ready: StateFlow<Boolean> = readyFlow.asStateFlow()

    val limits: StateFlow<Limits> = limitsFlow.asStateFlow()

    /** Nicknames by node id in hexadecimal. */
    val names: StateFlow<Map<String, String>> = namesFlow.asStateFlow()

    val contacts: StateFlow<List<ContactRow>> = contactsFlow.asStateFlow()
    val chats: StateFlow<List<ChatRow>> = chatsFlow.asStateFlow()
    val unread: StateFlow<Unread> = unreadFlow.asStateFlow()
    val chat: StateFlow<ChatState> = chatFlow.asStateFlow()
    val feed: StateFlow<FeedState> = feedFlow.asStateFlow()

    // Opening and closing a screen only swap these references (on the main thread, in order, so that a
    // rotation, which closes and opens the same screen, cannot end up closed). The work on a window
    // happens while holding [lock] and checks that the window is still the open one before it publishes.
    @Volatile
    private var chatWindow: Window? = null

    @Volatile
    private var feedWindow: Window? = null

    /** The loaded part of one conversation: entries oldest first, and where the next older page starts. */
    private class Window(val ref: ConversationRef, val peerHex: String? = null) {
        var entries: List<HistoryItem> = emptyList()
        var olderBefore: ULong? = null
        var readUpTo: ULong = 0u
        var firstUnreadSeq: ULong? = null
        var loaded = false
    }

    // ----- lifecycle ---------------------------------------------------------------------------------

    /** Starts observing the mesh. Idempotent; call it before the first screen. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        nicknameStore = NicknameStore(PrefsKeyValueStore(appContext.getSharedPreferences(PREFS_NICKNAMES, Context.MODE_PRIVATE)))
        namesFlow.value = nicknameStore.loadAll()
        scope.launch {
            try {
                val info = coreInfo()
                limitsFlow.value = Limits(info.maxPayloadBytes.toInt(), info.maxLifetimeSecs.toLong())
            } catch (t: Throwable) {
                MeshController.log("messaging: limits unavailable (${t.message})")
            }
        }
        scope.launch {
            combine(
                MeshController.state.map { it.status }.distinctUntilChanged(),
                MeshController.dataVersion,
            ) { status, version -> status to version }
                .collectLatest {
                    // A burst of notifications becomes one refresh.
                    delay(REFRESH_DELAY_MS)
                    refresh()
                }
        }
    }

    private fun runningRuntime(): NodeRuntime? = MeshController.runtime?.takeIf { MeshController.state.value.status == MeshController.Status.Running }

    // ----- opening and closing screens -----------------------------------------------------------------

    /** The chat screen with [peerHex] appeared: load it and keep it up to date and read. */
    fun openChat(peerHex: String) {
        val peer = Hex.decode(peerHex) ?: return
        chatWindow = Window(ConversationRef.Direct(peer), peerHex)
        MessageNotifier.cancelChat(appContext, peerHex)
        chatFlow.value = ChatState(peerHex = peerHex, loading = true)
        scope.launch { refresh() }
    }

    fun closeChat() {
        chatWindow = null
        chatFlow.value = ChatState()
    }

    /** The announcements screen appeared. */
    fun openFeed() {
        feedWindow = Window(ConversationRef.Announcements)
        MessageNotifier.cancelAnnouncements(appContext)
        feedFlow.value = FeedState(loading = true)
        scope.launch { refresh() }
    }

    fun closeFeed() {
        feedWindow = null
        feedFlow.value = FeedState()
    }

    // ----- refreshing -------------------------------------------------------------------------------------

    private suspend fun refresh() {
        lock.withLock {
            val rt = runningRuntime()
            if (rt == null) {
                readyFlow.value = false
                return
            }
            readyFlow.value = true
            try {
                if (UiPreferences.state.value.cardText == null) UiPreferences.saveCard(appContext, CardCodec.encode(rt.contactCard))
                drainPendingCards(rt)
                contactsFlow.value = loadContacts(rt)
                loadChatList(rt)
                chatWindow?.let { window ->
                    refreshWindow(rt, window)
                    if (chatWindow === window) chatFlow.value = chatStateOf(window)
                }
                feedWindow?.let { window ->
                    refreshWindow(rt, window)
                    if (feedWindow === window) feedFlow.value = feedStateOf(window)
                }
                // Reading may have changed the counters.
                updateUnread(rt)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A failed read leaves the previous state on screen; the next change tries again.
                MeshController.log("messaging: refresh failed (${t.message})")
            }
        }
    }

    private suspend fun loadContacts(rt: NodeRuntime): List<ContactRow> {
        val names = namesFlow.value
        return rt.contacts(CONTACT_LIMIT)
            .filter { it.isKnownPerson(names) }
            .map { info ->
                ContactRow(
                    hex = Hex.encode(info.nodeId),
                    trust = info.trust,
                    hasCard = info.card != null,
                    safetyNumber = info.safetyNumber,
                    lastSeenMs = info.lastSeenMs.toLong(),
                )
            }
    }

    /** Nodes that were merely seen in passing are not contacts: a card, a changed trust or a nickname makes them one. */
    private fun ContactInfo.isKnownPerson(names: Map<String, String>): Boolean =
        card != null || trust != TrustLevel.UNKNOWN || Hex.encode(nodeId) in names

    private suspend fun loadChatList(rt: NodeRuntime) {
        val rows = ArrayList<ChatRow>()
        for (info in rt.conversations(CHAT_LIMIT)) {
            val row = info.toChatRow() ?: continue
            rows += row
        }
        chatsFlow.value = rows
    }

    private fun ConversationInfo.toChatRow(): ChatRow? =
        when (val ref = conversation) {
            is ConversationRef.Direct -> {
                val newest = last
                ChatRow(
                    peerHex = Hex.encode(ref.peer),
                    preview = newest?.body?.let { previewOf(it) },
                    lastTimeMs = newest?.receivedAtMs?.toLong() ?: 0L,
                    lastOutgoing = newest?.direction == MessageDirection.OUTGOING,
                    unread = unread.toIntSaturated(),
                )
            }
            ConversationRef.Announcements -> null
        }

    private fun previewOf(body: ByteArray): String {
        val text = TextSanitizer.clean(String(body, Charsets.UTF_8)).replace('\n', ' ').trim()
        return if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS).trimEnd() + "…"
    }

    private suspend fun updateUnread(rt: NodeRuntime) {
        val counts = rt.unreadCounts()
        unreadFlow.value = Unread(chats = counts.direct.toIntSaturated(), announcements = counts.announcements.toIntSaturated())
    }

    /** Reloads the window over the newest messages (as large as it already is, plus room for new ones) and marks them read. */
    private suspend fun refreshWindow(rt: NodeRuntime, window: Window) {
        val size = (window.entries.size + 8).coerceIn(PAGE, MAX_WINDOW)
        val page = rt.conversationHistory(window.ref, null, size.toUInt())
        window.entries = page.entries
        window.olderBefore = page.olderBeforeSeq
        window.readUpTo = page.readUpToSeq
        if (!window.loaded) {
            window.loaded = true
            // What was unread when the screen opened: the "new messages" line stays there until it closes.
            window.firstUnreadSeq = page.entries.firstOrNull { it.direction == MessageDirection.INCOMING && it.seq > page.readUpToSeq }?.seq
        }
        val hasUnread = page.entries.any { it.direction == MessageDirection.INCOMING && it.seq > window.readUpTo }
        if (hasUnread) window.readUpTo = rt.markConversationRead(window.ref, null)
    }

    private fun chatStateOf(window: Window): ChatState =
        ChatState(
            peerHex = window.peerHex,
            messages = window.entries.map { it.toRow() },
            hasOlder = window.olderBefore != null,
            loading = false,
            firstUnreadSeq = window.firstUnreadSeq?.toLong(),
            loaded = window.loaded,
        )

    private fun feedStateOf(window: Window): FeedState =
        FeedState(
            messages = window.entries.map { it.toRow() }.asReversed(),
            hasOlder = window.olderBefore != null,
            loading = false,
            firstUnreadSeq = window.firstUnreadSeq?.toLong(),
            loaded = window.loaded,
        )

    private fun HistoryItem.toRow(): MessageRow {
        val outgoing = direction == MessageDirection.OUTGOING
        val direct = kind == MessageKind.DIRECT
        val raw = body
        val decoded = if (raw == null) null else if (direct) AnnouncementCodec.Decoded(TextSanitizer.clean(String(raw, Charsets.UTF_8)), Severity.Normal) else AnnouncementCodec.decode(raw)
        return MessageRow(
            seq = seq.toLong(),
            outgoing = outgoing,
            text = decoded?.text,
            timeMs = receivedAtMs.toLong(),
            status = MessageStatus.of(outgoing, direct, deliveredAtMs != null),
            senderHex = if (outgoing) null else counterpart?.let { Hex.encode(it) },
            severity = decoded?.severity ?: Severity.Normal,
        )
    }

    private fun ULong.toIntSaturated(): Int = if (this > Int.MAX_VALUE.toULong()) Int.MAX_VALUE else toInt()

    // ----- paging backwards ----------------------------------------------------------------------------------

    /** Loads the next older page of the open chat. Does nothing when there is none or one is loading. */
    fun loadOlderChat() {
        scope.launch {
            lock.withLock {
                val window = chatWindow ?: return@withLock
                if (loadOlder(window) && chatWindow === window) chatFlow.value = chatStateOf(window)
            }
        }
    }

    /** Loads the next older page of the announcements feed. */
    fun loadOlderFeed() {
        scope.launch {
            lock.withLock {
                val window = feedWindow ?: return@withLock
                if (loadOlder(window) && feedWindow === window) feedFlow.value = feedStateOf(window)
            }
        }
    }

    private suspend fun loadOlder(window: Window): Boolean {
        val before = window.olderBefore ?: return false
        val rt = runningRuntime() ?: return false
        return try {
            val page = rt.conversationHistory(window.ref, before, PAGE.toUInt())
            window.entries = page.entries + window.entries
            window.olderBefore = page.olderBeforeSeq
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            MeshController.log("messaging: older page failed (${t.message})")
            false
        }
    }

    // ----- sending ------------------------------------------------------------------------------------------------

    /** Sends [text] to the contact [peerHex] as a private message. */
    suspend fun sendDirect(peerHex: String, text: String): SendResult {
        val peer = Hex.decode(peerHex) ?: return SendResult.UnknownContact
        val clean = TextSanitizer.clean(text).trim()
        if (clean.isEmpty()) return SendResult.Empty
        if (ByteBudget.utf8Length(clean) > ByteBudget.directLimit(limitsFlow.value.maxPayloadBytes)) return SendResult.TooLong
        return publish { it.publishDirect(peer, clean.toByteArray(Charsets.UTF_8)) }
    }

    /** Publishes a public announcement that phones keep for [lifetimeSecs] seconds. */
    suspend fun publishAnnouncement(text: String, severity: Severity, lifetimeSecs: Long): SendResult {
        val limits = limitsFlow.value
        val clean = TextSanitizer.clean(text).trim()
        if (clean.isEmpty()) return SendResult.Empty
        val payload = AnnouncementCodec.encode(clean, severity)
        if (payload.size > limits.maxPayloadBytes) return SendResult.TooLong
        if (!Lifetimes.isValid(lifetimeSecs, limits.maxLifetimeSecs)) return SendResult.Failed("lifetime out of range")
        return publish { it.publishBroadcast(payload, lifetimeSecs.toUInt()) }
    }

    private suspend fun publish(block: suspend (NodeRuntime) -> PublishOutcome): SendResult {
        val rt = runningRuntime() ?: return SendResult.MeshOff
        return try {
            val outcome = block(rt)
            val rejected = outcome.output.notifications.filterIsInstance<CoreNotification.PublishRejected>().firstOrNull()
            if (rejected != null) SendResult.Rejected(rejected.reason) else SendResult.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            when (val failure = t.toCoreFailure()) {
                CoreFailure.UnknownContact -> SendResult.UnknownContact
                CoreFailure.RuntimeStopped -> SendResult.MeshOff
                else -> SendResult.Failed(failure.toString())
            }
        } finally {
            MeshController.notifyDataChanged()
        }
    }

    // ----- contacts ------------------------------------------------------------------------------------------------

    /**
     * Adds the owner of [card] as a contact and remembers [nickname] for them. When the mesh is off
     * the card is kept and added as soon as the node runs ([AddResult.Queued]).
     */
    suspend fun addContact(card: ByteArray, nickname: String?): AddResult {
        val details =
            try {
                inspectContactCard(card)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return when (t.toCoreFailure()) {
                    CoreFailure.InvalidContactCard -> AddResult.Invalid
                    else -> AddResult.Failed(t.message ?: "card")
                }
            }
        val hex = Hex.encode(details.nodeId)
        val own = MeshController.runtime?.nodeId?.let { Hex.encode(it) }
        if (hex == own) return AddResult.OwnCard
        if (nickname != null) setNickname(hex, nickname)

        val rt = runningRuntime()
        if (rt == null) {
            PendingCards.add(appContext, CardCodec.encode(card))
            return AddResult.Queued(hex)
        }
        return try {
            rt.addContact(card)
            MeshController.notifyDataChanged()
            AddResult.Added(hex)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            when (t.toCoreFailure()) {
                CoreFailure.InvalidContactCard -> AddResult.Invalid
                else -> AddResult.Failed(t.message ?: "add")
            }
        }
    }

    /** Adds the cards that were scanned while the mesh was off. */
    private suspend fun drainPendingCards(rt: NodeRuntime) {
        for (text in PendingCards.all(appContext)) {
            val card = CardCodec.decode(text)
            if (card == null) {
                PendingCards.remove(appContext, text)
                continue
            }
            try {
                rt.addContact(card)
                PendingCards.remove(appContext, text)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A card the core refuses is dropped; any other failure is tried again next time.
                if (t.toCoreFailure() == CoreFailure.InvalidContactCard) PendingCards.remove(appContext, text)
            }
        }
    }

    /** Changes the trust level of a contact. Needs the node, so it fails while the mesh is off. */
    suspend fun setTrust(peerHex: String, trust: TrustLevel): Boolean {
        val rt = runningRuntime() ?: return false
        val peer = Hex.decode(peerHex) ?: return false
        return try {
            rt.setTrust(peer, trust)
            MeshController.notifyDataChanged()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /** Saves a nickname; an empty text removes it. */
    fun setNickname(peerHex: String, nickname: String) {
        val saved = nicknameStore.set(peerHex, nickname)
        namesFlow.update { if (saved == null) it - peerHex else it + (peerHex to saved) }
        MeshController.notifyDataChanged()
    }
}

/** Contact cards scanned while the mesh was off, waiting for the node. Not secret (a card is public). */
private object PendingCards {
    private const val PREFS = "pending_cards"
    private const val KEY = "cards"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<String> = prefs(context).getStringSet(KEY, emptySet())?.toList() ?: emptyList()

    @Synchronized
    fun add(context: Context, card: String) {
        val updated = (prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()) + card
        prefs(context).edit().putStringSet(KEY, updated).apply()
    }

    @Synchronized
    fun remove(context: Context, card: String) {
        val updated = (prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()) - card
        prefs(context).edit().putStringSet(KEY, updated).apply()
    }
}
