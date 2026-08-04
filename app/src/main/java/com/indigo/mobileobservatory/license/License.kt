package com.indigo.mobileobservatory.license

/** Trial / recording flags. Industrial-camera licensing lives only in the private overlay. */
internal object License {
    // Set to true for trial builds. Disables recording (SER/FITS).
    const val isTrial: Boolean = false

    val canRecord: Boolean get() = !isTrial
}
