package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.pointing.PhoneCatalogStar
import com.indigo.mobileobservatory.pointing.SkyDirection

/** A second eyepiece reference must constrain an independent direction, not repeat the first star. */
internal fun auxiliaryValidationStarAllowed(star: PhoneCatalogStar, firstReference: SkyDirection?): Boolean =
    firstReference == null || firstReference.vector().angleDeg(SkyDirection(star.raDeg, star.decDeg).vector()) >= 5.0
