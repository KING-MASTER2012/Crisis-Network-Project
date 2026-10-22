package org.crisisnetwork.android.core.ble.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import org.crisisnetwork.android.core.ble.BleConfig
import org.crisisnetwork.android.core.ble.BleRole
import org.crisisnetwork.android.core.ble.BleTransport

/**
 * Entry points for the Android side: the transport itself and the checks that come before it.
 * The real permission flow (rationales, Android 12+ and older, settings shortcuts) is phase A3;
 * this is the minimum that lets a screen start the transport.
 */
object BleSupport {
    /**
     * The runtime permissions the [role] needs. Android 12 and newer: scan, advertise and connect
     * (no location, the manifest declares `neverForLocation`). Android 11 and older: location, for
     * scanning only; the classic Bluetooth permissions are install-time there.
     */
    fun requiredPermissions(role: BleRole, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val permissions = mutableListOf<String>()
        if (sdkInt >= Build.VERSION_CODES.S) {
            if (role.central) permissions += Manifest.permission.BLUETOOTH_SCAN
            if (role.peripheral) permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else if (role.central) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions
    }

    fun missingPermissions(context: Context, role: BleRole): List<String> =
        requiredPermissions(role).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /**
     * Android 11 and older return no scan results at all while the system location switch is off,
     * even with the permission granted. Always `false` on Android 12 and newer.
     */
    fun isScanBlockedByLocationSwitch(context: Context, role: BleRole): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !role.central) return false
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !locationManager.isLocationEnabled
        } else {
            !(
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * Builds the transport over the real Bluetooth stack. Throws [BleUnavailableException] when the
     * device has no BLE or Bluetooth is off. The permissions must be granted before the transport
     * is started (not before it is built).
     */
    fun createTransport(context: Context, config: BleConfig = BleConfig()): BleTransport {
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw BleUnavailableException("this device has no Bluetooth Low Energy")
        }
        val manager =
            app.getSystemService(BluetoothManager::class.java)
                ?: throw BleUnavailableException("this device has no Bluetooth")
        val adapter = manager.adapter ?: throw BleUnavailableException("this device has no Bluetooth")
        if (!adapter.isEnabled) throw BleUnavailableException("Bluetooth is switched off")

        val cache = ScanDeviceCache()
        return BleTransport(
            scanner = AndroidBleScanner(adapter, cache),
            advertiser = AndroidBleAdvertiser(adapter),
            connector = AndroidGattConnector(app, adapter, cache, config.requestedAttMtu),
            server = AndroidGattServer(app, manager),
            config = config,
        )
    }
}
