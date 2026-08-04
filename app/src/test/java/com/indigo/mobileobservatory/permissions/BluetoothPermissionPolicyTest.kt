package com.indigo.mobileobservatory.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionPolicyTest {
    @Test
    fun preAndroid12NeedsNoRuntimeBluetoothPermission() {
        assertTrue(BluetoothPermissionPolicy.requiredPermissions(30).isEmpty())
        assertTrue(BluetoothPermissionPolicy.isGranted(30, emptySet()))
    }

    @Test
    fun android12RequiresScanAndConnect() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            BluetoothPermissionPolicy.requiredPermissions(31).toSet()
        )
    }

    @Test
    fun partialAndroid12GrantIsRejected() {
        assertFalse(
            BluetoothPermissionPolicy.isGranted(
                31,
                setOf(Manifest.permission.BLUETOOTH_CONNECT)
            )
        )
    }

    @Test
    fun completeAndroid12GrantIsAccepted() {
        assertTrue(
            BluetoothPermissionPolicy.isGranted(
                31,
                setOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        )
    }
}
