package org.crisisnetwork.android.core.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.crisisnetwork.android.core.ble.BleAdvertisement
import org.crisisnetwork.android.core.ble.BleScanner
import org.crisisnetwork.android.core.ble.BleUuids
import org.crisisnetwork.android.core.ble.ScanEvent
import org.crisisnetwork.android.core.ble.ScanMode

/**
 * [BleScanner] over `BluetoothLeScanner`. Filters on the Crisis Network service UUID, so the
 * platform wakes us only for our own advertisements, and so that the scan is allowed to run, and
 * to be offloaded to the Bluetooth chip, while the screen is off. The permissions are checked by
 * the caller; a missing one makes [start] throw a `SecurityException`.
 *
 * Every sighting carries the token and flag of the advertisement ([BleAdvertisement]) when the
 * packet has them.
 *
 * Android allows five scan starts per 30 s per app. This class does not count them: the
 * transport rations the calls. A second [start] stops the running scan first, so that a mode
 * change (power profile, screen state) is one call, and so that a scan the platform already
 * ended (a failure callback) can be started again.
 */
@SuppressLint("MissingPermission")
internal class AndroidBleScanner(
    private val adapter: BluetoothAdapter,
    private val cache: ScanDeviceCache,
) : BleScanner {
    private val channel = Channel<ScanEvent>(Channel.UNLIMITED)
    override val events: Flow<ScanEvent> = channel.receiveAsFlow()

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    private fun newCallback() =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                report(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { report(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                channel.trySend(ScanEvent.Failed(errorCode))
            }
        }

    private fun report(result: ScanResult) {
        cache.put(result.device)
        val payload = result.scanRecord?.getManufacturerSpecificData(BleAdvertisement.COMPANY_ID)
        channel.trySend(ScanEvent.Sighting(result.device.address, result.rssi, BleAdvertisement.decode(payload)))
    }

    private fun platformMode(mode: ScanMode): Int =
        when (mode) {
            ScanMode.LowPower -> ScanSettings.SCAN_MODE_LOW_POWER
            ScanMode.Balanced -> ScanSettings.SCAN_MODE_BALANCED
            ScanMode.LowLatency -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }

    @Synchronized
    override fun start(mode: ScanMode) {
        stop()
        val leScanner = adapter.bluetoothLeScanner ?: throw BleUnavailableException("Bluetooth is off: there is no LE scanner")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()
        val settings =
            ScanSettings.Builder()
                .setScanMode(platformMode(mode))
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        val newCallback = newCallback()
        leScanner.startScan(listOf(filter), settings, newCallback)
        scanner = leScanner
        callback = newCallback
    }

    @Synchronized
    override fun stop() {
        val running = scanner ?: return
        val active = callback
        scanner = null
        callback = null
        if (active == null) return
        try {
            running.stopScan(active)
        } catch (_: Exception) {
            // Bluetooth was switched off or the permission was revoked: the scan is over anyway.
        }
    }
}
