package com.indigo.mobileobservatory.camera.playerone

/** The SDK read timeout covers transfer after readiness, independently of the exposure length. */
internal fun awaitPlayerOneImage(
    active: () -> Boolean,
    imageReady: () -> Boolean,
    readImage: () -> Unit,
    waitForReady: Boolean = true,
    pause: () -> Unit = { Thread.sleep(20) }
): Boolean {
    while (active()) {
        if (!waitForReady || imageReady()) {
            if (!active()) return false
            readImage()
            return active()
        }
        pause()
    }
    return false
}
