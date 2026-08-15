package com.indigo.mobileobservatory.camera.dslr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.indigo.mobileobservatory.camera.Camera
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.CameraStillCaptureCapable
import com.indigo.mobileobservatory.camera.CropInfo
import com.indigo.mobileobservatory.camera.DslrStillFormat
import com.indigo.mobileobservatory.camera.DslrStillResult
import com.indigo.mobileobservatory.camera.FloatRange
import com.indigo.mobileobservatory.camera.FrameCallback
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.camera.GainPreset
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.util.FileLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DslrCamera : Camera, CameraStillCaptureCapable {
    private val _isOpen = MutableStateFlow(false)
    private val _isCapturing = MutableStateFlow(false)

    private var connection: UsbDeviceConnection? = null
    private var transport: PtpTransport? = null
    private var session: PtpSession? = null
    private var deviceInfo: PtpDeviceInfo? = null
    private var isoDesc: PtpPropertyDesc? = null
    private var shutterDesc: PtpPropertyDesc? = null
    private var liveViewOps: NikonLiveViewOps? = null
    @Volatile private var liveViewRequested = false
    private var liveViewThread: Thread? = null
    private var frameCallback: FrameCallback? = null
    private var nextFrameId = 0L

    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    override var cameraInfo: CameraInfo? = null
        private set

    override var exposureRange: FloatRange = FloatRange(125f, 30_000_000f, 10_000f)
        private set
    override var gainRange: FloatRange = FloatRange(100f, 100f, 100f)
        private set
    override var gainCapability: GainCapability =
        GainValueNormalizer.isoCapability(listOf(100f), 100f)
        private set

    override var currentExposureUs: Float = 10_000f
        private set
    override var currentGain: Float = 100f
        private set
    override var currentPixelFormat: PixelFormat = PixelFormat.RGB24
        private set
    override var supportedPixelFormats: List<PixelFormat> = listOf(PixelFormat.RGB24)
        private set
    override var currentRoi: Roi = Roi(0, 0, 640, 424)
        private set
    override var cropInfo: CropInfo = CropInfo(0, 0, 640, 424)
        private set
    override var hwExposureMaxUs: Float = 30_000_000f
        private set
    override var longExposureEnabled: Boolean = false
    override val supportsHostRoi: Boolean = false
    override val recordsLiveViewAsScience: Boolean = false
    override val stillCaptureSupported: Boolean = true
    override val supportedStillFormats: List<DslrStillFormat> = listOf(DslrStillFormat.JPEG)

    fun open(usbDevice: UsbDevice, usbConnection: UsbDeviceConnection): Boolean {
        close()
        val endpoints = DslrUsb.findPtpInterface(usbDevice) ?: return false
        return try {
            val ptp = PtpTransport(usbConnection, endpoints)
            ptp.claim()
            val ptpSession = PtpSession(ptp)
            val info = ptpSession.open()
            connection = usbConnection
            transport = ptp
            session = ptpSession
            deviceInfo = info
            liveViewOps = NikonLiveView.detect(info)
            cameraInfo = CameraInfo(
                name = info.model.ifBlank { usbDevice.productName ?: "Nikon PTP" },
                serialNumber = info.serialNumber.ifBlank {
                    "NIKON-${usbDevice.productId.toString(16)}-${usbDevice.deviceId}"
                },
                sensorWidth = 640,
                sensorHeight = 424,
                maxBitDepth = 14,
                sensorName = info.manufacturer.ifBlank { "Nikon" }
            )
            dumpProbe(usbDevice, info, ptpSession)
            _isOpen.value = true
            true
        } catch (error: Throwable) {
            FileLogger.e(TAG, "PTP open failed: ${error.message}", error)
            releaseQuietly()
            false
        }
    }

    fun markDisconnected() {
        liveViewRequested = false
        _isCapturing.value = false
        _isOpen.value = false
        session = null
        transport = null
        connection = null
        deviceInfo = null
    }

    override fun close() {
        stopCapture()
        runCatching { session?.close() }
        releaseQuietly()
        _isOpen.value = false
    }

    override fun startCapture(callback: FrameCallback) {
        frameCallback = callback
        liveViewRequested = true
        _isCapturing.value = true
        val ops = liveViewOps
        val ptpSession = session
        if (ops == null || ptpSession == null) {
            FileLogger.w(TAG, "No Nikon Live View opcodes; preview stays dark until M2 hardware dump")
            return
        }
        liveViewThread = Thread({
            try {
                val started = ptpSession.operation(ops.start)
                if (!started.ok) {
                    FileLogger.w(TAG, "StartLiveView 0x${started.responseCode.toString(16)}")
                    return@Thread
                }
                Thread.sleep(200)
                while (liveViewRequested && _isOpen.value) {
                    try {
                        val image = ptpSession.operation(ops.getImage)
                        if (!image.ok) {
                            Thread.sleep(40)
                            continue
                        }
                        val jpeg = PtpJpeg.extract(image.data) ?: continue
                        val frame = JpegLiveViewDecoder.decode(jpeg, nextFrameId++) ?: continue
                        applyLiveViewGeometry(frame.width, frame.height)
                        frameCallback?.onFrame(frame)
                    } catch (error: Throwable) {
                        if (!liveViewRequested) break
                        FileLogger.w(TAG, "Live View frame failed: ${error.message}")
                        Thread.sleep(80)
                    }
                }
            } catch (error: Throwable) {
                if (liveViewRequested) {
                    FileLogger.e(TAG, "Live View loop failed: ${error.message}", error)
                }
            } finally {
                runCatching { ptpSession.operation(ops.end) }
                _isCapturing.value = false
            }
        }, "DslrLiveView").apply {
            isDaemon = true
            start()
        }
    }

    override fun stopCapture() {
        liveViewRequested = false
        liveViewThread?.join(8_000)
        liveViewThread = null
        _isCapturing.value = false
        frameCallback = null
    }

    override fun setExposureTime(us: Float) {
        val desc = shutterDesc
        val native = if (desc != null) {
            PtpExposureTime.fromMicroseconds(us, desc.enumValues)
        } else {
            us.toLong()
        }
        currentExposureUs = if (desc != null) {
            PtpExposureTime.toMicroseconds(native, desc.enumValues)
        } else {
            us
        }
        exposureRange = exposureRange.copy(current = currentExposureUs)
        val session = session ?: return
        if (desc == null || !desc.writable) return
        try {
            session.setPropertyValue(desc.code, desc.dataType, native)
        } catch (error: Throwable) {
            FileLogger.w(TAG, "Set shutter failed: ${error.message}")
        }
    }

    override fun setGain(value: Float) {
        val normalized = GainValueNormalizer.normalize(gainCapability, value)
        currentGain = normalized
        gainRange = FloatRange(gainCapability.min, gainCapability.max, currentGain)
        val desc = isoDesc ?: return
        if (!desc.writable) return
        val session = session ?: return
        try {
            session.setPropertyValue(desc.code, desc.dataType, normalized.toLong())
            val read = session.getPropertyValue(desc.code)
            currentGain = PtpPropertyDesc.decodeValue(desc.dataType, read.data).toFloat()
            gainRange = FloatRange(gainCapability.min, gainCapability.max, currentGain)
        } catch (error: Throwable) {
            FileLogger.w(TAG, "Set ISO failed: ${error.message}")
        }
    }

    override fun captureStill(format: DslrStillFormat, outputDir: File): DslrStillResult {
        if (format != DslrStillFormat.JPEG) {
            error("RAW still capture is not implemented yet")
        }
        val ptpSession = session ?: error("PTP session is closed")
        val resumeLiveView = liveViewRequested
        val callback = frameCallback
        stopCapture()
        outputDir.mkdirs()
        try {
            val before = ptpSession.allObjectHandles().toSet()
            val captureOp = captureOpcode()
            val capture = ptpSession.operation(captureOp)
            if (!capture.ok && !isIgnorableCaptureError(capture.responseCode)) {
                FileLogger.w(
                    TAG,
                    "InitiateCapture 0x${capture.responseCode.toString(16)}; continuing if an object appears"
                )
            }
            val waitMs = (currentExposureUs / 1000f).toLong().coerceAtLeast(5_000L) + 15_000L
            val handle = waitForNewHandle(ptpSession, before, waitMs)
                ?: error("Timed out waiting for a still image")
            val bytes = ptpSession.getObject(handle)
            val jpeg = PtpJpeg.extract(bytes) ?: bytes.takeIf { it.size >= 2 && it[0] == 0xFF.toByte() }
            val file = File(outputDir, "dslr-${System.currentTimeMillis()}.jpg")
            file.writeBytes(jpeg ?: bytes)
            return DslrStillResult(
                jpegFile = file,
                rawFile = null,
                iso = currentGain.toInt(),
                exposureUs = currentExposureUs.toLong(),
                bulb = false
            )
        } finally {
            if (resumeLiveView && callback != null && _isOpen.value) {
                startCapture(callback)
            }
        }
    }

    override fun setPixelFormat(format: PixelFormat) = Unit
    override fun setRoi(roi: Roi) = Unit
    override fun resetRoi() = Unit
    override fun recycleBuffer(buf: ByteArray) = Unit

    private fun waitForNewHandle(ptpSession: PtpSession, before: Set<Long>, waitMs: Long): Long? {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val handles = runCatching { ptpSession.allObjectHandles() }.getOrDefault(emptyList())
            val created = handles.filter { it !in before }
            if (created.isNotEmpty()) return created.last()
            Thread.sleep(250)
        }
        return null
    }

    private fun captureOpcode(): Int {
        val info = deviceInfo ?: return PtpConstants.OC_INITIATE_CAPTURE
        return when {
            info.hasOperation(PtpConstants.OC_INITIATE_CAPTURE) -> PtpConstants.OC_INITIATE_CAPTURE
            info.hasOperation(0x90C0) -> 0x90C0
            else -> PtpConstants.OC_INITIATE_CAPTURE
        }
    }

    private fun isIgnorableCaptureError(code: Int): Boolean =
        code == PtpConstants.RC_DEVICE_BUSY || code == PtpConstants.RC_NIKON_OUT_OF_FOCUS

    private fun applyLiveViewGeometry(width: Int, height: Int) {
        if (currentRoi.width == width && currentRoi.height == height) return
        currentRoi = Roi(0, 0, width, height)
        cropInfo = CropInfo(0, 0, width, height)
        val info = cameraInfo
        if (info != null) {
            cameraInfo = info.copy(sensorWidth = width, sensorHeight = height)
        }
    }

    private fun dumpProbe(usbDevice: UsbDevice, info: PtpDeviceInfo, ptpSession: PtpSession) {
        FileLogger.i(
            TAG,
            "PTP DeviceInfo VID=0x${usbDevice.vendorId.toString(16)} " +
                "PID=0x${usbDevice.productId.toString(16)} " +
                "manufacturer=${info.manufacturer} model=${info.model} " +
                "version=${info.deviceVersion} serial=${info.serialNumber} " +
                "std=0x${info.standardVersion.toString(16)} " +
                "vendorExt=${info.vendorExtensionId}/${info.vendorExtensionVersion} " +
                "(${info.vendorExtensionDesc})"
        )
        FileLogger.i(TAG, "PTP operations: ${formatCodes(info.operations)}")
        FileLogger.i(TAG, "PTP properties: ${formatCodes(info.properties)}")
        FileLogger.i(TAG, "Live View ops: $liveViewOps")
        probeProperty(ptpSession, info, PtpConstants.PROP_EXPOSURE_INDEX, "ISO") { desc ->
            isoDesc = desc
            val values = desc.enumValues.map { it.toFloat() }
            val hiPresets = values.filter { it >= 12_800f }.map { GainPreset(it, "Hi") }
            gainCapability = GainValueNormalizer.isoCapability(
                allowedValues = values,
                current = desc.currentValue.toFloat(),
                writable = desc.writable,
                presets = hiPresets
            )
            currentGain = GainValueNormalizer.normalize(gainCapability, desc.currentValue.toFloat())
            gainRange = FloatRange(gainCapability.min, gainCapability.max, currentGain)
            FileLogger.i(
                TAG,
                "ISO writable=${desc.writable} current=${desc.currentValue} " +
                    "allowed=${desc.enumValues.joinToString()}"
            )
        }
        probeProperty(ptpSession, info, PtpConstants.PROP_EXPOSURE_TIME, "shutter") { desc ->
            shutterDesc = desc
            val times = desc.enumValues.filter { it > 0L && it != 0xFFFFFFFFL }
            if (times.isNotEmpty()) {
                hwExposureMaxUs = times.maxOf { PtpExposureTime.toMicroseconds(it, times) }
                currentExposureUs = PtpExposureTime.toMicroseconds(desc.currentValue, times)
                exposureRange = FloatRange(
                    times.minOf { PtpExposureTime.toMicroseconds(it, times) },
                    hwExposureMaxUs,
                    currentExposureUs
                )
            }
            FileLogger.i(
                TAG,
                "Shutter writable=${desc.writable} current=${desc.currentValue} " +
                    "allowed=${desc.enumValues.joinToString()}"
            )
        }
        probeProperty(ptpSession, info, PtpConstants.PROP_F_NUMBER, "aperture") { desc ->
            FileLogger.i(
                TAG,
                "Aperture writable=${desc.writable} current=${desc.currentValue} " +
                    "allowed=${desc.enumValues.joinToString()}"
            )
        }
    }

    private fun probeProperty(
        ptpSession: PtpSession,
        info: PtpDeviceInfo,
        code: Int,
        label: String,
        onDesc: (PtpPropertyDesc) -> Unit
    ) {
        if (!info.hasProperty(code)) {
            FileLogger.w(TAG, "No standard $label property 0x${code.toString(16)}")
            return
        }
        try {
            onDesc(ptpSession.getPropertyDesc(code))
        } catch (error: Throwable) {
            FileLogger.w(TAG, "Failed to read $label: ${error.message}")
        }
    }

    private fun releaseQuietly() {
        liveViewRequested = false
        runCatching { transport?.release() }
        runCatching { connection?.close() }
        transport = null
        session = null
        connection = null
        deviceInfo = null
        isoDesc = null
        shutterDesc = null
        liveViewOps = null
    }

    private fun formatCodes(codes: List<Int>): String =
        codes.joinToString { "0x${it.toString(16)}" }

    companion object {
        private const val TAG = "DslrCamera"
    }
}
