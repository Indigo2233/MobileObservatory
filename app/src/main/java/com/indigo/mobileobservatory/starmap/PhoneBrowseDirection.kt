package com.indigo.mobileobservatory.starmap

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Browsing-only rear-camera axis. It is never an optical pointing observation. */
internal data class PhoneBrowseDirection(val azimuthDeg: Double, val altitudeDeg: Double) {
    companion object {
        fun fromRotationMatrix(matrix: FloatArray, declinationDeg: Double = 0.0): PhoneBrowseDirection? {
            if (matrix.size != 9 || matrix.any { !it.isFinite() } || !declinationDeg.isFinite()) return null
            // Android device-to-world rotation maps to east / magnetic north / up.
            // The rear camera looks along -Z, independent of display rotation.
            val east = -matrix[2].toDouble()
            val north = -matrix[5].toDouble()
            val up = -matrix[8].toDouble()
            val norm = sqrt(east * east + north * north + up * up)
            if (norm < 0.5) return null
            return PhoneBrowseDirection(
                ((Math.toDegrees(atan2(east, north)) + declinationDeg) % 360.0 + 360.0) % 360.0,
                Math.toDegrees(asin((up / norm).coerceIn(-1.0, 1.0)))
            )
        }
    }
}
