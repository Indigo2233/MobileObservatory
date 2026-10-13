package com.indigo.mobileobservatory.astro

import com.indigo.mobileobservatory.pointing.VerificationField
import com.indigo.mobileobservatory.pointing.ReferencePixelMapper
import com.indigo.mobileobservatory.camera.Roi
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min

/** Raw acquisition geometry. Binning changes pixel pitch, never the optical installation. */
data class ConnectedOpticsGeometry(
    val pixelSizeUm: Float,
    val widthPx: Int,
    val heightPx: Int,
    val binning: Int = 1,
    val deviceId: String,
    /** Zero-based pixel center coordinates in the same ROI domain as width/height. */
    val axisPixelX: Double? = null,
    val axisPixelY: Double? = null,
    val axisKnown: Boolean = axisPixelX != null && axisPixelY != null
) {
    fun sensor(displayName: String): SensorSpec? = OpticsEquipment.connectedSensor(
        pixelSizeUm.takeIf { it.isFinite() && it > 0f }?.times(binning.coerceAtLeast(1)),
        widthPx, heightPx, displayName
    )
    val hasKnownAxis get() = axisKnown && axisPixelX?.let { it.isFinite() && it in -.5..(widthPx - .5) } == true &&
        axisPixelY?.let { it.isFinite() && it in -.5..(heightPx - .5) } == true
    val axisCentered get() = hasKnownAxis && abs(requireNotNull(axisPixelX) - (widthPx - 1) / 2.0) < 1e-6 &&
        abs(requireNotNull(axisPixelY) - (heightPx - 1) / 2.0) < 1e-6

    fun inscribedField(focalLengthMm: Double, altitudeUpRollDeg: Double?): VerificationField? {
        if (!hasKnownAxis || !focalLengthMm.isFinite() || focalLengthMm <= 0 || !pixelSizeUm.isFinite() || pixelSizeUm <= 0 || binning < 1) return null
        val left = requireNotNull(axisPixelX) + .5
        val top = requireNotNull(axisPixelY) + .5
        val marginX = min(left, widthPx - left); val marginY = min(top, heightPx - top)
        if (marginX <= 0 || marginY <= 0) return null
        val width = Math.toDegrees(2 * atan(marginX * pixelSizeUm * binning / 1000 / focalLengthMm))
        val height = Math.toDegrees(2 * atan(marginY * pixelSizeUm * binning / 1000 / focalLengthMm))
        return VerificationField(widthDeg = width, heightDeg = height,
            altitudeUpRollDeg = altitudeUpRollDeg?.takeIf(Double::isFinite))
    }

    companion object {
        fun fromSensorRoi(pixelSizeUm: Float, sensorWidth: Int, sensorHeight: Int, roi: Roi,
            roiSensorScale: Int, deviceId: String): ConnectedOpticsGeometry {
            val axis = runCatching { ReferencePixelMapper.sensorCenter(sensorWidth, sensorHeight, roi,
                roiSensorScale, roi.width, roi.height) }.getOrNull()
            return ConnectedOpticsGeometry(pixelSizeUm, roi.width, roi.height, roiSensorScale, deviceId,
                axis?.first?.minus(1), axis?.second?.minus(1), axis != null)
        }
    }
}

enum class PointingOpticsEndpoint { EYEPIECE, SENSOR }

/** A caller must obtain an explicit choice; the planning overlay's default is not a choice. */
data class PointingOpticsSelection(val train: OpticsTrainId, val endpoint: PointingOpticsEndpoint)

enum class PointingOpticsChange { TELESCOPE, IMAGE_INSTALLATION }

