package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.astro.SensorSpec
import com.indigo.mobileobservatory.ui.screens.pixelSizeForSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlateSolveOpticsTest {
    @Test
    fun jpegUsesUserFocalLengthNotStaleFitsFovh() {
        val jpeg = FitsSolveHints(
            width = 6224,
            height = 4168,
            fovHeightDeg = 1.71
        )
        val scale = PlateSolveOptics.scaleHint(
            hints = jpeg,
            userFocalLengthMm = 800.0,
            userPixelSizeUm = 3.76
        )
        assertEquals(1.122, scale.fovHeightDeg!!, 0.01)
        assertEquals(800.0, scale.focalLengthMm!!, 0.0)
        assertEquals(3.76, scale.pixelSizeUm!!, 0.0)
    }

    @Test
    fun changingFocalLengthFrom525To800UpdatesFov() {
        val jpeg = FitsSolveHints(width = 6224, height = 4168)
        val at525 = PlateSolveOptics.scaleHint(jpeg, 525.0, 3.76)
        val at800 = PlateSolveOptics.scaleHint(jpeg, 800.0, 3.76)
        assertEquals(1.71, at525.fovHeightDeg!!, 0.02)
        assertEquals(1.122, at800.fovHeightDeg!!, 0.01)
    }

    @Test
    fun impliedFocalLengthFromSolvedScale() {
        assertEquals(528.0, PlateSolveOptics.impliedFocalLengthMm(3.76, 1.47)!!, 1.0)
        assertEquals(800.0, PlateSolveOptics.impliedFocalLengthMm(3.76, 0.969)!!, 2.0)
        assertNull(PlateSolveOptics.impliedFocalLengthMm(null, 1.47))
    }

    @Test
    fun withoutPixelSizeFallsBackToFileFov() {
        val hints = FitsSolveHints(width = 6224, height = 4168, fovHeightDeg = 2.5)
        val scale = PlateSolveOptics.scaleHint(hints, 800.0, null)
        assertEquals(2.5, scale.fovHeightDeg!!, 0.0)
    }

    @Test
    fun catalogHeightIsUsedWhenImageSizeIsUnknown() {
        val fov = PlateSolveOptics.astapFovDeg(
            hints = FitsSolveHints(),
            userFocalLengthMm = 800.0,
            userPixelSizeUm = 3.76,
            catalogHeightPx = 4168
        )
        assertEquals(1.122, fov!!, 0.01)
    }

    @Test
    fun userFocalLengthWinsOverFitsFocalen() {
        val fits = FitsSolveHints(
            width = 6224,
            height = 4168,
            pixelSizeUm = 3.76,
            focalLengthMm = 525.0,
            fovHeightDeg = 1.71
        )
        val scale = PlateSolveOptics.scaleHint(fits, userFocalLengthMm = 800.0, userPixelSizeUm = null)
        assertEquals(800.0, scale.focalLengthMm!!, 0.0)
        assertEquals(1.122, scale.fovHeightDeg!!, 0.01)
    }

    @Test
    fun imageHeightWinsOverCatalogHeight() {
        val cropped = FitsSolveHints(width = 3000, height = 2000)
        val fov = PlateSolveOptics.astapFovDeg(
            hints = cropped,
            userFocalLengthMm = 800.0,
            userPixelSizeUm = 3.76,
            catalogHeightPx = 4168
        )
        assertEquals(0.538, fov!!, 0.01)
    }

    @Test
    fun customSensorUsesTypedPixelSize() {
        val custom = SensorSpec(OpticsEquipment.CUSTOM_SENSOR_ID, "Custom", 3.76, 1920, 1080)
        val imx571 = SensorSpec("ccd_imx571", "IMX571", 3.76, 6224, 4168)
        assertEquals(4.78, pixelSizeForSensor(custom, 4.78))
        assertNull(pixelSizeForSensor(custom, null))
        assertEquals(3.76, pixelSizeForSensor(imx571, 4.78))
    }
}

class FitsSolveHintReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun jpegDimensionsComeFromSofNotFitsCards() {
        val file = folder.newFile("frame.jpg")
        file.writeBytes(minimalJpeg(width = 6224, height = 4168))
        val hints = FitsSolveHintReader.read(file)
        assertEquals(6224, hints.width)
        assertEquals(4168, hints.height)
        assertNull(hints.focalLengthMm)
        assertNull(hints.pixelSizeUm)
        assertNull(hints.fovHeightDeg)
    }

    @Test
    fun pngDimensionsComeFromIhdr() {
        val file = folder.newFile("frame.png")
        file.writeBytes(minimalPng(width = 1920, height = 1080))
        val hints = FitsSolveHintReader.read(file)
        assertEquals(1920, hints.width)
        assertEquals(1080, hints.height)
    }

    @Test
    fun cacheJpegWithoutFitsExtensionStillHasSize() {
        val file = folder.newFile("platesolve_1.jpg")
        file.writeBytes(minimalJpeg(width = 800, height = 600))
        val hints = FitsSolveHintReader.read(file)
        assertEquals(800, hints.width)
        assertEquals(600, hints.height)
    }

    @Test
    fun jpegSizeSkipsApp0BeforeSof() {
        val file = folder.newFile("exif.jpg")
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        )
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof(320, 240))
        assertEquals(320 to 240, RasterImageSize.read(file))
        val hints = FitsSolveHintReader.read(file)
        assertEquals(320, hints.width)
        assertEquals(240, hints.height)
    }

    @Test
    fun fitsHeaderStillSuppliesPixelFocalAndFov() {
        val file = writeFits(
            "SIMPLE" to "T",
            "NAXIS1" to "6224",
            "NAXIS2" to "4168",
            "XPIXSZ" to "3.7600",
            "YPIXSZ" to "3.7600",
            "FOCALLEN" to "800.0",
            "XBINNING" to "1",
            "YBINNING" to "1"
        )
        val hints = FitsSolveHintReader.read(file)
        assertEquals(6224, hints.width)
        assertEquals(4168, hints.height)
        assertEquals(3.76, hints.pixelSizeUm!!, 0.001)
        assertEquals(800.0, hints.focalLengthMm!!, 0.0)
        assertEquals(1.122, hints.fovHeightDeg!!, 0.01)
    }

    @Test
    fun fitsMagicIsDetectedWithoutFitExtension() {
        val file = folder.newFile("frame.bin")
        file.writeBytes("SIMPLE  =                    T / test".padEnd(80).toByteArray(Charsets.US_ASCII))
        assertTrue(RasterImageSize.isFitsMagic(file))
        assertNull(RasterImageSize.read(file))
    }

    @Test
    fun unknownBytesHaveNoRasterSize() {
        val file = folder.newFile("notes.txt")
        file.writeText("not an image")
        assertNull(RasterImageSize.read(file))
        val hints = FitsSolveHintReader.read(file)
        assertEquals(0, hints.width)
        assertEquals(0, hints.height)
    }

    private fun writeFits(vararg cards: Pair<String, String>): File {
        val file = folder.newFile("frame.fits")
        val header = StringBuilder()
        cards.forEach { (key, value) ->
            header.append("${key.padEnd(8).take(8)}= ${value.padStart(20)} / test".padEnd(80).take(80))
        }
        header.append("END".padEnd(80))
        file.writeBytes(header.toString().padEnd(2880, ' ').toByteArray(Charsets.US_ASCII))
        return file
    }

    private fun sof(width: Int, height: Int): ByteArray {
        return byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            (height shr 8).toByte(), (height and 0xFF).toByte(),
            (width shr 8).toByte(), (width and 0xFF).toByte(),
            0x01, 0x01, 0x11, 0x00
        )
    }

    private fun minimalJpeg(width: Int, height: Int): ByteArray {
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sof(width, height)
    }

    private fun minimalPng(width: Int, height: Int): ByteArray {
        val ihdr = ByteArray(24)
        ihdr[0] = 0x89.toByte()
        ihdr[1] = 0x50
        ihdr[2] = 0x4E
        ihdr[3] = 0x47
        ihdr[4] = 0x0D
        ihdr[5] = 0x0A
        ihdr[6] = 0x1A
        ihdr[7] = 0x0A
        ihdr[11] = 0x0D
        ihdr[12] = 0x49
        ihdr[13] = 0x48
        ihdr[14] = 0x44
        ihdr[15] = 0x52
        ihdr[16] = (width shr 24).toByte()
        ihdr[17] = (width shr 16).toByte()
        ihdr[18] = (width shr 8).toByte()
        ihdr[19] = width.toByte()
        ihdr[20] = (height shr 24).toByte()
        ihdr[21] = (height shr 16).toByte()
        ihdr[22] = (height shr 8).toByte()
        ihdr[23] = height.toByte()
        return ihdr
    }
}
