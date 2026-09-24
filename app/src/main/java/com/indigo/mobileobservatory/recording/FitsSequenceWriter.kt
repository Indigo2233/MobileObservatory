package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File

/**
 * Writes each live-view frame as its own FITS file.
 * A crash or power loss can only lose the frame currently being written.
 */
class FitsSequenceWriter(
    val sessionDir: File,
    private val fitsWriter: FITSWriter = FITSWriter()
) {
    var exposureSeconds: Float = 0f
    var gain: Float = 0f
    var gainKind: GainControlKind = GainControlKind.NATIVE_GAIN
    var gainLabel: String = "Gain"
    var gainUnit: String? = null
    var gainDbEquivalent: Float? = null
    var cameraName: String? = null
    var filterName: String? = null
    var configuredFormat: PixelFormat? = null
    var pixelSizeUm: Float? = null
    var focalLengthMm: Float? = null
    var binning: Int = 1

    var totalBytesWritten: Long = 0
        private set
    var currentFrameCount: Int = 0
        private set
    var isOpen: Boolean = false
        private set

    fun open() {
        sessionDir.mkdirs()
        if (!sessionDir.isDirectory) {
            error("Cannot create FITS session directory: ${sessionDir.absolutePath}")
        }
        currentFrameCount = 0
        totalBytesWritten = 0
        isOpen = true
    }

    fun writeFrame(frame: FrameData) {
        if (!isOpen) return
        val index = currentFrameCount + 1
        val file = File(sessionDir, "frame_%06d.fits".format(index))
        fitsWriter.write(
            file = file,
            frame = frame,
            exposureSeconds = exposureSeconds,
            gain = gain,
            gainKind = gainKind,
            gainLabel = gainLabel,
            gainUnit = gainUnit,
            gainDbEquivalent = gainDbEquivalent,
            cameraName = cameraName,
            filterName = filterName,
            configuredFormat = configuredFormat ?: frame.pixelFormat,
            pixelSizeUm = pixelSizeUm,
            focalLengthMm = focalLengthMm,
            binning = binning
        )
        currentFrameCount = index
        totalBytesWritten += file.length()
    }

    fun close() {
        isOpen = false
    }
}
