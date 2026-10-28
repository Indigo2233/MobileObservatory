package com.indigo.mobileobservatory.polar

/** Error components reverse with the pole; east/west describe the physical polar-axis motion. */
fun polarAdjustWest(azimuthErrorDeg: Double, latitudeDeg: Double): Boolean =
    (azimuthErrorDeg > 0.0) == (latitudeDeg >= 0.0)

fun polarAdjustDown(altitudeErrorDeg: Double, latitudeDeg: Double): Boolean =
    (altitudeErrorDeg > 0.0) == (latitudeDeg >= 0.0)
