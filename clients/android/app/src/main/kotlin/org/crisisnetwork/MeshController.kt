package org.crisisnetwork.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.crisisnetwork.android.core.ble.BleConfig
import org.crisisnetwork.android.core.ble.BleDiagnostic
import org.crisisnetwork.android.core.ble.BleRole
import org.crisisnetwork.android.core.ble.BleStats
import org.crisisnetwork.android.core.ble.BleTransport
import org.crisisnetwork.android.core.ble.LinkInfo
import org.crisisnetwork.android.core.ble.PowerProfile
import org.crisisnetwork.android.core.ble.android.BleSupport
import org.crisisnetwork.android.core.runtime.EncryptionPolicy
import org.crisisnetwork.android.core.runtime.NodeBootstrap
import org.crisisnetwork.android.core.runtime.NodeRuntime
import org.crisisnetwork.android.core.runtime.OpenResult
import org.crisisnetwork.android.messaging.Lifetimes
import org.crisisnetwork.android.settings.DefaultLifetimes
import org.crisisnetwork.android.settings.StorageLimits
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.NodeSettings
import uniffi.crisis_core.coreInfo
import uniffi.crisis_core.defaultSettings

/**
 * Owns the one node of the app process and its BLE transport (phase A3). [MeshService] keeps the
 * process alive in the foreground; this object does the work and publishes [state], so that screens
 * and the service's notification only observe it.
 *
 * Lifecycle: [start] opens the node, starts the transport and the observers; [stop] ends them.
 * Neither blocks. A failure while starting leaves the state `Stopped` with [State.error] set.
 *
 * The power profile, the role, the size of the message store and the default lifetime are
 * remembered across launches (`SharedPreferences`, no secrets). The core takes the store size and
 * the lifetime when the node is opened, so a new choice applies the next time the mesh starts
 * ([State.settingsPending] says that a running node still has the old values). The screen state is tracked with a receiver that exists only while the node runs, and
 * is handed to the transport, which scans less aggressively while the screen is off.
 */
object MeshController {
    enum class Status { Stopped, Starting, Running, Stopping }

    data class State(
        val status: Status = Status.Stopped,
        val role: BleRole = BleRole.Both,
        val profile: PowerProfile = PowerProfile.Balanced,
        val screenOn: Boolean = true,
        val error: String? = null,
        val safetyNumber: String? = null,
        val links: List<LinkInfo> = emptyList(),
        val stats: BleStats? = null,
        val log: List<String> = emptyList(),
        /** Size of the message store the user chose, in megabytes (applies when the node is opened). */
        val storeLimitMb: Int = StorageLimits.DEFAULT_MB,
        /** Lifetime of announcements unless another one is chosen, in seconds (applies when the node is opened). */
        val defaultLifetimeSecs: Long = Lifetimes.DEFAULT_SECS,
        /** What the running node was opened with, `null` while it does not run. */
        val openedWith: OpenedSettings? = null,
    ) {
        /** Neighbours that are connected and usable. */
        val activeLinks: Int get() = links.count { it.active }

        /** `true` while the node runs with values that differ from the ones chosen since. */
        val settingsPending: Boolean
            get() = openedWith != null && openedWith != OpenedSettings(storeLimitMb, defaultLifetimeSecs)
    }

    /** The settings the core only reads when the node is opened. */
    data class OpenedSettings(val storeLimitMb: Int, val defaultLifetimeSecs: Long)

    private const val PREFS = "mesh"
    private const val KEY_PROFILE = "profile"
    private const val KEY_ROLE = "role"
    private const val KEY_WANTED = "wanted"
    private const val KEY_STORE_MB = "store_mb"
    private const val KEY_LIFETIME = "default_lifetime_secs"

    /** Pause between connection attempts: Android stacks fail (status 133) when they are started in a burst. */
    private const val CONNECT_SPACING_MS = 300L
    private const val POLL_SCREEN_ON_MS = 1_000L
    private const val POLL_SCREEN_OFF_MS = 10_000L
    private const val MAX_LOG = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(State())
    private val notificationFlow = MutableSharedFlow<CoreNotification>(extraBufferCapacity = 64)

    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutableDataVersion = MutableStateFlow(0L)

    /**
     * Changes whenever something happened to the messages (one arrived, was published, refused or
     * confirmed as delivered). Unlike [notifications] it is a state: a screen that appears later
     * still sees that something changed, and reads the messages from the core.
     */
    val dataVersion: StateFlow<Long> = mutableDataVersion.asStateFlow()

