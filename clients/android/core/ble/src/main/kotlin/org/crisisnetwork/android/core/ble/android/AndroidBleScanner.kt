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
import org.crisisnetwork.android.core.ble.BleScanner
import org.crisisnetwork.android.core.ble.BleUuids
import org.crisisnetwork.android.core.ble.ScanEvent

/**
 * [BleScanner] over `BluetoothLeScanner`. Filters on the Crisis Network service UUID, so the
 * platform wakes us only for our own advertisements (and so that a screen-off scan, phase A3, is
 * allowed to run at all). The permissions are checked by the caller; a missing one makes [start]
 * throw a `SecurityException`.
 *
 * Android throttles scan starts (five per 30 s per app); this class starts one scan and keeps it.
 * Restarts, duty cycles and power profiles are phase A3.
 */
@SuppressLint("MissingPermission")
internal class AndroidBleScanner(
    private val adapter: BluetoothAdapter,
    private val cache: ScanDeviceCache,
    private val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
) : BleScanner {
    private val channel = Channel<ScanEvent>(Channel.UNLIMITED)
    override val events: Flow<ScanEvent> = channel.receiveAsFlow()

    private var scanner: BluetoothLeScanner? = null

    private val callback =
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
        channel.trySend(ScanEvent.Sighting(result.device.address, result.rssi))
    }

    @Synchronized
    override fun start() {
        if (scanner != null) return
        val leScanner = adapter.bluetoothLeScanner ?: throw BleUnavailableException("Bluetooth is off: there is no LE scanner")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()
        val settings =
            ScanSettings.Builder()
                .setScanMode(scanMode)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        leScanner.startScan(listOf(filter), settings, callback)
        scanner = leScanner
    }

    @Synchronized
    override fun stop() {
        val running = scanner ?: return
        scanner = null
        try {
            running.stopScan(callback)
        } catch (_: Exception) {
            // Bluetooth was switched off or the permission was revoked: the scan is over anyway.
        }
    }
}
