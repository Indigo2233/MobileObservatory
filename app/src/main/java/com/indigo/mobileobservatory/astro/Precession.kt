package com.indigo.mobileobservatory.astro

/**
 * IAU 2006 precession expressed through the Fukushima-Williams angles, following SOFA's `iauPfw06`
 * and `iauFw2m`. The resulting matrix already contains the ICRS frame bias, so it maps GCRS
 * directions straight onto the equator and equinox of date.
 */
object Precession {
    /** Mean obliquity of the ecliptic in radians (IAU 2006). */
    fun meanObliquityRad(t: Double): Double {
        val arcsec = 84381.406 +
            t * (-46.836769 +
                t * (-0.0001831 +
                    t * (0.00200340 +
                        t * (-0.000000576 +
                            t * -0.0000000434))))
        return arcsec * ARCSEC_TO_RAD
    }

    private fun gammaBarRad(t: Double): Double {
        val arcsec = -0.052928 +
            t * (10.556378 +
                t * (0.4932044 +
                    t * (-0.00031238 +
                        t * (-0.000002788 +
                            t * 0.0000000260))))
        return arcsec * ARCSEC_TO_RAD
    }

    private fun phiBarRad(t: Double): Double {
        val arcsec = 84381.412819 +
            t * (-46.811016 +
                t * (0.0511268 +
                    t * (0.00053289 +
                        t * (-0.000000440 +
                            t * -0.0000000176))))
        return arcsec * ARCSEC_TO_RAD
    }

    private fun psiBarRad(t: Double): Double {
        val arcsec = -0.041775 +
            t * (5038.481484 +
                t * (1.5584175 +
                    t * (-0.00018522 +
                        t * (-0.000026452 +
                            t * -0.0000000148))))
        return arcsec * ARCSEC_TO_RAD
    }

    /** Bias-precession matrix mapping GCRS onto the mean equator and equinox of date. */
    fun biasPrecessionMatrix(t: Double): Mat3 =
        fukushimaWilliamsMatrix(gammaBarRad(t), phiBarRad(t), psiBarRad(t), meanObliquityRad(t))

    /** Bias-precession-nutation matrix mapping GCRS onto the true equator and equinox of date. */
    fun biasPrecessionNutationMatrix(t: Double, nutation: NutationAngles): Mat3 =
        fukushimaWilliamsMatrix(
            gammaBarRad(t),
            phiBarRad(t),
            psiBarRad(t) + nutation.deltaPsiRad,
            meanObliquityRad(t) + nutation.deltaEpsilonRad
        )

    private fun fukushimaWilliamsMatrix(
        gamma: Double,
        phi: Double,
        psi: Double,
        epsilon: Double
    ): Mat3 =
        Mat3.rotateX(epsilon) * Mat3.rotateZ(psi) * Mat3.rotateX(-phi) * Mat3.rotateZ(-gamma)
}