    /** Tells the observers of [dataVersion] that messages changed (also used after the user's own actions). */
    fun notifyDataChanged() {
        mutableDataVersion.update { it + 1 }
    }

    /** The core's notifications, forwarded while the node runs. */
    val notifications: SharedFlow<CoreNotification> = notificationFlow.asSharedFlow()

    @Volatile
    var runtime: NodeRuntime? = null
        private set

    @Volatile
    private var transport: BleTransport? = null

    @Volatile
    private var stopRequested = false

    private var loaded = false
    private var appContext: Context? = null
    private var jobs: List<Job> = emptyList()

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                transport?.setScreenOn(on)
                mutableState.update { it.copy(screenOn = on) }
            }
        }

    // ----- settings -----------------------------------------------------------------------------

    /** Reads the remembered profile and role. Safe to call often. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profile = prefs.getString(KEY_PROFILE, null)?.let { name -> PowerProfile.entries.firstOrNull { it.name == name } }
        val role = prefs.getString(KEY_ROLE, null)?.let { name -> BleRole.entries.firstOrNull { it.name == name } }
        val storeMb = StorageLimits.coerce(prefs.getInt(KEY_STORE_MB, StorageLimits.DEFAULT_MB))
        val lifetime = DefaultLifetimes.coerce(prefs.getLong(KEY_LIFETIME, Lifetimes.DEFAULT_SECS))
        mutableState.update {
            it.copy(profile = profile ?: it.profile, role = role ?: it.role, storeLimitMb = storeMb, defaultLifetimeSecs = lifetime)
        }
    }

    /** Takes effect at once, also while the node runs. */
    fun setProfile(context: Context, profile: PowerProfile) {
        load(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILE, profile.name).apply()
        mutableState.update { it.copy(profile = profile) }
        transport?.setPowerProfile(profile)
    }

    /** Only while stopped: the role decides which radio sides start. */
    fun setRole(context: Context, role: BleRole) {
        load(context)
        if (mutableState.value.status != Status.Stopped) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROLE, role.name).apply()
        mutableState.update { it.copy(role = role) }
    }

    /** Remembers the size of the message store. The core reads it when the node is opened: it applies from the next start. */
    fun setStorageLimit(context: Context, megabytes: Int) {
        load(context)
        val value = StorageLimits.coerce(megabytes)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_STORE_MB, value).apply()
        mutableState.update { it.copy(storeLimitMb = value) }
    }

    /** Remembers the lifetime of new announcements. Used by the composer at once and by the core from the next start. */
    fun setDefaultLifetime(context: Context, seconds: Long) {
        load(context)
        val value = DefaultLifetimes.coerce(seconds)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LIFETIME, value).apply()
        mutableState.update { it.copy(defaultLifetimeSecs = value) }
    }

    /** Whether the user wants the mesh running; the service uses it to decide about a restart by the system. */
    fun wantsToRun(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    fun setWanted(context: Context, wanted: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    /** Zeroes the throughput counters of all links (for a measurement run). */
    fun resetLinkMetrics() {
        transport?.resetMetrics()
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    fun log(line: String) {
        mutableState.update { it.copy(log = (listOf(line) + it.log).take(MAX_LOG)) }
    }

    // ----- lifecycle ----------------------------------------------------------------------------

    /**
     * Opens the node and starts Bluetooth. The state is `Starting` when this returns, `Running` or
     * `Stopped` (with an error) a moment later. The permissions must be granted already.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        load(app)
        synchronized(this) {
            if (mutableState.value.status != Status.Stopped) return
            stopRequested = false
            appContext = app
            mutableState.update { it.copy(status = Status.Starting, error = null, links = emptyList(), stats = null) }
        }
        scope.launch {
            try {
                open(app)
            } catch (t: Throwable) {
                fail("${t::class.simpleName}: ${t.message}")
            }
        }
    }

    private suspend fun open(app: Context) {
        val snapshot = mutableState.value
        val config =
            BleConfig(
                role = snapshot.role,
                minLinkMtu = coreInfo().minLinkMtu.toInt(),
                profile = snapshot.profile,
                connectSpacingMs = CONNECT_SPACING_MS,
            )
        val ble = BleSupport.createTransport(app, config)
        val node =
            when (val opened = NodeBootstrap.open(NodeBootstrap.defaultRoot(app), ble, EncryptionPolicy.forApp(app), nodeSettings(snapshot))) {
                is OpenResult.Ready -> opened.runtime
                is OpenResult.Failed -> {
                    fail("Could not open the node: ${opened.failure}")
                    return
                }
            }
        try {
            node.start()
        } catch (t: Throwable) {
            runCatching { node.stop() }
            fail("Could not start Bluetooth: ${t.message}")
            return
        }
        if (stopRequested) {
            runCatching { node.stop() }
            mutableState.update { it.copy(status = Status.Stopped) }
            return
        }
        val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        ble.setScreenOn(screenOn)
        registerScreenReceiver(app)
        runtime = node
        transport = ble
        mutableState.update {
            it.copy(
                status = Status.Running,
                screenOn = screenOn,
                safetyNumber = node.safetyNumber,
                openedWith = OpenedSettings(snapshot.storeLimitMb, snapshot.defaultLifetimeSecs),
                log = listOf("started as ${it.role}, ${it.profile}"),
            )
        }
        observe(node, ble)
    }

    /**
     * The core's settings for this start: the defaults of the core with the store size and the
     * lifetime the user chose. The part of the store that others may pin keeps its share.
     */
    private fun nodeSettings(snapshot: State): NodeSettings {
        val base = defaultSettings()
        return NodeSettings(
            defaultLifetimeSecs = snapshot.defaultLifetimeSecs.toUInt(),
            maxStoreBytes = StorageLimits.bytesOf(snapshot.storeLimitMb).toULong(),
            maxInboundPinnedBytes = StorageLimits.inboundPinnedBytes(snapshot.storeLimitMb).toULong(),
            maxPeers = base.maxPeers,
        )
    }

    /** Stops the node and the transport. Idempotent; also cancels a start that is still under way. */
    fun stop() {
        var node: NodeRuntime? = null
        synchronized(this) {
            when (mutableState.value.status) {
                Status.Stopped, Status.Stopping -> return
                Status.Starting -> {
                    // open() sees the flag and ends the start by itself.
                    stopRequested = true
                    return
                }
                Status.Running -> {
                    node = runtime
                    mutableState.update { it.copy(status = Status.Stopping) }
                }
            }
        }
        scope.launch {
            appContext?.let { unregisterScreenReceiver(it) }
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            node?.let { runCatching { it.stop() } }
            runtime = null
            transport = null
            mutableState.update {
                it.copy(
                    status = Status.Stopped,
                    links = emptyList(),
                    stats = null,
                    openedWith = null,
                    log = (listOf("stopped") + it.log).take(MAX_LOG),
                )
            }
        }
    }

    private fun fail(message: String) {
        runtime = null
        transport = null
        appContext?.let { unregisterScreenReceiver(it) }
        mutableState.update { it.copy(status = Status.Stopped, error = message, openedWith = null) }
    }

    private fun registerScreenReceiver(app: Context) {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterScreenReceiver(app: Context) {
        try {
            app.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // It was not registered (a start that failed early).
        }
    }

    // ----- observers --------------------------------------------------------------------------------

    private fun observe(node: NodeRuntime, ble: BleTransport) {
        jobs =
            listOf(
                scope.launch {
                    node.notifications.collect { note ->
                        notificationFlow.tryEmit(note)
                        when (note) {
                            is CoreNotification.MessageReceived,
                            is CoreNotification.Delivered,
                            is CoreNotification.Published,
                            -> notifyDataChanged()
                            is CoreNotification.PeerConnected -> log("core: peer ${note.peer} accepted")
                            is CoreNotification.PeerDisconnected -> log("core: peer ${note.peer} gone")
                            is CoreNotification.PeerBanned -> log("core: peer ${note.peer} BANNED")
                            is CoreNotification.PublishRejected -> {
                                log("core: publish rejected (${note.reason})")
                                notifyDataChanged()
                            }
                            else -> Unit
                        }
                    }
                },
                scope.launch { ble.diagnostics.collect { log("ble: ${describe(it)}") } },
                scope.launch { node.failures.collect { log("failure: $it") } },
                scope.launch {
                    while (isActive) {
                        val links = ble.linkInfo()
                        val stats = ble.stats()
                        mutableState.update { it.copy(links = links, stats = stats) }
                        // Nobody looks at the numbers with the screen off: poll rarely, save the wake-ups.
                        delay(if (mutableState.value.screenOn) POLL_SCREEN_ON_MS else POLL_SCREEN_OFF_MS)
                    }
                },
            )
    }

    private fun describe(d: BleDiagnostic): String =
        when (d) {
            is BleDiagnostic.LinkUp -> "link up ${d.address} ${d.role} handle=${d.handle} mtu=${d.payloadMtu}"
            is BleDiagnostic.LinkDown -> "link down ${d.address} ${d.role} handle=${d.handle}: ${d.reason}"
            else -> d.toString()
        }
}
