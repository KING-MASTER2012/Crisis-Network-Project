package org.crisisnetwork.android.core.ble.android

import android.bluetooth.BluetoothDevice

/**
 * Remembers the [BluetoothDevice] objects the scanner saw, by address. Connecting with the object
 * from the scan result is more reliable than building one from the address string, because phones
 * advertise with random addresses and the stack knows the address type only from the scan.
 * Bounded: the oldest entries are forgotten.
 */
internal class ScanDeviceCache(private val maxEntries: Int = 64) {
    private val devices =
        object : LinkedHashMap<String, BluetoothDevice>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BluetoothDevice>?): Boolean =
                size > maxEntries
        }

    @Synchronized
    fun put(device: BluetoothDevice) {
        devices[device.address] = device
    }

    @Synchronized
    fun get(address: String): BluetoothDevice? = devices[address]
}
