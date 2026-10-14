package com.indigo.mobileobservatory.camera

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CameraReferenceIdentityTest {
    @Test fun anonymousReplacementHasAnotherIdentityEvenWithTheSameModelAndUsbLocation() {
        val first = camera("")
        val replacement = camera("")
        assertEquals(CameraReferenceIdentity.of(first), CameraReferenceIdentity.of(first))
        assertNotEquals(CameraReferenceIdentity.of(first), CameraReferenceIdentity.of(replacement))
    }

    @Test fun aFactorySerialAllowsTheSamePhysicalCameraToReconnect() {
        assertEquals(CameraReferenceIdentity.of(camera("SN-123")), CameraReferenceIdentity.of(camera("SN-123")))
        assertNotEquals(CameraReferenceIdentity.of(camera("SN-123")), CameraReferenceIdentity.of(camera("SN-456")))
    }

    private fun camera(serial: String): Camera = Proxy.newProxyInstance(Camera::class.java.classLoader,
        arrayOf(Camera::class.java)) { proxy, method, args ->
        when (method.name) {
            "getCameraInfo" -> CameraInfo("same-model", serial, 1000, 1000, 16)
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected camera call ${method.name}")
        }
    } as Camera
}
