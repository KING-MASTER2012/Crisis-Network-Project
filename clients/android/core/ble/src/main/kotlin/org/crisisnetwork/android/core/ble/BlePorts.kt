package org.crisisnetwork.android.core.ble

import kotlinx.coroutines.flow.Flow

/*
 * The ports of the BLE transport. Everything that touches the Bluetooth stack sits behind these
 * interfaces, so that BleTransport (the state machine) can be tested on the JVM with fakes. The
 * implementations over the Android APIs live in the `android` package.
 *
 * Contract for implementations:
 *
 * * Methods must not block for long and must tolerate being called from any thread.
 * * Events may be produced from any thread (Binder threads on Android).
 * * Event flows are collected by exactly one collector, the transport, which starts collecting
 *   slightly after it created the object or called `start()`. Implementations therefore buffer
 *   (an unlimited `Channel` exposed with `receiveAsFlow()`) instead of dropping what happens first.
 * * `stop()`, `close()` and `GattLink.close()` are idempotent, and `stop()` / `close()` may be
 *   called on something that was never started.
 */

/** What a [BleScanner] reports. */
sealed interface ScanEvent {
    /** A device advertising the service was seen. May repeat for the same device. */
    data class Sighting(
        val address: String,
        val rssi: Int,
        /** The token and flag of the advertisement, `null` when it carries none (see [BleAdvertisement]). */
        val advertisement: BleAdvertisement? = null,
    ) : ScanEvent

    /** The platform stopped the scan or refused to start it. [code] is the platform's error code. */
    data class Failed(val code: Int) : ScanEvent
}

interface BleScanner {
    val events: Flow<ScanEvent>

    /**
     * Starts a scan filtered on [BleUuids.SERVICE] in [mode]. Throws when Bluetooth is off or a
     * permission is missing. Called again while a scan runs, it restarts the scan in the new mode.
     */
    fun start(mode: ScanMode)

    fun stop()
}

/** What a [BleAdvertiser] reports. */
sealed interface AdvertiseEvent {
    data object Started : AdvertiseEvent

    data class Failed(val code: Int) : AdvertiseEvent
}

interface BleAdvertiser {
    val events: Flow<AdvertiseEvent>

    /**
     * Starts advertising [BleUuids.SERVICE] as connectable, with [advertisement] (when not `null`)
     * as manufacturer data. Throws when Bluetooth is off or a permission is missing. Called again
     * while advertising, it restarts with the new mode and data (this is how the token rotates).
     */
    fun start(mode: AdvertiseMode, advertisement: BleAdvertisement?)

    fun stop()
}

/** What a [GattServerPort] reports besides new links. */
sealed interface ServerEvent {
    /** The service is registered and can be used. */
    data object Ready : ServerEvent

    data class Failed(val status: Int) : ServerEvent
}

/** The peripheral side: hosts the GATT service and hands out one [GattLink] per connecting central. */
interface GattServerPort {
    /** A central connected to us. The link is usable only after it reports [GattLinkEvent.Ready]. */
    val incoming: Flow<GattLink>

    val events: Flow<ServerEvent>

    /** Opens the GATT server and registers the service. Throws when Bluetooth is off or a permission is missing. */
    fun open()

    /** Closes the server and every link it handed out. */
    fun close()
}

/** The central side: opens outgoing connections. */
interface GattConnector {
    /**
     * Starts connecting to [address] and returns the link at once; the outcome arrives as
     * [GattLinkEvent]s (Ready, or Closed when it failed).
     */
    fun connect(address: String): GattLink
}

enum class LinkRole {
    /** We connected to the remote device (we are the GATT client, we write and receive notifications). */
    Client,

    /** The remote device connected to us (we are the GATT server, we notify and receive writes). */
    Server,
}

/** Why a link ended. */
enum class CloseReason {
    /** [GattLink.close] was called. */
    Local,

    /** The remote device disconnected or went out of range. */
    Remote,

    /** The connection could not be established (for example GATT status 133). */
    ConnectFailed,

    /** The remote device does not offer the service, or something in the setup went wrong. */
    SetupFailed,
}

/** What a [GattLink] reports. */
sealed interface GattLinkEvent {
    /**
     * The link is usable: the services were found, the notifications are enabled (client) or the
     * remote device subscribed (server). [attMtu] is the ATT MTU known at that moment.
     */
    data class Ready(val attMtu: Int) : GattLinkEvent

    /** The ATT MTU changed after (or while) the link became ready. */
    data class MtuChanged(val attMtu: Int) : GattLinkEvent

    /** One packet arrived. */
    class Received(val bytes: ByteArray) : GattLinkEvent

    /** The send started by [GattLink.send] finished. */
    data class SendCompleted(val success: Boolean) : GattLinkEvent

    /** The link is gone. Reported once; no event follows it. [status] is the platform's status, if any. */
    data class Closed(val reason: CloseReason, val status: Int? = null) : GattLinkEvent
}

/**
 * One GATT connection, in either role. A link carries one core packet per write or notification;
 * there is no framing on top, because the core already fits every packet to the MTU.
 */
interface GattLink {
    val role: LinkRole

    /** The remote device's Bluetooth address (random and rotating for most phones). */
    val address: String

    val events: Flow<GattLinkEvent>

    /**
     * Starts sending one packet. Returns `false` when the platform refuses it right now (busy,
     * not ready, closed). Only one send may be in progress: the next one is started after
     * [GattLinkEvent.SendCompleted].
     */
    fun send(bytes: ByteArray): Boolean

    /** Asks for the connection interval class of [priority]. Only a client link can; the default does nothing. */
    fun requestPriority(priority: LinkPriority) {}

    /** Disconnects and releases the link. Idempotent. After it no event is delivered any more. */
    fun close()
}
