package org.crisisnetwork.android.core.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.crisisnetwork.android.core.ble.BleUuids
import org.crisisnetwork.android.core.ble.CloseReason
import org.crisisnetwork.android.core.ble.GattLink
import org.crisisnetwork.android.core.ble.GattLinkEvent
import org.crisisnetwork.android.core.ble.GattServerPort
import org.crisisnetwork.android.core.ble.LinkRole
import org.crisisnetwork.android.core.ble.ServerEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GattServerPort] over `BluetoothGattServer`: the peripheral side of the link.
 *
 * The service has two characteristics: RX (write without response, central to peripheral) and TX
 * (notify, peripheral to central) with a CCCD. A central connecting to us becomes a [GattLink];
 * it is *ready* once it subscribed to TX, which our own central side does only after the MTU was
 * negotiated, so the MTU is already known by then. A peripheral cannot ask for a larger MTU, it
 * only learns the one the central chose.
 *
 * All callbacks arrive on Binder threads; they only touch thread-safe state and push events into
 * channels.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattServer(
    context: Context,
    private val manager: BluetoothManager,
) : GattServerPort {
    private val context: Context = context.applicationContext
    private val incomingChannel = Channel<GattLink>(Channel.UNLIMITED)
    private val eventChannel = Channel<ServerEvent>(Channel.UNLIMITED)

    override val incoming: Flow<GattLink> = incomingChannel.receiveAsFlow()
    override val events: Flow<ServerEvent> = eventChannel.receiveAsFlow()

    private val links = ConcurrentHashMap<String, ServerLink>()
    private val notifyLock = Any()

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                eventChannel.trySend(if (status == BluetoothGatt.GATT_SUCCESS) ServerEvent.Ready else ServerEvent.Failed(status))
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        val link = ServerLink(device)
                        links.put(device.address, link)?.remoteGone(status) // a stale link of the same device
                        incomingChannel.trySend(link)
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS ->
                        links.remove(device.address)?.remoteGone(status)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                links[device.address]?.onMtu(mtu)
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val value =
                    if (links[device.address]?.subscribed == true) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                val ok = descriptor.uuid == BleUuids.CCCD && offset in 0..value.size
                respond(
                    device,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    if (ok) value.copyOfRange(offset, value.size) else null,
                )
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val link = links[device.address]
                var status = BluetoothGatt.GATT_SUCCESS
                val isCccd = descriptor.uuid == BleUuids.CCCD && descriptor.characteristic?.uuid == BleUuids.TX
                if (!isCccd || link == null || preparedWrite || offset != 0 || value == null) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                } else if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    link.onSubscribed()
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    link.onUnsubscribed()
                } else {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
                if (responseNeeded) respond(device, requestId, status, offset, null)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val link = links[device.address]
                val accepted = characteristic.uuid == BleUuids.RX && link != null && !preparedWrite && offset == 0 && value != null
                if (accepted) link?.onPacket(value!!.copyOf())
                if (responseNeeded) {
                    respond(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null,
                    )
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                links[device.address]?.onNotificationSent(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

    @Synchronized
    override fun open() {
        if (gattServer != null) return
        val server =
            manager.openGattServer(context, callback)
                ?: throw BleUnavailableException("could not open the GATT server")

        val tx =
            BluetoothGattCharacteristic(BleUuids.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        tx.addDescriptor(
            BluetoothGattDescriptor(
                BleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val rx =
            BluetoothGattCharacteristic(
                BleUuids.RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)

        txCharacteristic = tx
        gattServer = server
        if (!server.addService(service)) {
            gattServer = null
            txCharacteristic = null
            runCatching { server.close() }
            throw BleUnavailableException("could not register the GATT service")
        }
    }

    @Synchronized
    override fun close() {
        val server = gattServer
        gattServer = null
        val all = links.values.toList()
        links.clear()
        all.forEach { it.close() }
        if (server != null) runCatching { server.close() }
        incomingChannel.close()
        eventChannel.close()
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (_: Exception) {
            // The device is gone or the permission was revoked; the disconnect callback follows.
        }
    }

    /** Sends one notification. Legacy API before Android 13 shares the characteristic object, hence the lock. */
    @Suppress("DEPRECATION")
    private fun notifyPacket(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        val tx = txCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, tx, false, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                synchronized(notifyLock) {
                    tx.value = bytes
                    server.notifyCharacteristicChanged(device, tx, false)
                }
            }
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    /** One central connected to us. */
    private inner class ServerLink(private val device: BluetoothDevice) : GattLink {
        override val role: LinkRole = LinkRole.Server
        override val address: String = device.address

        private val channel = Channel<GattLinkEvent>(Channel.UNLIMITED)
        override val events: Flow<GattLinkEvent> = channel.receiveAsFlow()

        @Volatile
        private var attMtu = DEFAULT_ATT_MTU

        @Volatile
        var subscribed = false
            private set

        private val closed = AtomicBoolean(false)

        fun onSubscribed() {
            if (closed.get() || subscribed) return
            subscribed = true
            channel.trySend(GattLinkEvent.Ready(attMtu))
        }

        fun onUnsubscribed() {
            // A central that unsubscribes has no use for the link any more.
            if (finish(CloseReason.Remote, null)) {
                try {
                    gattServer?.cancelConnection(device)
                } catch (_: Exception) {
                }
            }
        }

        fun onMtu(mtu: Int) {
            attMtu = mtu
            if (subscribed && !closed.get()) channel.trySend(GattLinkEvent.MtuChanged(mtu))
        }

        fun onPacket(bytes: ByteArray) {
            if (subscribed && !closed.get()) channel.trySend(GattLinkEvent.Received(bytes))
        }

        fun onNotificationSent(success: Boolean) {
            if (!closed.get()) channel.trySend(GattLinkEvent.SendCompleted(success))
        }

        fun remoteGone(status: Int) {
            finish(CloseReason.Remote, status)
        }

        override fun send(bytes: ByteArray): Boolean = subscribed && !closed.get() && notifyPacket(device, bytes)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            links.remove(address, this)
            try {
                gattServer?.cancelConnection(device)
            } catch (_: Exception) {
            }
            channel.close()
        }

        /** Ends the link because of the remote side: reports it once. Returns whether this call ended it. */
        private fun finish(reason: CloseReason, status: Int?): Boolean {
            if (!closed.compareAndSet(false, true)) return false
            links.remove(address, this)
            channel.trySend(GattLinkEvent.Closed(reason, status))
            channel.close()
            return true
        }
    }

    private companion object {
        const val DEFAULT_ATT_MTU = 23
    }
}
