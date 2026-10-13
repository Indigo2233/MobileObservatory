package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Golden values come from the separately installed Colour Python implementation, not Kotlin. */
class DdfapdReferenceTest {
    @Test fun allCfaPhasesOddDimensionsBordersAndHdrMatchIndependentMenonReference() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/stacking/ddfapd-menon2007-reference.json"))
        val fixture = resource.bufferedReader().use { JSONObject(it.readText()) }
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val width = case.getInt("width"); val height = case.getInt("height")
            val pattern = case.getString("pattern")
            val samples = case.getJSONArray("input")
            val sensor = LinearImage(width, height, 1, FloatArray(samples.length()) { samples.getDouble(it).toFloat() })
            val format = PixelFormat.valueOf("BAYER_${pattern.take(2)}16")
            val result = LinearPixelDecoder.demosaicSensor(sensor, format, algorithm = DemosaicAlgorithm.DDFAPD)
            val expected = case.getJSONArray("expected")
            assertEquals(expected.length(), result.pixels.size)
            for (sample in result.pixels.indices) assertEquals("$pattern $width x $height sample $sample",
                expected.getDouble(sample), result.pixels[sample].toDouble(), 2e-6)
        }
    }
}
