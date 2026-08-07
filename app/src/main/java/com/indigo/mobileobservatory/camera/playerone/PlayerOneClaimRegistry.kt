package com.indigo.mobileobservatory.camera.playerone

import java.util.concurrent.ConcurrentHashMap

/** Process-wide camera-id mutex for main/guide sessions. No Android dependency. */
object PlayerOneClaimRegistry {
    private val claimedIds = ConcurrentHashMap.newKeySet<Int>()

    fun claim(cameraId: Int): Boolean = claimedIds.add(cameraId)

    fun release(cameraId: Int) {
        claimedIds.remove(cameraId)
    }

    fun resetForTest() {
        claimedIds.clear()
    }

    fun snapshot(): Set<Int> = claimedIds.toSet()
}
