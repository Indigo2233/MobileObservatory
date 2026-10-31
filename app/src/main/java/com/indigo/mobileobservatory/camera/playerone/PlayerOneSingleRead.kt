package com.indigo.mobileobservatory.camera.playerone

/** The SDK read timeout covers transfer after readiness, independently of the exposure length. */
internal fun awaitPlayerOneSingleImage(
    active: () -> Boolean,
    imageReady: () -> Boolean,
    readImage: () -> Unit,
    pause: () -> Unit = { Thread.sleep(20) }
): Boolean {
    while (active()) {
        if (imageReady()) {
            if (!active()) return false
            readImage()
            return active()
        }
        pause()
    }
    return false
}
