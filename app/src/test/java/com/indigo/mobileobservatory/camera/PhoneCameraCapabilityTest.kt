package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCameraCapabilityTest {
    @Test
    fun classifiesLensByEquivalentFocalLength() {
        assertEquals(PhoneLensRole.ULTRA_WIDE, PhoneCameraCapability.classifyLensRole(14f))
        assertEquals(PhoneLensRole.MAIN, PhoneCameraCapability.classifyLensRole(26f))
        assertEquals(PhoneLensRole.TELEPHOTO, PhoneCameraCapability.classifyLensRole(70f))
        assertEquals(PhoneLensRole.UNKNOWN, PhoneCameraCapability.classifyLensRole(null))
    }

    @Test
    fun computesFullFrameEquivalentFocalLength() {
        // ~1/1.5" sensor diagonal ~11 mm, 5.4 mm phys ≈ ~21 mm equiv
        val equiv = PhoneCameraCapability.equivalentFocalLengthMm(5.4f, 8.0f, 6.0f)
        assertEquals(23.3f, equiv!!, 1.5f)
    }
}
