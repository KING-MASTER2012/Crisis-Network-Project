package org.crisisnetwork.android.core.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.crisisnetwork.android.core.ble.AdvertiseEvent
import org.crisisnetwork.android.core.ble.BleAdvertiser
import org.crisisnetwork.android.core.ble.BleUuids

/**
 * [BleAdvertiser] over `BluetoothLeAdvertiser`. The advertisement carries only the 128-bit service
 * UUID (flags + UUID = 21 of the 31 bytes); the device name is left out on purpose, both to stay
 * within the packet and because a name would identify the owner. Rotating tokens are phase A3.
 */
@SuppressLint("MissingPermission")
internal class AndroidBleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val mode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
    private val txPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
) : BleAdvertiser {
    private val channel = Channel<AdvertiseEvent>(Channel.UNLIMITED)
    override val events: Flow<AdvertiseEvent> = channel.receiveAsFlow()

    private var advertiser: BluetoothLeAdvertiser? = null

    private val callback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                channel.trySend(AdvertiseEvent.Started)
            }

            override fun onStartFailure(errorCode: Int) {
                channel.trySend(AdvertiseEvent.Failed(errorCode))
            }
        }

    @Synchronized
    override fun start() {
        if (advertiser != null) return
        val leAdvertiser =
            adapter.bluetoothLeAdvertiser
                ?: throw BleUnavailableException("Bluetooth is off or this device cannot advertise")
        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(mode)
                .setTxPowerLevel(txPower)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
                .build()
        leAdvertiser.startAdvertising(settings, data, callback)
        advertiser = leAdvertiser
    }

    @Synchronized
    override fun stop() {
        val running = advertiser ?: return
        advertiser = null
        try {
            running.stopAdvertising(callback)
        } catch (_: Exception) {
            // Bluetooth was switched off or the permission was revoked: advertising is over anyway.
        }
    }
}
