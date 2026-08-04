package com.indigo.mobileobservatory.permissions

import android.Manifest

/** Runtime camera permission set used by phone sky capture (M0+). */
object CameraPermissionPolicy {
    fun requiredPermissions(): List<String> = listOf(Manifest.permission.CAMERA)

    fun isGranted(grantedPermissions: Set<String>): Boolean =
        requiredPermissions().all(grantedPermissions::contains)
}
