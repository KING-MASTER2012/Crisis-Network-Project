package org.crisisnetwork.android.core.ble

import java.util.UUID

/**
 * The GATT profile of the Crisis Network link. Every device runs both sides of it: it advertises
 * the service and serves it (peripheral), and it scans for the service and connects to it
 * (central). See `docs/src/protocol/ble-link.md`.
 *
 * These values are part of the radio protocol: another implementation (for example iOS) must use
 * the same ones, and changing them splits the network. They are random, not derived from anything.
 */
object BleUuids {
    /** The service every node advertises. */
    val SERVICE: UUID = UUID.fromString("6f1d5a30-7c2e-4b8a-9d41-c3b5e0a7f2d6")

    /** Central -> peripheral. Write without response; one core packet per write. */
    val RX: UUID = UUID.fromString("6f1d5a31-7c2e-4b8a-9d41-c3b5e0a7f2d6")

    /** Peripheral -> central. Notifications; one core packet per notification. */
    val TX: UUID = UUID.fromString("6f1d5a32-7c2e-4b8a-9d41-c3b5e0a7f2d6")

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG, 0x2902). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
