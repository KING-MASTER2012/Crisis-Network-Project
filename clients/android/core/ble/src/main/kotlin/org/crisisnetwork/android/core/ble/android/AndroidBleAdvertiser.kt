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
import org.crisisnetwork.android.core.ble.AdvertiseMode
import org.crisisnetwork.android.core.ble.BleAdvertisement
import org.crisisnetwork.android.core.ble.BleAdvertiser
import org.crisisnetwork.android.core.ble.BleUuids

/**
 * [BleAdvertiser] over `BluetoothLeAdvertiser`. The advertisement carries the 128-bit service UUID
 * and, when given, the token structure of [BleAdvertisement] as manufacturer data: flags (3) +
 * UUID (18) + manufacturer data (9) = 30 of the 31 bytes. The device name and the transmit power
 * are left out on purpose, to stay within the packet and because a name would identify the owner.
 *
 * Starting again while advertising (a new token, another mode) stops the running advertisement
 * first; every start uses a fresh callback object, so a late callback of the old advertisement
 * cannot be mistaken for the new one's.
 */
@SuppressLint("MissingPermission")
internal class AndroidBleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val txPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
) : BleAdvertiser {
    private val channel = Channel<AdvertiseEvent>(Channel.UNLIMITED)
    override val events: Flow<AdvertiseEvent> = channel.receiveAsFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    private fun newCallback() =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                channel.trySend(AdvertiseEvent.Started)
            }

            override fun onStartFailure(errorCode: Int) {
                channel.trySend(AdvertiseEvent.Failed(errorCode))
            }
        }

    private fun platformMode(mode: AdvertiseMode): Int =
        when (mode) {
            AdvertiseMode.LowPower -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            AdvertiseMode.Balanced -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            AdvertiseMode.LowLatency -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        }

    @Synchronized
    override fun start(mode: AdvertiseMode, advertisement: BleAdvertisement?) {
        stop()
        val leAdvertiser =
            adapter.bluetoothLeAdvertiser
                ?: throw BleUnavailableException("Bluetooth is off or this device cannot advertise")
        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(platformMode(mode))
                .setTxPowerLevel(txPower)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
                .apply {
                    if (advertisement != null) addManufacturerData(BleAdvertisement.COMPANY_ID, advertisement.encode())
                }
                .build()
        val newCallback = newCallback()
        leAdvertiser.startAdvertising(settings, data, newCallback)
        advertiser = leAdvertiser
        callback = newCallback
    }

    @Synchronized
    override fun stop() {
        val running = advertiser ?: return
        val active = callback
        advertiser = null
        callback = null
        if (active == null) return
        try {
            running.stopAdvertising(active)
        } catch (_: Exception) {
            // Bluetooth was switched off or the permission was revoked: advertising is over anyway.
        }
    }
}
