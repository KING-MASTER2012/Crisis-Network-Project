package org.crisisnetwork.android.core.ble

import android.Manifest
import org.crisisnetwork.android.core.ble.android.BleSupport
import org.junit.Assert.assertEquals
import org.junit.Test

class BleSupportTest {
    @Test
    fun android12AndNewerAsksForScanAdvertiseAndConnectAndNeverForLocation() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BleSupport.requiredPermissions(BleRole.Both, sdkInt = 33),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            BleSupport.requiredPermissions(BleRole.Central, sdkInt = 31),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
            BleSupport.requiredPermissions(BleRole.Peripheral, sdkInt = 34),
        )
    }

    @Test
    fun android11AndOlderNeedsLocationOnlyForScanning() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleSupport.requiredPermissions(BleRole.Both, sdkInt = 30))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleSupport.requiredPermissions(BleRole.Central, sdkInt = 26))
        assertEquals(emptyList<String>(), BleSupport.requiredPermissions(BleRole.Peripheral, sdkInt = 29))
    }
}
