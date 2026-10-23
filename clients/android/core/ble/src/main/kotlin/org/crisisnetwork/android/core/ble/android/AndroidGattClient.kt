package org.crisisnetwork.android.core.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.crisisnetwork.android.core.ble.BleUuids
import org.crisisnetwork.android.core.ble.CloseReason
import org.crisisnetwork.android.core.ble.GattConnector
import org.crisisnetwork.android.core.ble.GattLink
import org.crisisnetwork.android.core.ble.GattLinkEvent
import org.crisisnetwork.android.core.ble.LinkPriority
import org.crisisnetwork.android.core.ble.LinkRole

/** [GattConnector] over `BluetoothDevice.connectGatt`: the central side of the link. */
@SuppressLint("MissingPermission")
internal class AndroidGattConnector(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val cache: ScanDeviceCache,
    private val requestedAttMtu: Int,
) : GattConnector {
    private val context: Context = context.applicationContext

    override fun connect(address: String): GattLink {
        val device = cache.get(address) ?: adapter.getRemoteDevice(address)
        val link = AndroidGattClientLink(context, device, requestedAttMtu)
        link.start()
        return link
    }
}

/**
 * One outgoing GATT connection. It runs the setup in the order that works on all stacks, one GATT
 * operation at a time:
 *
 * 1. connect (no auto-connect, LE transport);
 * 2. discover services, check for our service, RX and TX;
 * 3. negotiate the MTU (skipped when the stack already did, as Android 14 and newer do on connect);
 * 4. enable notifications on TX: `setCharacteristicNotification` plus the CCCD write.
 *
 * Only after step 4 is a [GattLinkEvent.Ready] reported, with the MTU of that moment. Any failure
 * on the way ends the link with [GattLinkEvent.Closed]. A step that never calls back is caught by
 * the transport's handshake timeout, which closes the link.
 *
 * `gatt.close()` is always called when the link ends: every client interface the app leaves open
 * counts against a small system-wide limit and eventually makes new connections fail.
 *
 * Callbacks arrive on Binder threads, possibly before [start] returns; the state is guarded by one
 * lock (stack calls made under it return without waiting for a callback, so it cannot deadlock).
 */