data class PointingOpticsConfiguration(
    val primary: OpticsTrainConfig,
    val secondary: OpticsTrainConfig,
    val telescopes: List<TelescopeSpec>,
    val eyepieces: List<EyepieceSpec>,
    val cameras: List<SensorSpec>,
    val primaryGeometry: ConnectedOpticsGeometry? = null,
    val secondaryGeometry: ConnectedOpticsGeometry? = null,
    val confirmedTrains: Set<OpticsTrainId> = emptySet(),
    val connectedDisplayName: String = "Connected camera"
) {
    fun train(id: OpticsTrainId) = if (id == OpticsTrainId.PRIMARY) primary else secondary
    fun geometry(id: OpticsTrainId) = if (id == OpticsTrainId.PRIMARY) primaryGeometry else secondaryGeometry
    fun sensorsFor(id: OpticsTrainId): List<SensorSpec> = buildList {
        geometry(id)?.sensor(connectedDisplayName)?.let(::add)
        addAll(cameras.filter { it.id != OpticsEquipment.CONNECTED_SENSOR_ID })
    }
    fun computation(id: OpticsTrainId) = train(id).compute(telescopes, eyepieces, sensorsFor(id))

    /** A solid centered frame cannot stand for an offset crop or a planning camera preset. */
    fun currentFrameCentered(id: OpticsTrainId): Boolean {
        if (id !in confirmedTrains) return false
        val config = train(id)
        if (config.mode == FovInstrumentMode.EYEPIECE) return computation(id)?.circleDeg != null
        val actual = geometry(id)?.takeIf { it.axisCentered } ?: return false
        val sensor = actual.sensor(connectedDisplayName) ?: return false
        val actualField = config.copy(sensorId = OpticsEquipment.CONNECTED_SENSOR_ID).compute(telescopes, eyepieces, listOf(sensor)) ?: return false
        val planned = computation(id) ?: return false
        return planned.rectWidthDeg?.let { abs(it - requireNotNull(actualField.rectWidthDeg)) < 1e-6 } == true &&
            planned.rectHeightDeg?.let { abs(it - requireNotNull(actualField.rectHeightDeg)) < 1e-6 } == true
    }

    /** No guide-key fallback or built-in 500 mm assumption for main reference solving. */
    fun primaryReferenceFocalLengthMm(): Float? {
        if (OpticsTrainId.PRIMARY !in confirmedTrains) return null
        val config = primary
        return config.focalLengthMm(telescopes)
            ?.takeIf { it.isFinite() && it > 0 && it <= Float.MAX_VALUE }
            ?.toFloat()
    }

    fun verificationField(selection: PointingOpticsSelection?, altitudeUpRollDeg: Double? = null): VerificationField? {
        selection ?: return null
        if (selection.train !in confirmedTrains) return null
        val config = train(selection.train)
        val expected = if (config.mode == FovInstrumentMode.EYEPIECE) PointingOpticsEndpoint.EYEPIECE else PointingOpticsEndpoint.SENSOR
        if (selection.endpoint != expected) return null
        val field = computation(selection.train)?.takeIf { it.hasOverlay } ?: return null
        return when (selection.endpoint) {
            PointingOpticsEndpoint.EYEPIECE -> field.circleDeg?.takeIf { it.isFinite() && it > 0 && it < 180 }
                ?.let { VerificationField(circleDiameterDeg = it) }
            PointingOpticsEndpoint.SENSOR -> {
                val actual = geometry(selection.train) ?: return null
                // The selected preset is for planning. Arrival uses the actual ROI and the
                // closest edge on every side of the physically mapped optical reference.
                actual.inscribedField(config.focalLengthMm(telescopes) ?: return null, altitudeUpRollDeg)
            }
        }
    }

    /** Compare physical choices only. Eyepiece specs, crop/binning and screen rotation are not axis changes. */
    fun changeTo(next: PointingOpticsConfiguration, id: OpticsTrainId): PointingOpticsChange? {
        val old = train(id); val new = next.train(id)
        if (old.telescopeId != new.telescopeId ||
            old.focalLengthMm(telescopes) != new.focalLengthMm(next.telescopes)) return PointingOpticsChange.TELESCOPE
        if (old.mode != new.mode || old.sensorId != new.sensorId &&
            (old.mode == FovInstrumentMode.SENSOR || new.mode == FovInstrumentMode.SENSOR)) return PointingOpticsChange.IMAGE_INSTALLATION
        if (old.mode == FovInstrumentMode.SENSOR && old.sensorId == OpticsEquipment.CONNECTED_SENSOR_ID &&
            geometry(id)?.deviceId != null && next.geometry(id)?.deviceId != null &&
            geometry(id)?.deviceId != next.geometry(id)?.deviceId) return PointingOpticsChange.IMAGE_INSTALLATION
        return null
    }

    companion object {
        fun confirmationKey(id: OpticsTrainId) = "star_map_${id.prefValue()}_pointing_optics_confirmed"
        fun load(prefs: OpticsPrefReader, primaryGeometry: ConnectedOpticsGeometry? = null,
            secondaryGeometry: ConnectedOpticsGeometry? = null, connectedDisplayName: String = "Connected camera"): PointingOpticsConfiguration =
            PointingOpticsConfiguration(
                StarMapOpticsPrefs.loadPrimary(prefs),
                StarMapOpticsPrefs.loadSecondary(prefs, secondaryGeometry?.sensor(connectedDisplayName) != null),
                UserOpticsCatalog.loadTelescopes(prefs.getString(UserOpticsCatalog.TELESCOPES_KEY, null)),
                UserOpticsCatalog.loadEyepieces(prefs.getString(UserOpticsCatalog.EYEPIECES_KEY, null)),
                UserOpticsCatalog.loadCameras(prefs.getString(UserOpticsCatalog.CAMERAS_KEY, null)),
                primaryGeometry, secondaryGeometry,
                OpticsTrainId.entries.filter { prefs.getBoolean(confirmationKey(it), false) }.toSet(),
                connectedDisplayName
            )
    }
}
