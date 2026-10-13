package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.ui.screens.auxiliaryValidationStarAllowed
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryReferenceChoiceTest {
    private fun star(ra: Double, dec: Double = 0.0) = PhoneCatalogStar(ra, dec, 1.0, 1, "reference")

    @Test fun validationCannotReuseTheFirstStarOrAnInsufficientlySeparatedStar() {
        val first = SkyDirection(120.0, 0.0)
        assertTrue(auxiliaryValidationStarAllowed(star(120.0), null))
        assertFalse(auxiliaryValidationStarAllowed(star(120.0), first))
        assertFalse(auxiliaryValidationStarAllowed(star(124.9), first))
        assertTrue(auxiliaryValidationStarAllowed(star(125.1), first))
    }

    @Test fun separationUsesTheSphereAcrossRightAscensionWrapAndNearThePole() {
        assertFalse(auxiliaryValidationStarAllowed(star(1.0), SkyDirection(359.0, 0.0)))
        assertFalse(auxiliaryValidationStarAllowed(star(180.0, 89.0), SkyDirection(0.0, 89.0)))
        assertTrue(auxiliaryValidationStarAllowed(star(180.0, 80.0), SkyDirection(0.0, 80.0)))
    }
}
