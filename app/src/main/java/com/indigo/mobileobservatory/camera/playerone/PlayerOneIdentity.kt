package com.indigo.mobileobservatory.camera.playerone

import com.playeroneastronomy.camera.CameraProperties

internal fun stablePlayerOneIdentity(serialNumber: String?, cameraId: Int): String =
    serialNumber?.takeIf { it.isNotBlank() } ?: "PO-$cameraId"

internal fun playerOneIdentityMatches(
    serialNumber: String?,
    cameraId: Int,
    requestedIdentity: String
): Boolean = stablePlayerOneIdentity(serialNumber, cameraId) == requestedIdentity

internal fun CameraProperties.stableIdentity(): String =
    stablePlayerOneIdentity(serialNumber, cameraId)
