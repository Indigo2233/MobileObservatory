package com.indigo.mobileobservatory.permissions

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionPolicyTest {
    @Test
    fun requiresCameraPermission() {
        assertTrue(CameraPermissionPolicy.requiredPermissions().contains(Manifest.permission.CAMERA))
        assertTrue(CameraPermissionPolicy.isGranted(setOf(Manifest.permission.CAMERA)))
        assertFalse(CameraPermissionPolicy.isGranted(emptySet()))
    }
}