@SuppressLint("MissingPermission")
internal class AndroidGattClientLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val requestedAttMtu: Int,
) : GattLink {
    override val role: LinkRole = LinkRole.Client
    override val address: String = device.address

    private val channel = Channel<GattLinkEvent>(Channel.UNLIMITED)
    override val events: Flow<GattLinkEvent> = channel.receiveAsFlow()

    private enum class Step { Connecting, Discovering, NegotiatingMtu, Subscribing, Ready }

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var step = Step.Connecting
    private var attMtu = DEFAULT_ATT_MTU
    private var mtuKnown = false
    private var closed = false

    fun start() {
        val started = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        synchronized(lock) {
            if (started == null) {
                endLocked(CloseReason.ConnectFailed, null, reportClosed = true)
                return
            }
            if (gatt == null) gatt = started
            if (closed) closeGattLocked()
        }
    }

    override fun send(bytes: ByteArray): Boolean =
        synchronized(lock) {
            val g = gatt
            val characteristic = rx
            if (closed || step != Step.Ready || g == null || characteristic == null) return@synchronized false
            writePacket(g, characteristic, bytes)
        }

    @Suppress("DEPRECATION")
    private fun writePacket(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = bytes
                g.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

    /** Asks for the connection interval class of [priority]; the peripheral cannot ask, so only this side does. */
    override fun requestPriority(priority: LinkPriority) {
        synchronized(lock) {
            val g = gatt
            if (closed || step != Step.Ready || g == null) return
            val platform =
                when (priority) {
                    LinkPriority.LowPower -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                    LinkPriority.Balanced -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    LinkPriority.High -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                }
            try {
                g.requestConnectionPriority(platform)
            } catch (_: SecurityException) {
                // The permission was revoked: the link is about to end anyway.
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            endLocked(CloseReason.Local, null, reportClosed = false)
        }
    }

    // ----- callbacks ---------------------------------------------------------------------------

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                synchronized(lock) {
                    if (closed) return
                    gatt = g
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        step = Step.Discovering
                        if (!g.discoverServices()) endLocked(CloseReason.SetupFailed, null, reportClosed = true)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        val reason = if (step == Step.Ready) CloseReason.Remote else CloseReason.ConnectFailed
                        endLocked(reason, status, reportClosed = true)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                synchronized(lock) {
                    if (closed || step != Step.Discovering) return
                    val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(BleUuids.SERVICE) else null
                    val rxCharacteristic = service?.getCharacteristic(BleUuids.RX)
                    val txCharacteristic = service?.getCharacteristic(BleUuids.TX)
                    if (rxCharacteristic == null || txCharacteristic == null || txCharacteristic.getDescriptor(BleUuids.CCCD) == null) {
                        endLocked(CloseReason.SetupFailed, status, reportClosed = true)
                        return
                    }
                    rx = rxCharacteristic
                    tx = txCharacteristic
                    if (mtuKnown) {
                        subscribeLocked(g)
                    } else {
                        step = Step.NegotiatingMtu
                        if (!g.requestMtu(requestedAttMtu)) endLocked(CloseReason.SetupFailed, null, reportClosed = true)
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                synchronized(lock) {
                    if (closed) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        attMtu = mtu
                        mtuKnown = true
                    }
                    when (step) {
                        // Carry on even if the request failed: the transport rejects a too small MTU itself.
                        Step.NegotiatingMtu -> subscribeLocked(g)
                        Step.Ready -> if (status == BluetoothGatt.GATT_SUCCESS) channel.trySend(GattLinkEvent.MtuChanged(attMtu))
                        else -> Unit
                    }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                synchronized(lock) {
                    if (closed || step != Step.Subscribing || descriptor.uuid != BleUuids.CCCD) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        step = Step.Ready
                        channel.trySend(GattLinkEvent.Ready(attMtu))
                    } else {
                        endLocked(CloseReason.SetupFailed, status, reportClosed = true)
                    }
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                synchronized(lock) {
                    if (closed || step != Step.Ready || characteristic.uuid != BleUuids.RX) return
                    channel.trySend(GattLinkEvent.SendCompleted(status == BluetoothGatt.GATT_SUCCESS))
                }
            }

            // Android 13 and newer.
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                received(characteristic, value)
            }

            // Android 12 and older (this overload is not called on 13+).
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                received(characteristic, value)
            }
        }

    private fun received(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        synchronized(lock) {
            if (closed || step != Step.Ready || characteristic.uuid != BleUuids.TX) return
            channel.trySend(GattLinkEvent.Received(value.copyOf()))
        }
    }

    // ----- helpers (call with the lock held) -----------------------------------------------------

    @Suppress("DEPRECATION")
    private fun subscribeLocked(g: BluetoothGatt) {
        step = Step.Subscribing
        val characteristic = tx
        val cccd = characteristic?.getDescriptor(BleUuids.CCCD)
        if (characteristic == null || cccd == null) {
            endLocked(CloseReason.SetupFailed, null, reportClosed = true)
            return
        }
        val started =
            try {
                if (!g.setCharacteristicNotification(characteristic, true)) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            } catch (_: SecurityException) {
                false
            }
        if (!started) endLocked(CloseReason.SetupFailed, null, reportClosed = true)
    }

    /** Ends the link: releases the GATT client and, when asked, tells the transport. Idempotent. */
    private fun endLocked(reason: CloseReason, status: Int?, reportClosed: Boolean) {
        if (closed) return
        closed = true
        closeGattLocked()
        if (reportClosed) channel.trySend(GattLinkEvent.Closed(reason, status))
        channel.close()
    }

    private fun closeGattLocked() {
        val g = gatt ?: return
        try {
            g.disconnect()
        } catch (_: Exception) {
        }
        try {
            g.close()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val DEFAULT_ATT_MTU = 23
    }
}
