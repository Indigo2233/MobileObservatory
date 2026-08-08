package com.indigo.mobileobservatory.camera.playerone

internal enum class AndroidUsbLinkEvidence {
    SUPER_SPEED,
    HIGH_SPEED,
    UNKNOWN
}

internal fun classifyAndroidUsbLink(bulkEndpointMaxPacketSizes: List<Int>): AndroidUsbLinkEvidence =
    when {
        bulkEndpointMaxPacketSizes.any { it >= 1024 } -> AndroidUsbLinkEvidence.SUPER_SPEED
        bulkEndpointMaxPacketSizes.any { it == 512 } -> AndroidUsbLinkEvidence.HIGH_SPEED
        else -> AndroidUsbLinkEvidence.UNKNOWN
    }
