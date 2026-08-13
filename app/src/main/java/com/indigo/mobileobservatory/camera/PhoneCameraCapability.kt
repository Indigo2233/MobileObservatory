package com.indigo.mobileobservatory.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import com.indigo.mobileobservatory.pointing.CameraLensCalibration
import kotlin.math.atan
import kotlin.math.sqrt

enum class PhoneLensRole {
    ULTRA_WIDE,
    MAIN,
    TELEPHOTO,
    UNKNOWN
}

data class PhoneCameraCapability(
    val cameraId: String,
    val facing: Int,
    val lensRole: PhoneLensRole,
    val equivalentFocalLengthMm: Float?,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val exposureTimeRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val activeArraySize: android.graphics.Rect?,
    val yuvOutputSizes: List<Size>,
    val rawOutputSizes: List<Size>,
    val maxDigitalZoom: Float,
    val noiseReductionModes: IntArray,
    val edgeModes: IntArray,
    val oisAvailable: Boolean,
    val sensorOrientation: Int = 0,
    val lensCalibration: CameraLensCalibration? = null,
    val distortionCorrectionModes: IntArray = intArrayOf(),
    /** Non-null when this is a physical sub-camera that must be opened via its logical parent. */
    val logicalParentId: String? = null
) {
    /** Camera id that [android.hardware.camera2.CameraManager.openCamera] must be called with. */
    val openableCameraId: String
        get() = logicalParentId ?: cameraId

    val isPhysicalSubCamera: Boolean
        get() = logicalParentId != null

    val maxExposureSeconds: Double?
        get() = exposureTimeRangeNs?.upper?.let { it / 1_000_000_000.0 }

    val minExposureSeconds: Double?
        get() = exposureTimeRangeNs?.lower?.let { it / 1_000_000_000.0 }

    val displayLabel: String
        get() {
            val role = when (lensRole) {
                PhoneLensRole.ULTRA_WIDE -> "Ultra-wide"
                PhoneLensRole.MAIN -> "Main"
                PhoneLensRole.TELEPHOTO -> "Tele"
                PhoneLensRole.UNKNOWN -> "Camera"
            }
            val fl = equivalentFocalLengthMm?.let { "${it.toInt()}mm" }
                ?: focalLengthMm?.let { "${"%.1f".format(it)}mm-phys" }
            val idTag = if (logicalParentId != null) "#$logicalParentId/$cameraId" else "#$cameraId"
            return if (fl != null) "$role $fl ($idTag)" else "$role ($idTag)"
        }

    /** Identity used to drop physical lenses that duplicate an already-listed logical camera. */
    internal fun focalKey(): String? {
        val fl = focalLengthMm ?: return null
        val sw = sensorWidthMm ?: return null
        return "${"%.2f".format(fl)}/${"%.2f".format(sw)}"
    }

    fun estimatedFovDegrees(outputWidth: Int, outputHeight: Int): Pair<Double, Double>? {
        val fl = focalLengthMm?.toDouble() ?: return null
        val sw = sensorWidthMm?.toDouble() ?: return null
        val sh = sensorHeightMm?.toDouble() ?: return null
        if (fl <= 0.0 || sw <= 0.0 || sh <= 0.0) return null
        val fovW = Math.toDegrees(2.0 * atan(sw / (2.0 * fl)))
        val fovH = Math.toDegrees(2.0 * atan(sh / (2.0 * fl)))
        if (outputWidth > 0 && outputHeight > 0 && activeArraySize != null) {
            val aw = activeArraySize.width().toDouble().coerceAtLeast(1.0)
            val ah = activeArraySize.height().toDouble().coerceAtLeast(1.0)
            val scale = minOf(outputWidth / aw, outputHeight / ah)
            val usedW = (outputWidth / scale).coerceAtMost(aw)
            val usedH = (outputHeight / scale).coerceAtMost(ah)
            return fovW * (usedW / aw) to fovH * (usedH / ah)
        }
        return fovW to fovH
    }

    fun summaryLines(): List<String> = buildList {
        add("cameraId=$cameraId role=$lensRole label=$displayLabel")
        add("facing=${facingLabel(facing)} equivFL=${equivalentFocalLengthMm ?: "?"}mm")
        add("RAW=$supportsRaw manualSensor=$supportsManualSensor")
        add(
            "exposure=${minExposureSeconds?.let { "%.3f".format(it) } ?: "?"}s" +
                "–${maxExposureSeconds?.let { "%.3f".format(it) } ?: "?"}s"
        )
        add("ISO=${isoRange?.lower ?: "?"}–${isoRange?.upper ?: "?"}")
        add("focal=${focalLengthMm ?: "?"}mm sensor=${sensorWidthMm}×${sensorHeightMm}mm")
        estimatedFovDegrees(0, 0)?.let { (w, h) ->
            add("approxFOV=${"%.1f".format(w)}°×${"%.1f".format(h)}°")
        }
        add("YUV sizes=${yuvOutputSizes.take(4)}")
        add("RAW sizes=${rawOutputSizes.take(4)}")
        add("NR modes=${noiseReductionModes.toList()} edge=${edgeModes.toList()} OIS=$oisAvailable")
    }

    companion object {
        private const val TAG = "PhoneCameraCapability"
        private const val FULL_FRAME_DIAGONAL_MM = 43.27f

        fun facingLabel(facing: Int): String = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown($facing)"
        }

        fun equivalentFocalLengthMm(focalMm: Float?, sensorWidthMm: Float?, sensorHeightMm: Float?): Float? {
            if (focalMm == null || sensorWidthMm == null || sensorHeightMm == null) return null
            if (focalMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
            val diag = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
            if (diag <= 0f) return null
            return focalMm * FULL_FRAME_DIAGONAL_MM / diag
        }

        fun classifyLensRole(equivalentFocalLengthMm: Float?): PhoneLensRole {
            val fl = equivalentFocalLengthMm ?: return PhoneLensRole.UNKNOWN
            return when {
                fl < 20f -> PhoneLensRole.ULTRA_WIDE
                fl > 45f -> PhoneLensRole.TELEPHOTO
                else -> PhoneLensRole.MAIN
            }
        }

        fun probeBackCamera(context: Context): PhoneCameraCapability? {
            val all = enumerateBackCameras(context)
            return all.firstOrNull { it.lensRole == PhoneLensRole.MAIN } ?: all.firstOrNull()
        }

        /**
         * Only ids in [CameraManager.getCameraIdList] can be opened directly. Physical sub-cameras
         * of a logical multi-camera must be driven through their logical parent, so they are kept
         * with [logicalParentId] set and only when they add a distinct focal length.
         */
        fun enumerateBackCameras(context: Context): List<PhoneCameraCapability> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val openableIds = linkedSetOf<String>()
            val physicalToParent = linkedMapOf<String, String>()

            for (id in manager.cameraIdList) {
                val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    continue
                }
                openableIds.add(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (physicalId in chars.physicalCameraIds) {
                        if (physicalId !in openableIds) {
                            physicalToParent.putIfAbsent(physicalId, id)
                        }
                    }
                }
            }

            val directAll = openableIds.mapNotNull { id ->
                runCatching { probe(context, id) }.getOrNull()
            }.filter { it.yuvOutputSizes.isNotEmpty() || it.rawOutputSizes.isNotEmpty() }

            // OEMs often expose several logical cameras over the same physical lens (vivo ships a
            // duplicate main-camera id). Keep only the most capable one per focal length.
            val direct = directAll
                .groupBy { it.focalKey() ?: "id:${it.cameraId}" }
                .map { (_, group) -> group.maxWith(preferenceComparator) }

            val seenFocalKeys = direct.mapNotNull { it.focalKey() }.toMutableSet()
            val physical = physicalToParent.entries.mapNotNull { (physicalId, parentId) ->
                if (physicalId in openableIds) return@mapNotNull null
                val cap = runCatching { probe(context, physicalId, parentId) }.getOrNull()
                    ?: return@mapNotNull null
                if (cap.yuvOutputSizes.isEmpty() && cap.rawOutputSizes.isEmpty()) return@mapNotNull null
                val key = cap.focalKey()
                // Drop physical lenses that merely duplicate what the logical camera already reports.
                if (key != null && !seenFocalKeys.add(key)) return@mapNotNull null
                cap
            }

            val merged = (direct + physical).sortedWith(
                compareBy<PhoneCameraCapability> {
                    when (it.lensRole) {
                        PhoneLensRole.MAIN -> 0
                        PhoneLensRole.ULTRA_WIDE -> 1
                        PhoneLensRole.TELEPHOTO -> 2
                        PhoneLensRole.UNKNOWN -> 3
                    }
                }.thenBy { it.equivalentFocalLengthMm ?: Float.MAX_VALUE }
                    .thenBy { it.cameraId }
            )

            Log.i(
                TAG,
                "enumerateBackCameras: idList=${manager.cameraIdList.toList()} " +
                    "openable=$openableIds physicalMap=$physicalToParent " +
                    "directAll=${directAll.map { "${it.cameraId}:${it.focalKey()}:${it.displayLabel}" }} " +
                    "result=${merged.map { it.displayLabel }}"
            )
            return merged
        }

        /** Higher is better: prefer manual sensor, then RAW, then longer exposure ceiling. */
        private val preferenceComparator = compareBy<PhoneCameraCapability>(
            { if (it.supportsManualSensor) 1 else 0 },
            { if (it.supportsRaw) 1 else 0 },
            { it.exposureTimeRangeNs?.upper ?: 0L },
            { -(it.cameraId.toIntOrNull() ?: Int.MAX_VALUE) }
        )

        fun probe(
            context: Context,
            cameraId: String,
            logicalParentId: String? = null
        ): PhoneCameraCapability {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val supportsManual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalMm = focals?.firstOrNull()
            val sensorW = physical?.width
            val sensorH = physical?.height
            val equiv = equivalentFocalLengthMm(focalMm, sensorW, sensorH)
            val intrinsic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            } else null
            val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.get(CameraCharacteristics.LENS_DISTORTION)
            } else null
            val calibration = if (intrinsic?.size == 5 && distortion?.size == 5) {
                runCatching {
                    CameraLensCalibration(
                        focalX = intrinsic[0].toDouble(), focalY = intrinsic[1].toDouble(),
                        principalX = intrinsic[2].toDouble(), principalY = intrinsic[3].toDouble(),
                        skew = intrinsic[4].toDouble(), radialK1 = distortion[0].toDouble(),
                        radialK2 = distortion[1].toDouble(), radialK3 = distortion[2].toDouble(),
                        tangentialP1 = distortion[3].toDouble(), tangentialP2 = distortion[4].toDouble()
                    )
                }.getOrNull()
            } else null
            return PhoneCameraCapability(
                cameraId = cameraId,
                facing = facing,
                lensRole = classifyLensRole(equiv),
                equivalentFocalLengthMm = equiv,
                supportsRaw = supportsRaw,
                supportsManualSensor = supportsManual,
                exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                focalLengthMm = focalMm,
                sensorWidthMm = sensorW,
                sensorHeightMm = sensorH,
                activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                yuvOutputSizes = yuvSizes.sortedByDescending { it.width.toLong() * it.height },
                rawOutputSizes = rawSizes.sortedByDescending { it.width.toLong() * it.height },
                maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                noiseReductionModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?: intArrayOf(),
                edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf(),
                oisAvailable = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                lensCalibration = calibration,
                distortionCorrectionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: intArrayOf()
                } else intArrayOf(),
                logicalParentId = logicalParentId
            )
        }

        /** Diagnostic dump when enumeration yields nothing (permission / OEM quirks). */
        fun debugCameraIdDump(context: Context): String {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                buildString {
                    append("cameraIdList=${manager.cameraIdList.toList()}")
                    for (id in manager.cameraIdList) {
                        append('\n')
                        try {
                            val chars = manager.getCameraCharacteristics(id)
                            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                                ?.toList().orEmpty()
                            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.size ?: 0
                            val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.size ?: 0
                            append("id=$id facing=$facing yuvSizes=$yuv rawSizes=$raw caps=$caps")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                append(" physical=${chars.physicalCameraIds}")
                            }
                        } catch (t: Throwable) {
                            append("id=$id ERROR ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                "CameraManager dump failed: ${t.message}"
            }
        }
    }
}
