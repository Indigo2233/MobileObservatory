package com.indigo.mobileobservatory.camera

import java.util.WeakHashMap
import java.util.UUID

/** Driver-qualified physical serial; anonymous cameras are deliberately connection-local. */
object CameraReferenceIdentity {
    private val anonymous = WeakHashMap<Camera, String>()
    @Synchronized fun of(camera: Camera): String {
        val serial = camera.cameraInfo?.serialNumber?.trim().orEmpty()
        return if (serial.isNotEmpty()) "${camera.javaClass.name}:$serial" else
            anonymous.getOrPut(camera) { "session:${camera.javaClass.name}:${UUID.randomUUID()}" }
    }
}
