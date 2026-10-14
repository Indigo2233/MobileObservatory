package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.*
import org.junit.Test

class ReferencePixelMapperTest {
    @Test fun hardwareBinnedAndOriginalResolutionRoiHaveSameOpticalAxis() {
        // ZWO/PlayerOne/QHY use the binned grid; Toup ROI coordinates remain original resolution.
        val hardware = ReferencePixelMapper.sensorCenter(3536, 3536, Roi(0, 0, 1768, 1768), 2, 1768, 1768)
        val digital = ReferencePixelMapper.sensorCenter(3536, 3536, Roi(0, 0, 3536, 3536), 1, 1768, 1768)
        assertEquals(884.5, hardware.first, 1e-9); assertEquals(hardware, digital)
        val software = ReferencePixelMapper.sensorCenter(3536, 3536, Roi(0, 0, 1768, 1768), 2, 884, 884)
        assertEquals(442.5, software.first, 1e-9)
    }
    @Test fun offCenterCropProjectsTheSensorAxisAndMissingAxisIsRejected() {
        val pixel = ReferencePixelMapper.sensorCenter(4000, 3000, Roi(800, 500, 500, 500), 2, 500, 500)
        assertEquals(200.5, pixel.first, 1e-9); assertEquals(250.5, pixel.second, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { ReferencePixelMapper.sensorCenter(4000, 3000, Roi(0, 0, 500, 500), 2, 500, 500) }
    }
}
