package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.mount.MountCoordinates
import java.io.File

/** File solving boundary used by the observing workflow and its device-free UI tests. */
interface ImagePlateSolver {
    val ready: Boolean
    suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates? = null,
                      searchRadiusDeg: Double? = null): PlateSolveResult
}
