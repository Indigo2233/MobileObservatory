package com.indigo.mobileobservatory.permissions

import android.Manifest
import android.os.Build

/** Android-version Bluetooth permission matrix, independent from Activity launchers. */
object BluetoothPermissionPolicy {
    fun requiredPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptyList()
        }

    fun isGranted(sdkInt: Int, grantedPermissions: Set<String>): Boolean =
        requiredPermissions(sdkInt).all(grantedPermissions::contains)
}
