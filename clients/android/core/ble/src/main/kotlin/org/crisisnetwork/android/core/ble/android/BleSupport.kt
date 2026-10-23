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
 * What stands between the user and a running transport, in the order a screen should resolve it.
 * See [BleSupport.readiness].
 */
sealed interface BleReadiness {
    /** Everything is in place: the transport can be started. */
    data object Ready : BleReadiness

    /** The device has no Bluetooth Low Energy: nothing the user can do. */
    data object NoBle : BleReadiness

    /** Runtime permissions are missing; [permissions] is what to ask for (one dialog on every Android version). */
    data class MissingPermissions(val permissions: List<String>) : BleReadiness

    data object BluetoothOff : BleReadiness

    /** Android 11 and older only: the system location switch is off, so scanning would find nothing. */
    data object LocationSwitchOff : BleReadiness
}

/**
 * Entry points for the Android side: the transport itself and the checks that come before it.
 * [readiness] is the single question a screen asks before it starts the foreground service; the
 * answers are the steps of the permission flow (rationale, request, settings shortcut).
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

    /**
     * The first thing that still has to be dealt with before the transport can start, in this
     * order: no BLE at all, missing runtime permissions, Bluetooth off, location switch off (Android
     * 11 and older). The foreground service of type `connectedDevice` also needs the Bluetooth
     * permissions on Android 14 and newer, so asking for them first is not only polite.
     */
    fun readiness(context: Context, role: BleRole): BleReadiness {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BleReadiness.NoBle
        val missing = missingPermissions(context, role)
        return when {
            missing.isNotEmpty() -> BleReadiness.MissingPermissions(missing)
            !isBluetoothEnabled(context) -> BleReadiness.BluetoothOff
            isScanBlockedByLocationSwitch(context, role) -> BleReadiness.LocationSwitchOff
            else -> BleReadiness.Ready
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
