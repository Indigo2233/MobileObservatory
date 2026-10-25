package com.indigo.mobileobservatory.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.PointingReferenceCameraState
import com.indigo.mobileobservatory.pointing.PointingReferenceIssue
import com.indigo.mobileobservatory.pointing.PointingReferencePreflight
import com.indigo.mobileobservatory.pointing.PointingReferenceState
import com.indigo.mobileobservatory.accessories.AccessoryDeviceManager
import com.indigo.mobileobservatory.accessories.power.DewHeaterMode
import com.indigo.mobileobservatory.accessories.power.PowerInterfaceNames
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.camera.playerone.PlayerOneCamera
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
import com.indigo.mobileobservatory.camera.toupcam.FilterWheelInfo
import com.indigo.mobileobservatory.license.License
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.astrometry.FitsSolveHintReader
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.mount.MountModule
import com.indigo.mobileobservatory.mount.MountAccessMode
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.MountTrackingRate
import com.indigo.mobileobservatory.mount.MountProtocolType
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.mount.SkyWatcherMountMode
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.EquatorialEpoch
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.mount.SkyWatcherEquatorialMath
import com.indigo.mobileobservatory.sequence.AutofocusRun
import com.indigo.mobileobservatory.sequence.DeviceUnavailable
import com.indigo.mobileobservatory.sequence.SequenceEditorMode
import com.indigo.mobileobservatory.sequence.SequenceMosaicPlan
import com.indigo.mobileobservatory.sequence.SequenceEphemeris
import com.indigo.mobileobservatory.sequence.SequenceRuntime
import com.indigo.mobileobservatory.sequence.SequenceSettings
import com.indigo.mobileobservatory.sequence.SequenceSkyTarget
import com.indigo.mobileobservatory.sequence.SequenceWorld
import com.indigo.mobileobservatory.sequence.SessionFrame
import com.indigo.mobileobservatory.sequence.VirtualSequenceHardware
import com.indigo.mobileobservatory.sequence.VirtualSequenceStatus
import com.indigo.mobileobservatory.sequence.countStars
import com.indigo.mobileobservatory.sequence.medianHalfLightRadius
import com.indigo.mobileobservatory.sequence.monoLuma
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import com.indigo.mobileobservatory.mount.PrecisionGotoMath
import com.indigo.mobileobservatory.mount.PrecisionGotoProgress
import com.indigo.mobileobservatory.ui.components.RecordLimit
import com.indigo.mobileobservatory.ui.components.RecordLimitType
import com.indigo.mobileobservatory.util.DeterministicResourceCleaner
import com.indigo.mobileobservatory.util.FileLogger
import com.indigo.mobileobservatory.util.ImageUtils
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.recording.FitsSequenceWriter
import com.indigo.mobileobservatory.recording.Mp4Writer
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer
import com.indigo.mobileobservatory.recording.RecordingWriteOutcome
import com.indigo.mobileobservatory.recording.RecordingWriteSession
import com.indigo.mobileobservatory.stacking.LiveStackPhase
import com.indigo.mobileobservatory.stacking.LiveStackAdmission
import com.indigo.mobileobservatory.stacking.StackRoiContext
import com.indigo.mobileobservatory.stacking.StackRoiApplyPhase
import com.indigo.mobileobservatory.stacking.StackRoiApplyState
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.StackSessionKey
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.stacking.StackingCoordinator
import com.indigo.mobileobservatory.stacking.ScienceFrameProvenance
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import com.indigo.mobileobservatory.stacking.CalibrationRecordingContext
import com.indigo.mobileobservatory.settings.CameraDefaults
import com.indigo.mobileobservatory.settings.CoverDefaults
import com.indigo.mobileobservatory.settings.DeviceSettingsRepository
import com.indigo.mobileobservatory.settings.FocuserDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CaptureFormat { FITS, JPG }
enum class RecordFormat { SER, PSER, MP4, FITS }

typealias GuideStar = com.indigo.mobileobservatory.guide.GuideStar
typealias GuideCorrection = com.indigo.mobileobservatory.guide.GuideCorrection
typealias GuideHistoryPoint = com.indigo.mobileobservatory.guide.GuideHistoryPoint
typealias GuideAlgorithm = com.indigo.mobileobservatory.guide.GuideAlgorithm
typealias GuideCalibrationState = com.indigo.mobileobservatory.guide.GuideCalibrationState
typealias GuideCalibration = com.indigo.mobileobservatory.guide.GuideCalibration
private typealias GuideVector = com.indigo.mobileobservatory.guide.GuideVector
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application

    companion object {
        private const val TAG = "CameraVM"
        private const val GOTO_TOLERANCE_DEG = 0.05
        private const val GOTO_STABLE_SAMPLES = 2
        private const val MOTION_STABLE_TOLERANCE_DEG = 0.01
        private const val MOTION_STABLE_SAMPLES = 3
        private const val RECORD_QUEUE_CAPACITY = 8
    }

    val cameraManager = DahengCameraManager(application)
    val guideCameraManager = DahengCameraManager(
        context = application,
        sessionName = "guide",
        enableAccessories = false
    )
    val accessoryManager = AccessoryDeviceManager(application)
    private val frameProcessor = FrameProcessor()
    private val guideFrameProcessor = FrameProcessor()
    private val captureFrameProcessor = FrameProcessor()
    private val softwareBinning = SoftwareBinning()
    val stacking = StackingCoordinator(application, viewModelScope,
        captureTemperature = { calibrationTemperature() },
        isSourceBusy = { file ->
        currentRecordingFile?.let { active ->
            runCatching { file.canonicalFile == active.canonicalFile }.getOrDefault(file.absoluteFile == active.absoluteFile)
        } == true
    })
    @Volatile private var stackFrameGeometry: Triple<Int, Int, PixelFormat>? = null
    private val scienceProvenance = ScienceFrameProvenance()
    private val previewEpoch = CameraPreviewEpoch()
    @Volatile private var activePreviewTraits: PixelTraits? = null
    @Volatile private var recordingDisplayTraits: PixelTraits? = null
    private val _cameraCfa = MutableStateFlow(SerCfaInterpretation.AUTO)
    val cameraCfa = _cameraCfa.asStateFlow()
    private val _showCameraCfaDialog = MutableStateFlow(false)
    val showCameraCfaDialog = _showCameraCfaDialog.asStateFlow()
    private val _cameraCfaApplying = MutableStateFlow(false)
    val cameraCfaApplying = _cameraCfaApplying.asStateFlow()
    private var cameraCfaDialogIdentity: String? = null
    private var cameraCfaDialogLayout: String? = null
    private var requestedPreviewColorIdentity: String? = null
    private val previewColorReset = PreviewColorReset()
    private val cameraCfaChoices = java.util.concurrent.ConcurrentHashMap<String, Pair<SerCfaInterpretation, String>>()
    private val captureSettingsMutex = Mutex()
    private val pointingCaptureOwned = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingCaptureChanges = java.util.concurrent.atomic.AtomicInteger()
    private val _scienceCaptureReady = MutableStateFlow(false)
    val scienceCaptureReady = _scienceCaptureReady.asStateFlow()
    private val _liveStackPixelFormat = MutableStateFlow<PixelFormat?>(null)
    val liveStackPixelFormat: StateFlow<PixelFormat?> = _liveStackPixelFormat.asStateFlow()
    private val guideModule = com.indigo.mobileobservatory.guide.GuideModule()
    private val previewPipeline = PreviewPipeline(viewModelScope, frameProcessor, targetFps = 30)
    private val guidePreviewPipeline = PreviewPipeline(viewModelScope, guideFrameProcessor, targetFps = 12)
    private val autoExposureController = AutoExposureController()
    private val fitsWriter = FITSWriter()
    private val mountModule = MountModule(application, viewModelScope)
    val mountMotionState: StateFlow<MountMotionState> = mountModule.motionState
    val precisionGotoProgress: StateFlow<PrecisionGotoProgress> = mountModule.precisionGotoProgress
    private val astapRunner = AstapRunner(application)
    private val virtualSequenceHardware = if (BuildConfig.EMULATOR_TEST) {
        VirtualSequenceHardware()
    } else {
        null
    }
    private val noVirtualSequenceStatus = MutableStateFlow<VirtualSequenceStatus?>(null)
    val virtualSequenceStatus: StateFlow<VirtualSequenceStatus?> =
        virtualSequenceHardware?.status ?: noVirtualSequenceStatus.asStateFlow()
    val virtualSequenceHardwareSnapshot: SequenceHardwareSnapshot? =
        if (BuildConfig.EMULATOR_TEST) VirtualSequenceHardware.SNAPSHOT else null
    private val d50Manager = D50Manager(application)

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()
    val previewPerformance: StateFlow<PreviewPerformanceStats> = previewPipeline.performance

    val histogram: StateFlow<HistogramData?> = frameProcessor.histogram
    val focusScore: StateFlow<Float?> = frameProcessor.focusScore

    private val _focusAssistEnabled = MutableStateFlow(false)
    val focusAssistEnabled: StateFlow<Boolean> = _focusAssistEnabled.asStateFlow()

    private val _lp750PreviewEnabled = MutableStateFlow(false)
    val lp750PreviewEnabled: StateFlow<Boolean> = _lp750PreviewEnabled.asStateFlow()

    private val _focusZoomCenter = MutableStateFlow(Pair(0.5f, 0.5f))
    val focusZoomCenter: StateFlow<Pair<Float, Float>> = _focusZoomCenter.asStateFlow()

    private val _focusZoomFactor = MutableStateFlow(3f)
    val focusZoomFactor: StateFlow<Float> = _focusZoomFactor.asStateFlow()

    private val _focusHistory = MutableStateFlow<List<Float>>(emptyList())
    val focusHistory: StateFlow<List<Float>> = _focusHistory.asStateFlow()

    // Filter wheel state (delegates to FilterWheelController)
    private val fwController get() = accessoryManager.filterWheelController
    val filterWheelConnected: StateFlow<Boolean> = accessoryManager.filterWheelController.isConnected
    val filterWheelInfo: StateFlow<FilterWheelInfo?> = accessoryManager.filterWheelController.wheelInfo
    val filterWheelPosition: StateFlow<Int> = accessoryManager.filterWheelController.currentPosition
    val filterWheelSlotNames: StateFlow<List<String>> = accessoryManager.filterWheelController.slotNames
    val filterWheelMoving: StateFlow<Boolean> = accessoryManager.filterWheelController.isMoving
    val filterWheelBidirectional: StateFlow<Boolean> = accessoryManager.filterWheelController.bidirectional
    val accessoryDevices: StateFlow<List<AccessoryDeviceEntry>> = accessoryManager.devices
    val accessoryScanError: StateFlow<String?> = accessoryManager.scanError
    val activeFilterWheelDeviceId: StateFlow<Int?> = accessoryManager.activeFilterWheelDeviceId
    val activeFocuserDeviceId: StateFlow<Int?> = accessoryManager.activeFocuserDeviceId
    val activeCoverDeviceId: StateFlow<Int?> = accessoryManager.activeCoverDeviceId
    val activeRotatorDeviceId: StateFlow<Int?> = accessoryManager.activeRotatorDeviceId
    val activePowerBoxDeviceId: StateFlow<Int?> = accessoryManager.activePowerBoxDeviceId
    val powerBoxConnected = accessoryManager.powerBoxController.isConnected
    val powerBoxCapabilities = accessoryManager.powerBoxController.capabilities
    val powerBoxTelemetry = accessoryManager.powerBoxController.telemetry
    private val _powerBoxInterfaceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val powerBoxInterfaceNames: StateFlow<Map<String, String>> =
        _powerBoxInterfaceNames.asStateFlow()

    // EAF (Electric Auto Focuser) state
    private val eafCtrl get() = accessoryManager.focuserController
    val eafConnected: StateFlow<Boolean> = accessoryManager.focuserController.isConnected
    val eafInfo: StateFlow<EAFInfo?> = accessoryManager.focuserController.eafInfo
    val eafPosition: StateFlow<Int> = accessoryManager.focuserController.currentPosition
    val eafMoving: StateFlow<Boolean> = accessoryManager.focuserController.isMoving
    val eafTemperature: StateFlow<Float?> = accessoryManager.focuserController.temperature

    val coverConnected = accessoryManager.coverController.isConnected
    val coverState = accessoryManager.coverController.coverState
    val calibratorState = accessoryManager.coverController.calibratorState
    val calibratorBrightness = accessoryManager.coverController.brightness
    val calibratorMaxBrightness = accessoryManager.coverController.maxBrightness
    val coverDeviceInfo = accessoryManager.coverController.deviceInfo

    val rotatorConnected = accessoryManager.rotatorController.isConnected
    val rotatorAngle = accessoryManager.rotatorController.angle
    val rotatorPositionSteps = accessoryManager.rotatorController.positionSteps
    val rotatorMoving = accessoryManager.rotatorController.isMoving
    val rotatorStepsPerDegree = accessoryManager.rotatorController.stepsPerDegree
    val rotatorStepsPerDegreeFromBoard =
        accessoryManager.rotatorController.stepsPerDegreeFromBoard
    val rotatorSupportsStepConfiguration =
        accessoryManager.rotatorController.supportsStepConfiguration
    val rotatorReversed = accessoryManager.rotatorController.reversed
    val rotatorHold = accessoryManager.rotatorController.hold
    val rotatorSupportsHold = accessoryManager.rotatorController.supportsHold
    val rotatorDeviceInfo = accessoryManager.rotatorController.deviceInfo

    // Cooling / TEC state (active for any CoolingCapable camera)
    private val _coolingInfo = MutableStateFlow<CoolingInfo?>(null)
    val coolingInfo: StateFlow<CoolingInfo?> = _coolingInfo.asStateFlow()
    private val _coolerOn = MutableStateFlow(false)
    val coolerOn: StateFlow<Boolean> = _coolerOn.asStateFlow()
    private val _targetTempTenths = MutableStateFlow(0)
    val targetTempTenths: StateFlow<Int> = _targetTempTenths.asStateFlow()
    private val _sensorTempTenths = MutableStateFlow(0)
    val sensorTempTenths: StateFlow<Int> = _sensorTempTenths.asStateFlow()
    private val _tecVoltageTenths = MutableStateFlow(0)
    val tecVoltageTenths: StateFlow<Int> = _tecVoltageTenths.asStateFlow()
    private val _coolingPowerPct = MutableStateFlow(0f)
    val coolingPowerPct: StateFlow<Float> = _coolingPowerPct.asStateFlow()
    private val _tempHistory = MutableStateFlow<List<TempHistoryPoint>>(emptyList())
    val tempHistory: StateFlow<List<TempHistoryPoint>> = _tempHistory.asStateFlow()
    private val _rampStatus = MutableStateFlow("")
    val rampStatus: StateFlow<String> = _rampStatus.asStateFlow()

    private val _heaterSupported = MutableStateFlow(false)
    val heaterSupported: StateFlow<Boolean> = _heaterSupported.asStateFlow()
    private val _heaterLevel = MutableStateFlow(0)
    val heaterLevel: StateFlow<Int> = _heaterLevel.asStateFlow()
    private val _heaterMaxLevel = MutableStateFlow(0)
    val heaterMaxLevel: StateFlow<Int> = _heaterMaxLevel.asStateFlow()
    private val _fanSupported = MutableStateFlow(false)
    val fanSupported: StateFlow<Boolean> = _fanSupported.asStateFlow()
    private val _fanLevel = MutableStateFlow(0)
    val fanLevel: StateFlow<Int> = _fanLevel.asStateFlow()
    private val _fanMaxLevel = MutableStateFlow(0)
    val fanMaxLevel: StateFlow<Int> = _fanMaxLevel.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = cameraManager.connectionState
    val devices: StateFlow<List<DeviceEntry>> = cameraManager.devices

    private val _exposureUs = MutableStateFlow(10_000f)
    val exposureUs: StateFlow<Float> = _exposureUs.asStateFlow()

    private val _gain = MutableStateFlow(0f)
    val gain: StateFlow<Float> = _gain.asStateFlow()
    private val _gainCapability = MutableStateFlow<GainCapability?>(null)
    val gainCapability: StateFlow<GainCapability?> = _gainCapability.asStateFlow()
    private val _gainWriteInProgress = MutableStateFlow(false)
    val gainWriteInProgress: StateFlow<Boolean> = _gainWriteInProgress.asStateFlow()

    private val _usbBandwidth = MutableStateFlow<Int?>(null)
    val usbBandwidth: StateFlow<Int?> = _usbBandwidth.asStateFlow()
    private val _usbBandwidthRange = MutableStateFlow<IntRange?>(null)
    val usbBandwidthRange: StateFlow<IntRange?> = _usbBandwidthRange.asStateFlow()

    private val _offset = MutableStateFlow<Float?>(null)
    val offset: StateFlow<Float?> = _offset.asStateFlow()
    private val _offsetRange = MutableStateFlow<FloatRange?>(null)
    val offsetRange: StateFlow<FloatRange?> = _offsetRange.asStateFlow()
    private val _offsetLabel = MutableStateFlow("Offset")
    val offsetLabel: StateFlow<String> = _offsetLabel.asStateFlow()
    private val _offsetStep = MutableStateFlow(1f)
    val offsetStep: StateFlow<Float> = _offsetStep.asStateFlow()

    private val _pixelFormat = MutableStateFlow(PixelFormat.MONO8)
    val pixelFormat: StateFlow<PixelFormat> = _pixelFormat.asStateFlow()

    private val _supportedPixelFormats = MutableStateFlow<List<PixelFormat>>(listOf(PixelFormat.MONO8))
    val supportedPixelFormats: StateFlow<List<PixelFormat>> = _supportedPixelFormats.asStateFlow()

    private val _supportsHostRoi = MutableStateFlow(true)
    val supportsHostRoi: StateFlow<Boolean> = _supportsHostRoi.asStateFlow()
    private val _recordsLiveViewAsScience = MutableStateFlow(true)
    val recordsLiveViewAsScience: StateFlow<Boolean> = _recordsLiveViewAsScience.asStateFlow()

    private val _readoutMode = MutableStateFlow(ReadoutMode.NORMAL)
    val readoutMode: StateFlow<ReadoutMode> = _readoutMode.asStateFlow()

    private val _supportedReadoutModes = MutableStateFlow<List<ReadoutMode>>(listOf(ReadoutMode.NORMAL))
    val supportedReadoutModes: StateFlow<List<ReadoutMode>> = _supportedReadoutModes.asStateFlow()

    private val _nativeReadoutModes = MutableStateFlow<List<CameraNativeReadoutMode>>(emptyList())
    val nativeReadoutModes: StateFlow<List<CameraNativeReadoutMode>> = _nativeReadoutModes.asStateFlow()
    private val _nativeReadoutModeId = MutableStateFlow<String?>(null)
    val nativeReadoutModeId: StateFlow<String?> = _nativeReadoutModeId.asStateFlow()

    private val _detectedBitDepth = MutableStateFlow(8)
    val detectedBitDepth: StateFlow<Int> = _detectedBitDepth.asStateFlow()

    private val _roi = MutableStateFlow(Roi(0, 0, 1920, 1200))
    val roi: StateFlow<Roi> = _roi.asStateFlow()

    private val _binning = MutableStateFlow(1)
    val binning: StateFlow<Int> = _binning.asStateFlow()
    private val _binningUsesHardware = MutableStateFlow(false)
    val binningUsesHardware: StateFlow<Boolean> = _binningUsesHardware.asStateFlow()
    private val _binningUsesSoftware = MutableStateFlow(false)
    val binningUsesSoftware: StateFlow<Boolean> = _binningUsesSoftware.asStateFlow()
    private val _imagingWidth = MutableStateFlow(1920)
    val imagingWidth: StateFlow<Int> = _imagingWidth.asStateFlow()
    private val _imagingHeight = MutableStateFlow(1200)
    val imagingHeight: StateFlow<Int> = _imagingHeight.asStateFlow()

    private val _autoStretch = MutableStateFlow(true)
    val autoStretch: StateFlow<Boolean> = _autoStretch.asStateFlow()

    private val _flipH = MutableStateFlow(false)
    val flipH: StateFlow<Boolean> = _flipH.asStateFlow()

    private val _flipV = MutableStateFlow(false)
    val flipV: StateFlow<Boolean> = _flipV.asStateFlow()

    private val _rotation = MutableStateFlow(0)
    val rotation: StateFlow<Int> = _rotation.asStateFlow()

    private val _longExposureEnabled = MutableStateFlow(false)
    val longExposureEnabled: StateFlow<Boolean> = _longExposureEnabled.asStateFlow()

    private val _longExposureProgress = MutableStateFlow("")
    val longExposureProgress: StateFlow<String> = _longExposureProgress.asStateFlow()

    private val _exposureUiMinUs = MutableStateFlow(1f)
    val exposureUiMinUs: StateFlow<Float> = _exposureUiMinUs.asStateFlow()

    private val _exposureUiMaxUs = MutableStateFlow(ExposureLimits.SHORT_MAX_US)
    val exposureUiMaxUs: StateFlow<Float> = _exposureUiMaxUs.asStateFlow()

    private val _exposureCountdown = MutableStateFlow(0f)
    val exposureCountdown: StateFlow<Float> = _exposureCountdown.asStateFlow()

    private val _exposureProgressFraction = MutableStateFlow(0f)
    val exposureProgressFraction: StateFlow<Float> = _exposureProgressFraction.asStateFlow()

    @Volatile private var lastFrameArrivalMs = 0L
    private var exposureTimerJob: kotlinx.coroutines.Job? = null

    private val _autoExposureMode = MutableStateFlow(AutoExposureMode.OFF)
    val autoExposureMode: StateFlow<AutoExposureMode> = _autoExposureMode.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingFrameCount = MutableStateFlow(0)
    val recordingFrameCount: StateFlow<Int> = _recordingFrameCount.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _recordingBytes = MutableStateFlow(0L)
    val recordingBytes: StateFlow<Long> = _recordingBytes.asStateFlow()

    private val _previewPaused = MutableStateFlow(false)
    val previewPaused: StateFlow<Boolean> = _previewPaused.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    val guideConnectionState: StateFlow<ConnectionState> = guideCameraManager.connectionState
    val guideDevices: StateFlow<List<DeviceEntry>> = guideCameraManager.devices

    private val _guidePreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val guidePreviewBitmap: StateFlow<Bitmap?> = _guidePreviewBitmap.asStateFlow()

    private val _guideExposureUs = MutableStateFlow(1_000_000f)
    val guideExposureUs: StateFlow<Float> = _guideExposureUs.asStateFlow()

    private val _guideGain = MutableStateFlow(0f)
    val guideGain: StateFlow<Float> = _guideGain.asStateFlow()
    private val _guideGainCapability = MutableStateFlow<GainCapability?>(null)
    val guideGainCapability: StateFlow<GainCapability?> = _guideGainCapability.asStateFlow()
    private val _guideGainWriteInProgress = MutableStateFlow(false)
    val guideGainWriteInProgress: StateFlow<Boolean> = _guideGainWriteInProgress.asStateFlow()

    private val _guideFrameSize = MutableStateFlow(0 to 0)
    val guideFrameSize: StateFlow<Pair<Int, Int>> = _guideFrameSize.asStateFlow()
    private val _guideStar = MutableStateFlow<GuideStar?>(null)
    val guideStar: StateFlow<GuideStar?> = _guideStar.asStateFlow()

    private val _guideStars = MutableStateFlow<List<GuideStar>>(emptyList())
    val guideStars: StateFlow<List<GuideStar>> = _guideStars.asStateFlow()

    private val _guideReferenceStar = MutableStateFlow<GuideStar?>(null)
    val guideReferenceStar: StateFlow<GuideStar?> = _guideReferenceStar.asStateFlow()

    private val _guideReferenceStars = MutableStateFlow<List<GuideStar>>(emptyList())
    val guideReferenceStars: StateFlow<List<GuideStar>> = _guideReferenceStars.asStateFlow()

    private val _guideCorrection = MutableStateFlow<GuideCorrection?>(null)
    val guideCorrection: StateFlow<GuideCorrection?> = _guideCorrection.asStateFlow()

    private val _guideRunning = MutableStateFlow(false)
    val guideRunning: StateFlow<Boolean> = _guideRunning.asStateFlow()
    @Volatile private var guideStarLost = false
    private val pendingGuideDither = AtomicReference<Pair<Float, Float>?>(null)

    private val _guideCalibrating = MutableStateFlow(false)
    val guideCalibrating: StateFlow<Boolean> = _guideCalibrating.asStateFlow()

    private val _guideCalibrationState = MutableStateFlow(GuideCalibrationState.IDLE)
    val guideCalibrationState: StateFlow<GuideCalibrationState> = _guideCalibrationState.asStateFlow()

    private val _guideCalibration = MutableStateFlow<GuideCalibration?>(null)
    val guideCalibration: StateFlow<GuideCalibration?> = _guideCalibration.asStateFlow()

    private val _guideStatus = MutableStateFlow(app.getString(R.string.guide_camera_disconnected))
    val guideStatus: StateFlow<String> = _guideStatus.asStateFlow()

    private val _showGuideDevicePicker = MutableStateFlow(false)
    val showGuideDevicePicker: StateFlow<Boolean> = _showGuideDevicePicker.asStateFlow()

    private val _guideRaAggressiveness = MutableStateFlow(0.7f)
    val guideRaAggressiveness: StateFlow<Float> = _guideRaAggressiveness.asStateFlow()

    private val _guideDecAggressiveness = MutableStateFlow(0.7f)
    val guideDecAggressiveness: StateFlow<Float> = _guideDecAggressiveness.asStateFlow()

    private val _guideHistory = MutableStateFlow<List<GuideHistoryPoint>>(emptyList())
    val guideHistory: StateFlow<List<GuideHistoryPoint>> = _guideHistory.asStateFlow()

    private val _guideRaRmsPx = MutableStateFlow(0f)
    val guideRaRmsPx: StateFlow<Float> = _guideRaRmsPx.asStateFlow()

    private val _guideDecRmsPx = MutableStateFlow(0f)
    val guideDecRmsPx: StateFlow<Float> = _guideDecRmsPx.asStateFlow()

    private val _guideTotalRmsPx = MutableStateFlow(0f)
    val guideTotalRmsPx: StateFlow<Float> = _guideTotalRmsPx.asStateFlow()

    private val _guidePulseMsPerPx = MutableStateFlow(120f)
    val guidePulseMsPerPx: StateFlow<Float> = _guidePulseMsPerPx.asStateFlow()

    private val _guideMinMovePx = MutableStateFlow(0.15f)
    val guideMinMovePx: StateFlow<Float> = _guideMinMovePx.asStateFlow()

    private val _guideAlgorithm = MutableStateFlow(GuideAlgorithm.HYSTERESIS)
    val guideAlgorithm: StateFlow<GuideAlgorithm> = _guideAlgorithm.asStateFlow()

    private val _guideMultiStarEnabled = MutableStateFlow(true)
    val guideMultiStarEnabled: StateFlow<Boolean> = _guideMultiStarEnabled.asStateFlow()

    private val _guideCalibrationPulseMs = MutableStateFlow(1200)
    val guideCalibrationPulseMs: StateFlow<Int> = _guideCalibrationPulseMs.asStateFlow()

    private val _guideReverseRa = MutableStateFlow(false)
    val guideReverseRa: StateFlow<Boolean> = _guideReverseRa.asStateFlow()

    private val _guideReverseDec = MutableStateFlow(false)
    val guideReverseDec: StateFlow<Boolean> = _guideReverseDec.asStateFlow()

    private val pendingFrameSnapshot = AtomicReference<CompletableDeferred<CameraCfaLayout.Snapshot>?>(null)
    private val frameSnapshotMutex = Mutex()
    private val cameraDefaultsMutex = Mutex()
    private val gainWriteMutex = Mutex()
    private val exposureWriteMutex = Mutex()
    private val guideGainWriteMutex = Mutex()
    private val guideOperationLock = Any()
    private val auxiliaryCaptureOwned = java.util.concurrent.atomic.AtomicBoolean(false)
    private val auxiliaryCollector = AtomicReference<com.indigo.mobileobservatory.pointing.AuxiliaryFrameCollector?>(null)
    @Volatile private var auxiliaryCaptureJob: Job? = null
    private var pendingGuideSettings = 0
    private val _auxiliaryCaptureBusy = MutableStateFlow(false)
    val auxiliaryCaptureBusy = _auxiliaryCaptureBusy.asStateFlow()
    private val _guideCameraGeometry = MutableStateFlow<com.indigo.mobileobservatory.astro.ConnectedOpticsGeometry?>(null)
    val guideCameraGeometry = _guideCameraGeometry.asStateFlow()
    private val _guidePixelFormat = MutableStateFlow(PixelFormat.MONO8)
    val guidePixelFormat = _guidePixelFormat.asStateFlow()
    private val _guideSupportedPixelFormats = MutableStateFlow<List<PixelFormat>>(emptyList())
    val guideSupportedPixelFormats = _guideSupportedPixelFormats.asStateFlow()
    private val _guideReferenceReady = MutableStateFlow(false)
    val guideReferenceReady = _guideReferenceReady.asStateFlow()
    private val auxiliaryMotionMonitor = AtomicReference<com.indigo.mobileobservatory.pointing.AuxiliaryOpticalMotion?>(null)
    private val _auxiliaryOpticalMotion = MutableStateFlow(com.indigo.mobileobservatory.pointing.AuxiliaryOpticalMotionState())
    val auxiliaryOpticalMotion = _auxiliaryOpticalMotion.asStateFlow()
    private val guideDeliveryLock = Any()
    private val guideDeliveryTimes = LinkedHashMap<Pair<Long?, Long>, Pair<Long, Long>>()
    private var guideDeliveryElapsedMs = 0L
    private var guideDeliveryGeneration: Long? = null
    private val guideMeasurements = com.indigo.mobileobservatory.guide.GuideMeasurements()
    private val guideTasks by lazy { com.indigo.mobileobservatory.guide.GuideTaskRunner(viewModelScope,
        { direction, durationMs -> mountModule.setGuideRate(); mountModule.startGuidePulse(direction, durationMs) },
        { direction ->
            try { mountModule.stopGuidePulse(direction) }
            catch (failure: Exception) { runCatching { mountModule.stopAllMotion() }; throw failure }
        }) }
    @Volatile private var pendingGuideConnect = false
    private var lastGuideCorrectionMs = 0L
    private var lastRaGuideOutputPx = 0f
    private var lastDecGuideOutputPx = 0f
    private var lastRaGuideErrorPx = 0f
    private var lastDecGuideErrorPx = 0f
    @Volatile private var serWriter: SERWriter? = null
    @Volatile private var pserWriter: PSERWriter? = null
    @Volatile private var mp4Writer: Mp4Writer? = null
    @Volatile private var fitsSequenceWriter: FitsSequenceWriter? = null
    @Volatile private var currentRecordingFile: File? = null
    @Volatile private var recordingCaptureKey: StackSessionKey? = null
    @Volatile private var recordingCaptureChanged = false
    @Volatile private var recordingCaptureTemperature: Double? = null
    private data class RawRecordingLayout(val width: Int, val height: Int, val format: PixelFormat, val traits: PixelTraits)
    @Volatile private var rawRecordingLayout: RawRecordingLayout? = null
    private val recordingEnqueueLock = Any()
    private var recordingStartTime: Long = 0
    private val recordWriteQueue = LinkedBlockingQueue<FrameData>(RECORD_QUEUE_CAPACITY)
    private val recordBufferPool = ReusableByteArrayPool(RECORD_QUEUE_CAPACITY)
    private val recordDroppedFrames = AtomicLong(0)
    private val recordingLifecycleLock = Any()
    @Volatile private var recordingWriteSession: RecordingWriteSession<FrameData>? = null
    @Volatile private var recordingSaveToGallery = false
    private val _recordingFinalizing = MutableStateFlow(false)
    val recordingFinalizing = _recordingFinalizing.asStateFlow()
    private val _stackRoiApplyState = MutableStateFlow(StackRoiApplyState())
    val stackRoiApplyState = _stackRoiApplyState.asStateFlow()

    private val _captureFormat = MutableStateFlow(CaptureFormat.JPG)
    val captureFormat: StateFlow<CaptureFormat> = _captureFormat.asStateFlow()

    private val _recordFormat = MutableStateFlow(RecordFormat.MP4)
    val recordFormat: StateFlow<RecordFormat> = _recordFormat.asStateFlow()

    private val _recordLimit = MutableStateFlow(RecordLimit(RecordLimitType.NONE, 0))
    val recordLimit: StateFlow<RecordLimit> = _recordLimit.asStateFlow()

    private val _targetName = MutableStateFlow(CaptureTargetNames.DEFAULT)
    val targetName: StateFlow<String> = _targetName.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    private val prefs = application.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val deviceSettings = DeviceSettingsRepository(application)
    private val _sequenceSettings = MutableStateFlow(deviceSettings.sequenceSettings())
    val sequenceSettings: StateFlow<SequenceSettings> = _sequenceSettings.asStateFlow()

    fun updateSequenceSettings(settings: SequenceSettings) {
        _sequenceSettings.value = settings
        deviceSettings.saveSequenceSettings(settings)
    }

    val mountConnectionState = mountModule.mountConnectionState
    val mountHost = mountModule.mountHost
    val mountPort = mountModule.mountPort
    val synScanHost = mountModule.synScanHost
    val synScanPort = mountModule.synScanPort
    val skyWatcherMountMode = mountModule.skyWatcherMountMode
    val mountTransport = mountModule.mountTransport
    val mountUsbDevices = mountModule.mountUsbDevices
    val mountUsbDeviceId = mountModule.mountUsbDeviceId
    val mountBaudRate = mountModule.mountBaudRate
    val mountBluetoothDevices = mountModule.mountBluetoothDevices
    val mountBluetoothAddress = mountModule.mountBluetoothAddress
    val mountProtocol = mountModule.mountProtocol
    val mountDetectedInfo = mountModule.mountDetectedInfo
    val mountCoordinates = mountModule.mountCoordinates
    val mountAccessMode = mountModule.mountAccessMode
    val mountPointingCapabilities = mountModule.pointingCapabilities
    val mountModelRevision = mountModule.modelRevision
    val mountAxisObservation = mountModule.axisObservation
    val mountSite = mountModule.mountSite
    val mountTime = mountModule.mountTime
    val sequenceRuntime: SequenceRuntime by lazy {
        val files = getApplication<Application>()
        SequenceRuntime(
            templatesDir = File(files.filesDir, "sequences"),
            sessionsDir = File(files.getExternalFilesDir("captures"), "Sequences"),
            scope = viewModelScope,
            hardware = virtualSequenceHardware ?: CameraSequenceHardware(this),
            world = virtualSequenceHardware ?: object : SequenceWorld {
                override fun altitudeDeg(): Double? = sequenceTargetAltitude()
                override fun minutesToMeridian(): Double? = sequenceMinutesToMeridian()
                override fun temperatureC(): Double? {
                    if (cameraManager.activeCamera !is CoolingCapable) return null
                    return sensorTempTenths.value / 10.0
                }
                override fun lastHfr(): Double? = sequenceRuntime.frames.value.lastOrNull()?.hfr
                override fun filterName(): String? = currentFilterName()
                override fun observerLatitudeDeg(): Double? = mountSite.value?.latitudeDeg
                override fun observerLongitudeDeg(): Double? = mountSite.value?.longitudeDeg
                override fun sunAltitudeDeg(): Double? {
                    val site = mountSite.value ?: return null
                    return SequenceEphemeris.sunAltitudeDeg(
                        Instant.now(),
                        ObserverSite(site.latitudeDeg, site.longitudeDeg)
                    )
                }
                override fun moonAltitudeDeg(): Double? {
                    val site = mountSite.value ?: return null
                    return SequenceEphemeris.moonAltitudeDeg(
                        Instant.now(),
                        ObserverSite(site.latitudeDeg, site.longitudeDeg)
                    )
                }
                override fun moonIlluminationPct(): Double = SequenceEphemeris.moonIlluminationPct(Instant.now())
            }
        )
    }
    private val _sequenceSkyPick = MutableStateFlow<SequenceSkyTarget?>(null)
    val sequenceSkyPick: StateFlow<SequenceSkyTarget?> = _sequenceSkyPick.asStateFlow()
    private val _sequenceMosaicPlan = MutableStateFlow<SequenceMosaicPlan?>(null)
    val sequenceMosaicPlan: StateFlow<SequenceMosaicPlan?> = _sequenceMosaicPlan.asStateFlow()
    private val _lastPlateSolvePositionAngleDeg = MutableStateFlow(
        prefs.getFloat("last_plate_solve_position_angle_deg", Float.NaN)
            .takeIf { it.isFinite() }
            ?.toDouble()
    )
    val lastPlateSolvePositionAngleDeg: StateFlow<Double?> =
        _lastPlateSolvePositionAngleDeg.asStateFlow()

    val pointingRepository = com.indigo.mobileobservatory.pointing.PointingRepository(app)
    private val rotatorPointingGuard = com.indigo.mobileobservatory.pointing.RotatorPointingGuard {
        // The primary camera's eccentricity is not measured; do not assume rotation preserves
        // either its optical axis or roll. Other optical paths and their hand-eye fits remain valid.
        val hadPrimaryCalibration = pointingRepository.relationships.value.any {
            it.valid && (it.source.opticalPathId == "primary" || it.target.opticalPathId == "primary")
        }
        pointingRepository.invalidateOpticalPath("primary", "rotator_changed")
        _lastPlateSolvePositionAngleDeg.value = null
        prefs.edit().remove("last_plate_solve_position_angle_deg").apply()
        if (hadPrimaryCalibration) _statusMessage.value = app.getString(R.string.wfp_rotator_changed)
    }
    private val _pointingOpticsRevision = MutableStateFlow(0L)
    val pointingOpticsRevision = _pointingOpticsRevision.asStateFlow()
    private val pointingOpticsPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("star_map_") || key == "plate_focal_length_mm" || key in setOf(
                com.indigo.mobileobservatory.astro.UserOpticsCatalog.TELESCOPES_KEY,
                com.indigo.mobileobservatory.astro.UserOpticsCatalog.EYEPIECES_KEY,
                com.indigo.mobileobservatory.astro.UserOpticsCatalog.CAMERAS_KEY))) {
            _pointingOpticsRevision.value = _pointingOpticsRevision.value + 1
        }
    }

    fun pointingOpticsConfiguration(): com.indigo.mobileobservatory.astro.PointingOpticsConfiguration {
        val camera = cameraManager.activeCamera
        val info = camera?.cameraInfo
        val primary = if (camera != null && info?.pixelSizeUm != null) com.indigo.mobileobservatory.astro.ConnectedOpticsGeometry.fromSensorRoi(
            info.pixelSizeUm, info.sensorWidth, info.sensorHeight, camera.currentRoi, camera.roiSensorScale,
            CameraReferenceIdentity.of(camera)) else null
        return com.indigo.mobileobservatory.astro.PointingOpticsConfiguration.load(
            com.indigo.mobileobservatory.astro.AndroidOpticsPrefs(prefs), primary, guideCameraGeometry.value,
            app.getString(R.string.connected_camera_sensor))
    }

    fun primaryReferenceCameraId(): String? = cameraManager.activeCamera?.let(com.indigo.mobileobservatory.camera.CameraReferenceIdentity::of)
    fun auxiliaryReferenceCameraId(): String? = guideCameraManager.activeCamera?.let(com.indigo.mobileobservatory.camera.CameraReferenceIdentity::of)

    fun rememberSequenceSkyTarget(
        name: String,
        raHours: Double,
        decDeg: Double,
        positionAngleDeg: Double = 0.0
    ) {
        _sequenceSkyPick.value = SequenceSkyTarget(name, raHours, decDeg, positionAngleDeg)
    }

    fun addSequenceSkyTarget(
        name: String,
        raHours: Double,
        decDeg: Double,
        positionAngleDeg: Double = 0.0,
        destination: SequenceEditorMode = sequenceRuntime.mode.value
    ) {
        rememberSequenceSkyTarget(name, raHours, decDeg, positionAngleDeg)
        sequenceRuntime.addTarget(name, raHours, decDeg, positionAngleDeg, destination)
    }

    fun addSequenceMosaic(
        plan: SequenceMosaicPlan,
        destination: SequenceEditorMode
    ) {
        _sequenceMosaicPlan.value = plan
        rememberSequenceSkyTarget(
            plan.name,
            plan.centerRaHours,
            plan.centerDecDeg,
            plan.positionAngleDeg
        )
        sequenceRuntime.addMosaic(plan, destination)
    }

    fun recordPlateSolveResult(result: PlateSolveResult) {
        val angle = result.rotationDeg?.takeIf { result.success && it.isFinite() } ?: return
        val normalized = ((angle % 360.0) + 360.0) % 360.0
        _lastPlateSolvePositionAngleDeg.value = normalized
        prefs.edit().putFloat("last_plate_solve_position_angle_deg", normalized.toFloat()).apply()
    }

    val mountBusy = mountModule.mountBusy
    val mountSupportsSync: Boolean
        get() = mountModule.supportsSync
    val mountSupportsTimeSync: Boolean
        get() = mountModule.supportsTimeSync
    val mountConnectionMessage = mountModule.mountConnectionMessage
    val mountMoveStatus = mountModule.mountMoveStatus
    val mountSlewRate = mountModule.mountSlewRate
    val mountTrackingEnabled = mountModule.mountTrackingEnabled
    val mountTrackingRate = mountModule.mountTrackingRate
    private var frameCount = 0L
    private var fpsTimestamp = System.nanoTime()
    private var autoExpFrameSkip = 0
    fun setTargetName(name: String) {
        _targetName.value = CaptureTargetNames.canonical(name)
    }

    fun toggleCaptureFormat() {
        _captureFormat.value = when (_captureFormat.value) {
            CaptureFormat.FITS -> CaptureFormat.JPG
            CaptureFormat.JPG -> CaptureFormat.FITS
        }
    }

    fun setCaptureFormat(label: String) {
        _captureFormat.value = when (label) {
            "FITS" -> CaptureFormat.FITS
            else -> CaptureFormat.JPG
        }
    }

    fun setRecordFormat(label: String) {
        _recordFormat.value = when (label) {
            "PSER" -> RecordFormat.PSER
            "MP4" -> RecordFormat.MP4
            "FITS" -> RecordFormat.FITS
            else -> RecordFormat.SER
        }
    }

    fun toggleRecordFormat() {
        _recordFormat.value = when (_recordFormat.value) {
            RecordFormat.SER -> RecordFormat.PSER
            RecordFormat.PSER -> RecordFormat.MP4
            RecordFormat.MP4 -> RecordFormat.FITS
            RecordFormat.FITS -> RecordFormat.SER
        }
    }

    fun setRecordLimit(limit: RecordLimit) {
        _recordLimit.value = limit
    }

    private val _showPlayer = MutableStateFlow(false)
    val showPlayer: StateFlow<Boolean> = _showPlayer.asStateFlow()

    fun openPlayer() {
        _showPlayer.value = true
    }

    fun closePlayer() {
        _showPlayer.value = false
    }

    /** A file remains unavailable until the recording queue and writer have closed. */
    fun getActiveRecordingFile(): File? = currentRecordingFile

    fun openPlanetaryStack(file: File) {
        if (file.absoluteFile == currentRecordingFile?.absoluteFile) {
            stacking.open(StackMode.PLANETARY)
            stacking.report("recording_active")
            return
        }
        closePlayer()
        stacking.open(StackMode.PLANETARY, file)
    }

    fun getRecordingsDir(): File? {
        return getApplication<Application>().getExternalFilesDir("recordings")
    }
    
    fun getCapturesDir(): File? {
        return getApplication<Application>().getExternalFilesDir("captures")
    }

    fun showDevicePicker() { _showDevicePicker.value = true }
    fun hideDevicePicker() { _showDevicePicker.value = false }

    private var coolingJobs = mutableListOf<kotlinx.coroutines.Job>()
    private var environmentJobs = mutableListOf<kotlinx.coroutines.Job>()

    init {
        prefs.registerOnSharedPreferenceChangeListener(pointingOpticsPreferenceListener)
        _guideExposureUs.value = prefs.getFloat("guide_exposure_us", 1_000_000f)
            .coerceIn(50_000f, 5_000_000f)
        _guideRaAggressiveness.value = prefs.getFloat("guide_ra_aggressiveness", 0.7f)
            .coerceIn(0.1f, 1.0f)
        _guideDecAggressiveness.value = prefs.getFloat("guide_dec_aggressiveness", 0.7f)
            .coerceIn(0.1f, 1.0f)
        _guideMinMovePx.value = prefs.getFloat("guide_min_move_px", 0.15f)
            .coerceIn(0.05f, 1.0f)
        cameraManager.register()
        guideCameraManager.register()
        accessoryManager.register()
        mountModule.register()

        viewModelScope.launch {
            combine(rotatorConnected, rotatorMoving, rotatorAngle, rotatorReversed, rotatorStepsPerDegree) {
                    connected, moving, angle, reversed, steps ->
                com.indigo.mobileobservatory.pointing.RotatorPointingGuard.State(connected, moving, angle, reversed, steps)
            }.distinctUntilChanged().collect(rotatorPointingGuard::observe)
        }

        viewModelScope.launch {
            mountModule.activeUsbMountDeviceId.collect { deviceId ->
                accessoryManager.setExcludedUsbDeviceIds(
                    deviceId?.let { setOf(it) } ?: emptySet()
                )
            }
        }

        viewModelScope.launch {
            mountModule.statusMessage.collect { message ->
                if (message.isNotBlank()) _statusMessage.value = message
            }
        }
        viewModelScope.launch {
            combine(filterWheelMoving, filterWheelPosition, filterWheelConnected) { moving, position, connected ->
                Triple(moving, position, connected)
            }.distinctUntilChanged().drop(1).collect { (moving, _, _) ->
                invalidateStackSettings()
                if (!moving) cameraManager.activeCamera?.let { cam -> changeCaptureSettings(cam) {} }
            }
        }
        viewModelScope.launch {
            powerBoxCapabilities
                .map { it?.identity }
                .distinctUntilChanged()
                .collect { identity ->
                    _powerBoxInterfaceNames.value = if (identity == null) {
                        emptyMap()
                    } else {
                        withContext(Dispatchers.IO) {
                            deviceSettings.powerInterfaceNames(identity)
                        }
                    }
                }
        }
        viewModelScope.launch {
            cameraManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val cam = cameraManager.activeCamera ?: return@collect
                        if (_stackRoiApplyState.value.phase != StackRoiApplyPhase.APPLYING)
                            _stackRoiApplyState.value = StackRoiApplyState()
                        _exposureUs.value = cam.currentExposureUs
                        _gainCapability.value = cam.gainCapability
                        _gain.value = cam.currentGain
                        syncOffsetCapability(cam)
                        _pixelFormat.value = cam.currentPixelFormat
                        _supportedPixelFormats.value = cam.supportedPixelFormats
                        _supportsHostRoi.value = cam.supportsHostRoi
                        _recordsLiveViewAsScience.value = cam.recordsLiveViewAsScience
                        _readoutMode.value = cam.currentReadoutMode
                        _supportedReadoutModes.value = cam.supportedReadoutModes
                        (cam as? CameraNativeReadoutModeCapable)?.let { readoutCapable ->
                            _nativeReadoutModes.value = readoutCapable.supportedNativeReadoutModes
                            _nativeReadoutModeId.value = readoutCapable.currentNativeReadoutModeId
                        } ?: run {
                            _nativeReadoutModes.value = emptyList()
                            _nativeReadoutModeId.value = null
                        }
                        withContext(Dispatchers.IO) {
                            applyCameraDefaults(cam)
                            applySavedUsbBandwidth(cam)
                        }
                        _gain.value = cam.currentGain
                        _gainCapability.value = cam.gainCapability
                        _pixelFormat.value = cam.currentPixelFormat
                        _readoutMode.value = cam.currentReadoutMode
                        (cam as? CameraNativeReadoutModeCapable)?.let { readoutCapable ->
                            _nativeReadoutModes.value = readoutCapable.supportedNativeReadoutModes
                            _nativeReadoutModeId.value = readoutCapable.currentNativeReadoutModeId
                        }
                        syncOffsetCapability(cam)
                        syncUsbBandwidthCapability(cam)
                        _roi.value = cam.currentRoi
                        syncBinningState(cam, appBin = (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1)
                        _longExposureEnabled.value = cam.longExposureEnabled
                        refreshExposureUiLimits()
                        _statusMessage.value = ""
                        bindCoolingFlows(cam)
                        bindEnvironmentFlows(cam)
                        _previewPaused.value = false
                        startPreview()
                    }
                    is ConnectionState.Error -> {
                        _statusMessage.value = state.message
                    }
                    is ConnectionState.Disconnected -> {
                        previewEpoch.invalidate()
                        _showCameraCfaDialog.value = false
                        _cameraCfa.value = SerCfaInterpretation.AUTO
                        stacking.live.invalidate("camera_disconnected")
                        scienceProvenance.clear()
                        _scienceCaptureReady.value = false
                        stackFrameGeometry = null
                        _liveStackPixelFormat.value = null
                        previewPipeline.stop()
                        _previewBitmap.value = null
                        unbindCoolingFlows()
                        unbindEnvironmentFlows()
                        syncOffsetCapability(null)
                        syncUsbBandwidthCapability(null)
                        _nativeReadoutModes.value = emptyList()
                        _nativeReadoutModeId.value = null
                        _gainCapability.value = null
                        _gainWriteInProgress.value = false
                        _supportsHostRoi.value = true
                        _recordsLiveViewAsScience.value = true
                        resetBinningState()
                    }
                    is ConnectionState.Enumerating -> {
                        _statusMessage.value = app.getString(R.string.searching_cameras)
                    }
                    is ConnectionState.Connecting -> {
                        _statusMessage.value = app.getString(R.string.connecting)
                    }
                }
            }
        }

        var appliedFocuserId: String? = null
        viewModelScope.launch {
            eafInfo.collect { info ->
                val deviceId = info?.name ?: return@collect
                if (deviceId != appliedFocuserId) {
                    appliedFocuserId = deviceId
                    if (deviceSettings.hasFocuserDefaults(deviceId)) {
                        applyFocuserDefaults(deviceSettings.focuserDefaults(deviceId))
                    }
                }
            }
        }

        viewModelScope.launch {
            guideCameraManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val cam = guideCameraManager.activeCamera ?: return@collect
                        cam.setExposureTime(_guideExposureUs.value)
                        _guideExposureUs.value = cam.currentExposureUs
                        _guideGainCapability.value = cam.gainCapability
                        _guideGain.value = cam.currentGain
                        stopGuideOperations(invalidateCalibration = true)
                        updateGuideCameraGeometry(cam)
                        _guidePixelFormat.value = cam.currentPixelFormat
                        _guideSupportedPixelFormats.value = cam.supportedPixelFormats.filterNot { it.isRgb }
                        _guideStatus.value = app.getString(R.string.guide_camera_connected_status, cam.cameraInfo?.name.orEmpty())
                        startGuidePreview()
                    }
                    is ConnectionState.Error -> {
                        stopGuideOperations(invalidateCalibration = true)
                        auxiliaryCaptureJob?.cancel(CancellationException(state.message))
                        auxiliaryCollector.get()?.fail(IllegalStateException(state.message))
                        _guideCameraGeometry.value = null
                        _guideSupportedPixelFormats.value = emptyList()
                        _guideReferenceReady.value = false
                        _guideStatus.value = state.message
                        _guideRunning.value = false
                    }
                    is ConnectionState.Disconnected -> {
                        stopGuideOperations(invalidateCalibration = true)
                        auxiliaryCaptureJob?.cancel(CancellationException("Auxiliary camera disconnected"))
                        auxiliaryCollector.get()?.fail()
                        _guideCameraGeometry.value = null
                        _guideSupportedPixelFormats.value = emptyList()
                        _guideReferenceReady.value = false
                        guidePreviewPipeline.stop()
                        _guidePreviewBitmap.value = null
                        _guideStar.value = null
                        _guideStars.value = emptyList()
                        _guideReferenceStar.value = null
                        _guideReferenceStars.value = emptyList()
                        _guideGainCapability.value = null
                        _guideGainWriteInProgress.value = false
                        _guideCorrection.value = null
                        _guideRunning.value = false
                        _guideCalibrating.value = false
                        _guideStatus.value = app.getString(R.string.guide_camera_disconnected)
                    }
                    is ConnectionState.Enumerating -> {
                        _guideStatus.value = app.getString(R.string.searching_guide_cameras)
                    }
                    is ConnectionState.Connecting -> {
                        _guideStatus.value = app.getString(R.string.connecting_guide_camera)
                    }
                }
            }
        }

        viewModelScope.launch {
            mountConnectionState.collect { state ->
                if (state !is com.indigo.mobileobservatory.mount.MountConnectionState.Connected &&
                    (_guideRunning.value || _guideCalibrating.value || _guideCalibration.value != null)) {
                    stopGuideOperations(invalidateCalibration = true)
                    _guideStatus.value = app.getString(R.string.connect_mount_before_guiding)
                }
            }
        }

        viewModelScope.launch {
            guideCameraManager.devices.collect { devices ->
                if (pendingGuideConnect && devices.isNotEmpty()) {
                    pendingGuideConnect = false
                    kotlinx.coroutines.delay(300)
                    requestGuideConnect()
                }
            }
        }

        viewModelScope.launch {
            frameProcessor.focusScore.collect { score ->
                if (score != null && _focusAssistEnabled.value) {
                    val history = _focusHistory.value.toMutableList()
                    history.add(score)
                    if (history.size > 100) history.removeAt(0)
                    _focusHistory.value = history
                }
            }
        }
    }

    private val resourceCleaner by lazy {
        DeterministicResourceCleaner(
            ::cancelOwnedJobs,
            ::unregisterOwnedReceiver,
            { closeRecordingResources(saveToGallery = false) },
            ::closeOwnedDevices
        )
    }

    private fun cancelOwnedJobs() {
        previewEpoch.invalidate()
        stacking.close()
        viewModelScope.coroutineContext.cancelChildren()
        previewPipeline.close()
        guidePreviewPipeline.close()
        stopExposureTimer()
        mountModule.cancelJobs()
        coolingJobs.forEach { it.cancel() }
        coolingJobs.clear()
        _guideRunning.value = false
        _guideCalibrating.value = false
        pendingGuideConnect = false
    }

    private fun unregisterOwnedReceiver() {
        prefs.unregisterOnSharedPreferenceChangeListener(pointingOpticsPreferenceListener)
        runCatching {
            mountModule.unregisterReceiver()
        }.onFailure { Log.w(TAG, "Mount USB receiver cleanup failed", it) }
    }

    private fun closeOwnedDevices() {
        runCatching { disconnectGuideCamera() }
            .onFailure { Log.e(TAG, "Guide camera cleanup failed", it) }
        runCatching { cameraManager.unregister() }
            .onFailure { Log.e(TAG, "Camera manager cleanup failed", it) }
        runCatching { guideCameraManager.unregister() }
            .onFailure { Log.e(TAG, "Guide camera manager cleanup failed", it) }
        runCatching { accessoryManager.unregister() }
            .onFailure { Log.e(TAG, "Accessory manager cleanup failed", it) }
        runCatching { mountModule.closeController() }
            .onFailure { Log.e(TAG, "Mount transport cleanup failed", it) }
    }

    override fun onCleared() {
        resourceCleaner.close()
        super.onCleared()
    }

    fun setMountHost(value: String) = mountModule.setMountHost(value)
    fun setMountAccessMode(value: MountAccessMode) = mountModule.setMountAccessMode(value)
    fun setMountLocalSite(latitudeDeg: Double, longitudeDeg: Double) = mountModule.setMountLocalSite(latitudeDeg, longitudeDeg)
    fun setMountPort(value: String) = mountModule.setMountPort(value)
    fun setSynScanHost(value: String) = mountModule.setSynScanHost(value)
    fun setSynScanPort(value: String) = mountModule.setSynScanPort(value)
    fun setSkyWatcherMountMode(value: SkyWatcherMountMode) = mountModule.setSkyWatcherMountMode(value)
    fun setMountTransport(value: MountTransportType) = mountModule.setMountTransport(value)
    fun setMountProtocol(value: MountProtocolType) = mountModule.setMountProtocol(value)
    fun setMountUsbDevice(value: Int) = mountModule.setMountUsbDevice(value)
    fun setMountBaudRate(value: String) = mountModule.setMountBaudRate(value)
    fun setMountBluetoothDevice(value: String) = mountModule.setMountBluetoothDevice(value)
    fun scanMountUsbDevices() = mountModule.scanMountUsbDevices()
    fun scanMountBluetoothDevices() = mountModule.scanMountBluetoothDevices()
    fun connectMount() = mountModule.connectMount()
    fun cancelMountConnection() = mountModule.cancelMountConnection()
    fun disconnectMount() = mountModule.disconnectMount()
    fun readMountSite() = mountModule.readMountSite()
    fun syncPhoneSiteToMount(latitudeDeg: Double, longitudeDeg: Double) =
        mountModule.syncPhoneSiteToMount(latitudeDeg, longitudeDeg)
    fun readMountTime() = mountModule.readMountTime()
    fun syncPhoneTimeToMount(epochMillis: Long = System.currentTimeMillis()) =
        mountModule.syncPhoneTimeToMount(epochMillis)
    fun gotoMountTarget(
        name: String,
        raHours: Double,
        decDeg: Double,
        frame: String = "JNOW"
    ) {
        val (jnowRa, jnowDec) = EquatorialEpoch.toJnowHours(raHours, decDeg, frame)
        mountModule.gotoMountTarget(name, jnowRa, jnowDec)
    }

    fun syncMountToTarget(
        name: String,
        raHours: Double,
        decDeg: Double,
        frame: String = "JNOW"
    ) {
        val (jnowRa, jnowDec) = EquatorialEpoch.toJnowHours(raHours, decDeg, frame)
        mountModule.syncMountToTarget(name, jnowRa, jnowDec)
    }

    fun startPrecisionGoto(
        name: String,
        raHours: Double,
        decDeg: Double,
        frame: String = "JNOW",
        toleranceArcmin: Double = PrecisionGotoMath.TOLERANCE_ARCMIN
    ): Boolean {
        if (connectionState.value !is ConnectionState.Connected) {
            return mountModule.rejectPrecisionGoto(name, app.getString(R.string.precision_goto_need_camera))
        }
        val preflightFailure = runCatching { synchronized(recordingLifecycleLock) { requirePointingReferenceReady() } }.exceptionOrNull()
        if (preflightFailure != null) return mountModule.rejectPrecisionGoto(name,
            preflightFailure.message ?: app.getString(R.string.wfp_reference_busy))
        val (jnowRa, jnowDec) = EquatorialEpoch.toJnowHours(raHours, decDeg, frame)
        return mountModule.startPrecisionGoto(
            name = name,
            raHours = jnowRa,
            decDeg = jnowDec,
            toleranceArcmin = PrecisionGotoMath.clampToleranceArcmin(toleranceArcmin),
            captureAndSolve = { hint -> captureAndSolveForPrecisionGoto(hint) }
        )
    }

    private suspend fun captureAndSolveForPrecisionGoto(hint: MountCoordinates?): MountCoordinates {
        val observation = capturePointingReference(hint)
        val (jnowRa, jnowDec) = EquatorialEpoch.j2000DegToJnowHours(observation.direction.raDeg, observation.direction.decDeg)
        return MountCoordinates(raHours = jnowRa, decDeg = jnowDec)
    }

    suspend fun runPointingCalibrationMoves(targets: List<com.indigo.mobileobservatory.pointing.PhoneCatalogStar>,
        sample: suspend () -> Unit) = runPointingCalibrationMoves(targets, sample, beforeMove = {})

    suspend fun runPointingCalibrationMoves(targets: List<com.indigo.mobileobservatory.pointing.PhoneCatalogStar>,
        sample: suspend () -> Unit, beforeMove: () -> Unit) {
        require(targets.size in 2..4)
        val camera = synchronized(recordingLifecycleLock) { requirePointingReferenceReady() }
        val acquisitionIdentity = CameraReferenceIdentity.of(camera)
        val site = com.indigo.mobileobservatory.pointing.PhoneSiteProvider.currentSite(app)
        mountModule.verifyCalibrationEnvironment(site)
        val initialModel = mountModule.modelRevision.value
        mountModule.runCalibrationMotion { slew ->
            for (target in targets) {
                check(mountModule.modelRevision.value == initialModel && cameraManager.activeCamera === camera &&
                    CameraReferenceIdentity.of(camera) == acquisitionIdentity) {
                    app.getString(R.string.wfp_configuration_changed)
                }
                val horizontal = com.indigo.mobileobservatory.astro.CoordinateTransform.j2000ToTopocentric(
                    EquatorialCoordinates(target.raDeg, target.decDeg), Instant.now(), site, null)
                check(horizontal.altitudeDeg in 20.0..80.0) { app.getString(R.string.wfp_target_not_visible) }
                val (ra, dec) = EquatorialEpoch.j2000DegToJnowHours(target.raDeg, target.decDeg)
                // A later point can become unavailable after the preceding sample/settings edit.
                // Never begin that move on the assumption that a connected camera can sample.
                synchronized(recordingLifecycleLock) { requirePointingReferenceReady(expectedCamera = camera) }
                beforeMove()
                slew(target.name ?: "HIP ${target.hip}", MountCoordinates(ra, dec))
                sample()
            }
        }
    }

    suspend fun sequencePlateSolve(): Pair<Double, Double> {
        val solved = captureAndSolveForPrecisionGoto(mountCoordinates.value)
        val j2000 = EquatorialEpoch.jnowToJ2000(
            EquatorialCoordinates(solved.raHours * 15.0, solved.decDeg)
        )
        return (j2000.raDeg / 15.0).mod(24.0) to j2000.decDeg
    }
    fun moveMountRaBy(distanceDeg: Double, east: Boolean, rateDegPerSec: Double) =
        mountModule.moveMountRaBy(distanceDeg, east, rateDegPerSec)
    fun stopMountRaMove() = mountModule.stopMountRaMove()
    fun stopMountMotion() {
        stopGuideOperations()
        _guideStatus.value = app.getString(R.string.guiding_stopped)
        mountModule.stopMountMotion()
    }
    fun startMountManualMove(direction: MountDirection) = mountModule.startMountManualMove(direction)
    fun stopMountManualMove(direction: MountDirection? = null) = mountModule.stopMountManualMove(direction)
    fun setMountSlewRate(rate: MountSlewRate) = mountModule.setMountSlewRate(rate)
    fun setMountTracking(enabled: Boolean) = mountModule.setMountTracking(enabled)
    fun setMountTrackingRate(rate: MountTrackingRate) = mountModule.setMountTrackingRate(rate)
    fun goMountHome() = mountModule.goMountHome()
    fun setMountHomeHere() = mountModule.setMountHomeHere()
    fun readMountCoordinates() = mountModule.readMountCoordinates()
    fun connectCamera(index: Int = 0) {
        val entry = cameraManager.devices.value.firstOrNull { it.index == index }
        if (entry != null && (guideCameraManager.holdsUsbDevice(entry.usbDevice) ||
                CameraSessionIsolation.incompatible(entry.brand, activeCameraBrand(guideCameraManager)))) {
            _statusMessage.value = app.getString(R.string.wfa_sdk_single_camera); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _statusMessage.value = app.getString(R.string.connecting)
                cameraManager.openCamera(index)
            } catch (e: Throwable) {
                Log.e(TAG, "connectCamera failed", e)
                _statusMessage.value = app.getString(R.string.generic_error_detail, e.message.orEmpty())
            }
        }
    }

    fun connectCameraBySn(sn: String) {
        val entry = cameraManager.devices.value.firstOrNull { it.serialNumber == sn }
        if (entry != null && CameraSessionIsolation.incompatible(entry.brand, activeCameraBrand(guideCameraManager))) {
            _statusMessage.value = app.getString(R.string.wfa_sdk_single_camera)
            return
        }
        if (entry != null && guideCameraManager.holdsUsbDevice(entry.usbDevice)) {
            _statusMessage.value = app.getString(R.string.camera_in_use_as_guide)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _statusMessage.value = app.getString(R.string.connecting_serial, sn)
                FileLogger.i(TAG, "connectCameraBySn sn=$sn")
                cameraManager.openCameraBySn(sn)
            } catch (e: Throwable) {
                Log.e(TAG, "connectCameraBySn failed", e)
                _statusMessage.value = app.getString(R.string.generic_error_detail, e.message.orEmpty())
            }
        }
    }

    fun requestConnect() {
        cameraManager.enumerateDevices()
        _showDevicePicker.value = true
    }

    /** Manual, session-only demo; never replaces an active or pending real connection. */
    fun startSimulatedCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!cameraManager.openSimulatedCamera(app.getString(R.string.sim_camera_name))) {
                _statusMessage.value = app.getString(R.string.sim_camera_disconnect_first)
            }
        }
    }

    fun stopSimulatedCamera() {
        if (cameraManager.activeCamera is SimulatedCamera) disconnectCamera()
    }

    fun disconnectCamera() {
        previewEpoch.invalidate()
        stopRecording()
        stopExposureTimer()
        previewPipeline.stop()
        cameraManager.closeCamera()
        _previewBitmap.value = null
        _statusMessage.value = app.getString(R.string.disconnected)
    }

    fun showGuideDevicePicker() { _showGuideDevicePicker.value = true }
    fun hideGuideDevicePicker() { _showGuideDevicePicker.value = false }

    fun camerasAvailableToMain(devices: List<DeviceEntry>): List<DeviceEntry> =
        devices.filterNot { guideCameraManager.holdsUsbDevice(it.usbDevice) ||
            CameraSessionIsolation.incompatible(it.brand, activeCameraBrand(guideCameraManager)) }

    fun camerasAvailableToGuide(devices: List<DeviceEntry>): List<DeviceEntry> =
        devices.filterNot { cameraManager.holdsUsbDevice(it.usbDevice) ||
            CameraSessionIsolation.incompatible(it.brand, activeCameraBrand(cameraManager)) }

    private fun activeCameraBrand(manager: DahengCameraManager): CameraBrand? = when (manager.activeCamera) {
        is com.indigo.mobileobservatory.camera.toupcam.ToupcamCamera -> CameraBrand.TOUPCAM
        is com.indigo.mobileobservatory.camera.qhyccd.QhyCamera -> CameraBrand.QHY
        is com.indigo.mobileobservatory.camera.zwo.ZwoAsiCamera -> CameraBrand.ZWO
        is PlayerOneCamera -> CameraBrand.PLAYERONE
        else -> null
    }

    private fun updateGuideCameraGeometry(cam: Camera) {
        _guideReferenceReady.value = cam.isOpen.value && cam.sciencePixelTraits != null &&
            !cam.currentPixelFormat.isRgb && !cam.requiresHighBitScienceCapture
        val info = cam.cameraInfo ?: return
        _guideCameraGeometry.value = info.pixelSizeUm?.takeIf { it > 0 && it.isFinite() }?.let {
            com.indigo.mobileobservatory.astro.ConnectedOpticsGeometry.fromSensorRoi(it, info.sensorWidth,
                info.sensorHeight, cam.currentRoi, cam.roiSensorScale, CameraReferenceIdentity.of(cam))
        }
    }

    fun requestGuideConnect() {
        val listed = guideCameraManager.devices.value
        val devs = camerasAvailableToGuide(listed)
        when {
            listed.isEmpty() -> {
                pendingGuideConnect = true
                guideCameraManager.enumerateDevices()
            }
            devs.isEmpty() -> {
                pendingGuideConnect = false
                _guideStatus.value = app.getString(R.string.select_different_guide_camera)
                _showGuideDevicePicker.value = true
            }
            devs.size == 1 -> {
                connectGuideCameraBySn(devs[0].serialNumber)
            }
            else -> {
                _showGuideDevicePicker.value = true
            }
        }
    }

    fun connectGuideCameraBySn(sn: String) {
        if (auxiliaryCaptureOwned.get()) {
            _guideStatus.value = app.getString(R.string.wfa_capture_busy); return
        }
        val entry = guideCameraManager.devices.value.firstOrNull { it.serialNumber == sn }
        if (entry != null && CameraSessionIsolation.incompatible(entry.brand, activeCameraBrand(cameraManager))) {
            _guideStatus.value = app.getString(R.string.wfa_sdk_single_camera); return
        }
        if (entry != null && cameraManager.holdsUsbDevice(entry.usbDevice)) {
            _guideStatus.value = app.getString(R.string.select_different_guide_camera)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _guideStatus.value = app.getString(R.string.connecting_guide_serial, sn)
                guideCameraManager.openCameraBySn(sn)
            } catch (e: Throwable) {
                Log.e(TAG, "connectGuideCameraBySn failed", e)
                _guideStatus.value = app.getString(R.string.guide_camera_error_detail, e.message.orEmpty())
            }
        }
    }

    fun disconnectGuideCamera() {
        stopGuideOperations(invalidateCalibration = true)
        auxiliaryCaptureJob?.cancel(CancellationException("Auxiliary camera disconnected"))
        auxiliaryCollector.get()?.fail()
        _guideCameraGeometry.value = null
        _guideReferenceReady.value = false
        _guideRunning.value = false
        guidePreviewPipeline.stop()
        runCatching { guideCameraManager.activeCamera?.stopCapture() }
        guideCameraManager.closeCamera()
        _guidePreviewBitmap.value = null
        _guideStar.value = null
        _guideStars.value = emptyList()
        _guideReferenceStar.value = null
        _guideReferenceStars.value = emptyList()
        _guideCorrection.value = null
        _guideCalibrating.value = false
        _guideStatus.value = app.getString(R.string.guide_camera_disconnected)
    }

    fun setGuideExposure(us: Float) {
        val requestedUs = us.coerceIn(50_000f, 5_000_000f)
        applyGuideSettings { cam ->
            cam?.setExposureTime(requestedUs)
            _guideExposureUs.value = cam?.currentExposureUs ?: requestedUs
            prefs.edit().putFloat("guide_exposure_us", _guideExposureUs.value).apply()
        }
    }

    fun setGuideGain(value: Float) {
        applyGuideSettings { cam ->
            cam ?: return@applyGuideSettings
            cam.setGain(value)
            _guideGain.value = cam.currentGain
            _guideGainCapability.value = cam.gainCapability
        }
    }

    fun setGuidePixelFormat(format: PixelFormat) {
        applyGuideSettings { cam ->
            require(cam != null && !format.isRgb && format in cam.supportedPixelFormats)
            cam.setPixelFormat(format)
            _guidePixelFormat.value = cam.currentPixelFormat
        }
    }

    private fun applyGuideSettings(write: (Camera?) -> Unit) {
        synchronized(guideOperationLock) {
            if (auxiliaryCaptureOwned.get()) {
                _guideStatus.value = app.getString(R.string.wfa_capture_busy); return
            }
            pendingGuideSettings++
            stopGuideOperations()
            _guideReferenceStar.value = null
            _guideReferenceStars.value = emptyList()
        }
        invalidateAuxiliaryMotion()
        val cam = guideCameraManager.activeCamera
        viewModelScope.launch(Dispatchers.IO) {
            _guideGainWriteInProgress.value = true
            try {
                guideGainWriteMutex.withLock {
                    if (guideCameraManager.activeCamera !== cam) return@withLock
                    val resume = cam?.isCapturing?.value == true
                    if (resume) cam?.stopCapture()
                    check(cam?.isCapturing?.value != true) { "Auxiliary acquisition did not stop" }
                    write(cam)
                    if (resume && guideCameraManager.activeCamera === cam) startGuidePreview()
                    cam?.let(::updateGuideCameraGeometry)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { _guideStatus.value = app.getString(R.string.guide_camera_error_detail, t.message.orEmpty()) }
            finally {
                synchronized(guideOperationLock) { pendingGuideSettings-- }
                _guideGainWriteInProgress.value = synchronized(guideOperationLock) { pendingGuideSettings > 0 }
            }
        }
    }

    fun lockGuideStar() {
        if (_guideCalibrating.value) return
        if (_guideRunning.value) stopGuideOperations()
        val star = _guideStar.value
        if (star == null) {
            _guideStatus.value = app.getString(R.string.no_star_detected)
            return
        }
        val refs = if (_guideMultiStarEnabled.value) {
            _guideStars.value.take(12).ifEmpty { listOf(star) }
        } else {
            listOf(star)
        }
        _guideReferenceStar.value = star
        _guideReferenceStars.value = refs
        _guideCorrection.value = null
        resetGuideAlgorithmState()
        _guideStatus.value = app.getString(R.string.guide_lock_set, refs.size)
    }

    fun clearGuideLock() {
        stopGuideOperations()
        _guideReferenceStar.value = null
        _guideReferenceStars.value = emptyList()
        _guideCorrection.value = null
        resetGuideAlgorithmState()
        _guideStatus.value = app.getString(R.string.guide_lock_cleared)
    }

    fun guideLocked(): Boolean = _guideRunning.value && !guideStarLost && guideMeasurementIsFresh()

    private fun guideMeasurementIsFresh(): Boolean {
        val measurement = guideMeasurements.latest ?: return false
        return android.os.SystemClock.elapsedRealtime() - measurement.deliveredMs <= (_guideExposureUs.value / 1000).toLong() * 2 + 1500
    }

    private fun stopGuideOperations(invalidateCalibration: Boolean = false) = synchronized(guideOperationLock) {
        _guideRunning.value = false
        _guideCalibrating.value = false
        guideStarLost = true
        guideTasks.cancel()
        pendingGuideDither.set(null)
        resetGuideAlgorithmState()
        lastGuideCorrectionMs = 0L
        if (_guideCalibrationState.value == GuideCalibrationState.RUNNING) {
            _guideCalibration.value = null
            _guideCalibrationState.value = GuideCalibrationState.IDLE
        }
        if (invalidateCalibration) {
            _guideCalibration.value = null
            _guideCalibrationState.value = GuideCalibrationState.IDLE
            _guideReferenceStar.value = null
            _guideReferenceStars.value = emptyList()
            guideMeasurements.forgetSamples()
        }
    }

    fun requestGuideDither(radiusPx: Double) {
        val angle = Math.random() * 2.0 * Math.PI
        val radius = Math.random() * radiusPx.coerceAtLeast(0.0)
        pendingGuideDither.set(
            (kotlin.math.cos(angle) * radius).toFloat() to (kotlin.math.sin(angle) * radius).toFloat()
        )
    }

    fun setGuideRunning(enabled: Boolean) {
        synchronized(guideOperationLock) {
            if (enabled && auxiliaryCaptureOwned.get()) {
                _guideStatus.value = app.getString(R.string.wfa_capture_busy); return
            }
            if (enabled) {
                if (_guideCalibrating.value || guideTasks.busy || pendingGuideSettings > 0 || _guideGainWriteInProgress.value) {
                    _guideStatus.value = app.getString(R.string.guide_operation_busy); return
                }
                if (guideCameraManager.activeCamera == null) {
                    _guideStatus.value = app.getString(R.string.connect_guide_camera_first)
                    return
                }
                if (_guideReferenceStar.value == null) {
                    lockGuideStar()
                }
                if (_guideReferenceStar.value == null) return
                if (_guideCalibrationState.value != GuideCalibrationState.COMPLETE || _guideCalibration.value == null || guideModule.projectError(_guideCalibration.value!!, 0f, 0f) == null) {
                    _guideStatus.value = app.getString(R.string.calibrate_before_guiding)
                    return
                }
                if (!mountModule.isConnected) {
                    _guideStatus.value = app.getString(R.string.connect_mount_before_guiding)
                    return
                }
                if (mountAccessMode.value != com.indigo.mobileobservatory.mount.MountAccessMode.READ_WRITE) {
                    _guideStatus.value = app.getString(R.string.guide_mount_read_only)
                    return
                }
                if (!_guideRunning.value) {
                    clearGuideHistory()
                }
                _guideStatus.value = app.getString(R.string.guiding)
            } else {
                stopGuideOperations()
                _guideStatus.value = app.getString(R.string.guiding_stopped)
            }
            _guideRunning.value = enabled
        }
    }

    fun setGuideRaAggressiveness(value: Float) {
        _guideRaAggressiveness.value = value.coerceIn(0.1f, 1.0f)
        prefs.edit().putFloat("guide_ra_aggressiveness", _guideRaAggressiveness.value).apply()
    }

    fun setGuideDecAggressiveness(value: Float) {
        _guideDecAggressiveness.value = value.coerceIn(0.1f, 1.0f)
        prefs.edit().putFloat("guide_dec_aggressiveness", _guideDecAggressiveness.value).apply()
    }

    fun clearGuideHistory() {
        _guideHistory.value = emptyList()
        _guideRaRmsPx.value = 0f
        _guideDecRmsPx.value = 0f
        _guideTotalRmsPx.value = 0f
    }

    fun setGuidePulseMsPerPx(value: Float) {
        _guidePulseMsPerPx.value = value.coerceIn(20f, 500f)
    }

    fun setGuideMinMovePx(value: Float) {
        // PHD2-style deadband: useful values are almost always under 1 px.
        _guideMinMovePx.value = value.coerceIn(0.05f, 1.0f)
        prefs.edit().putFloat("guide_min_move_px", _guideMinMovePx.value).apply()
    }

    fun setGuideAlgorithm(algorithm: GuideAlgorithm) {
        _guideAlgorithm.value = algorithm
        resetGuideAlgorithmState()
    }

    fun setGuideMultiStarEnabled(enabled: Boolean) {
        _guideMultiStarEnabled.value = enabled
        if (_guideReferenceStar.value != null) {
            lockGuideStar()
        }
    }

    fun setGuideCalibrationPulseMs(value: Int) {
        _guideCalibrationPulseMs.value = value.coerceIn(300, 5000)
    }

    fun setGuideReverseRa(enabled: Boolean) {
        _guideReverseRa.value = enabled
    }

    fun setGuideReverseDec(enabled: Boolean) {
        _guideReverseDec.value = enabled
    }

    fun startGuideCalibration() {
        synchronized(guideOperationLock) {
            if (auxiliaryCaptureOwned.get()) {
                _guideStatus.value = app.getString(R.string.wfa_capture_busy); return
            }
            if (guideCameraManager.activeCamera == null) {
                _guideStatus.value = app.getString(R.string.connect_guide_camera_first)
                return
            }
            if (!mountModule.isConnected) {
                _guideStatus.value = app.getString(R.string.connect_mount_before_calibration)
                return
            }
            if (mountAccessMode.value != com.indigo.mobileobservatory.mount.MountAccessMode.READ_WRITE) {
                _guideStatus.value = app.getString(R.string.guide_mount_read_only)
                return
            }
            if (_guideCalibrating.value || _guideRunning.value || guideTasks.busy || pendingGuideSettings > 0 || _guideGainWriteInProgress.value) {
                _guideStatus.value = app.getString(R.string.guide_operation_busy); return
            }
            _guideCalibrating.value = true
            val started = guideTasks.launch {
                _guideRunning.value = false
                _guideCalibrating.value = true
                _guideCalibrationState.value = GuideCalibrationState.RUNNING
                _guideCalibration.value = null
                resetGuideAlgorithmState()
                val pulseMs = _guideCalibrationPulseMs.value
                try {
                    mountModule.setGuideRate()
                    _guideStatus.value = app.getString(R.string.calibration_locking_reference)
                    val reference = awaitGuideStars()
                    if (reference.isEmpty()) error(app.getString(R.string.no_guide_stars_detected))

                    _guideStatus.value = app.getString(R.string.calibration_ra_east)
                    pulseMount(MountDirection.EAST, pulseMs)
                    kotlinx.coroutines.delay(settleDelayMs())
                    val eastStars = awaitGuideStarsAfterMotion()
                    if (eastStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_ra_pulse))
                    val eastShift = measureStarShift(reference, eastStars)
                        ?: error(app.getString(R.string.unable_match_ra_stars))
                    val eastVector = GuideVector(
                        eastShift.x / pulseMs,
                        eastShift.y / pulseMs
                    )
                    if (eastVector.length * pulseMs < 1.5f) error(app.getString(R.string.ra_calibration_too_small))

                    _guideStatus.value = app.getString(R.string.calibration_ra_return)
                    pulseMount(MountDirection.WEST, pulseMs)
                    kotlinx.coroutines.delay(settleDelayMs())
                    val decReferenceStars = awaitGuideStarsAfterMotion()
                    if (decReferenceStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_ra_return))

                    _guideStatus.value = app.getString(R.string.calibration_dec_north)
                    pulseMount(MountDirection.NORTH, pulseMs)
                    kotlinx.coroutines.delay(settleDelayMs())
                    val northStars = awaitGuideStarsAfterMotion()
                    if (northStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_dec_pulse))
                    val northShift = measureStarShift(decReferenceStars, northStars)
                        ?: error(app.getString(R.string.unable_match_dec_stars))
                    val northVector = GuideVector(
                        northShift.x / pulseMs,
                        northShift.y / pulseMs
                    )
                    if (northVector.length * pulseMs < 1.5f) error(app.getString(R.string.dec_calibration_too_small))

                    _guideStatus.value = app.getString(R.string.calibration_dec_return)
                    pulseMount(MountDirection.SOUTH, pulseMs)
                    kotlinx.coroutines.delay(settleDelayMs())
                    val finalReference = awaitGuideStarsAfterMotion()
                    if (finalReference.isEmpty()) error(app.getString(R.string.no_guide_stars_after_dec_return))

                    val calibration = GuideCalibration(
                        eastXPerMs = eastVector.x,
                        eastYPerMs = eastVector.y,
                        northXPerMs = northVector.x,
                        northYPerMs = northVector.y,
                        pulseMs = pulseMs
                    )
                    check(guideModule.projectError(calibration, 0f, 0f) != null) { app.getString(R.string.guide_calibration_invalid) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    _guideCalibration.value = calibration
                    _guideCalibrationState.value = GuideCalibrationState.COMPLETE
                    _guideReferenceStars.value = finalReference
                    _guideReferenceStar.value = finalReference.firstOrNull()
                    _guideStatus.value = app.getString(R.string.calibration_complete_rates, _guideCalibration.value?.eastRatePxPerSec ?: 0f, _guideCalibration.value?.northRatePxPerSec ?: 0f)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    _guideCalibration.value = null
                    _guideCalibrationState.value = GuideCalibrationState.FAILED
                    _guideStatus.value = app.getString(R.string.calibration_failed_detail, e.message.orEmpty())
                    runCatching { mountModule.stopAllMotion() }
                } finally {
                    _guideCalibrating.value = false
                }
            }
            if (!started) { _guideCalibrating.value = false; _guideStatus.value = app.getString(R.string.guide_operation_busy) }
        }
    }

    fun clearGuideCalibration() {
        stopGuideOperations()
        _guideCalibration.value = null
        _guideCalibrationState.value = GuideCalibrationState.IDLE
        resetGuideAlgorithmState()
        _guideStatus.value = app.getString(R.string.guide_calibration_cleared)
    }

    private fun startGuidePreview() {
        val cam = guideCameraManager.activeCamera ?: return
        synchronized(guideDeliveryLock) {
            guideDeliveryTimes.clear(); guideDeliveryElapsedMs = android.os.SystemClock.elapsedRealtime()
            guideDeliveryGeneration = null
        }
        auxiliaryMotionMonitor.get()?.let {
            if (auxiliaryCaptureOwned.get()) it.expectAcquisitionRestart() else it.invalidate()
            _auxiliaryOpticalMotion.value = it.state
        }

        val measurementRun = guideMeasurements.startRun()
        val previewRun = guidePreviewPipeline.start(
            onProcessed = processed@ { frame ->
                if (guideCameraManager.activeCamera !== cam) return@processed
                val previousSize = _guideFrameSize.value
                if (previousSize.first > 0 && previousSize != (frame.width to frame.height) &&
                    (_guideRunning.value || _guideCalibrating.value || _guideCalibration.value != null)) stopGuideOperations(invalidateCalibration = true)
                _guideFrameSize.value = frame.width to frame.height
                _guidePreviewBitmap.value = guidePreviewPipeline.frame.value?.bitmap
                val stars = detectGuideStars(frame)
                observeAuxiliaryMotionFrame(cam, frame, stars)
                val usable = guideModule.usableStars(stars)
                if (guideMeasurements.processed(frame.captureGeneration, frame.frameId, usable, measurementRun) == null) return@processed
                _guideStars.value = usable
                _guideStar.value = usable.firstOrNull()
                processGuideCorrection(usable, badQuality = stars.isNotEmpty() && usable.isEmpty())
            },
            recycleBuffer = cam::recycleBuffer
        )

        cam.startCapture(object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                guideMeasurements.delivered(frame.captureGeneration, frame.frameId, android.os.SystemClock.elapsedRealtime(), measurementRun)
                if (auxiliaryMotionMonitor.get() != null) synchronized(guideDeliveryLock) {
                    val end = android.os.SystemClock.elapsedRealtime()
                    guideDeliveryTimes[frame.captureGeneration to frame.frameId] = guideDeliveryElapsedMs to end
                    guideDeliveryElapsedMs = end; guideDeliveryGeneration = frame.captureGeneration
                    while (guideDeliveryTimes.size > 8) guideDeliveryTimes.remove(guideDeliveryTimes.keys.first())
                }
                auxiliaryCollector.get()?.accept(frame, cam.sciencePixelTraits,
                    android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
                guidePreviewPipeline.submit(frame, cam.sciencePixelTraits, expectedGeneration = previewRun, rejectedRecycler = cam::recycleBuffer)
            }
        })
    }

    fun setAuxiliaryMotionMonitoring(enabled: Boolean, site: ObserverSite? = null, trackingSky: Boolean = false) {
        val previousEpoch = _auxiliaryOpticalMotion.value.epoch
        val monitor = if (enabled) com.indigo.mobileobservatory.pointing.AuxiliaryOpticalMotion(requireNotNull(site), trackingSky) else null
        auxiliaryMotionMonitor.set(monitor)
        synchronized(guideDeliveryLock) { guideDeliveryTimes.clear(); guideDeliveryElapsedMs = android.os.SystemClock.elapsedRealtime() }
        _auxiliaryOpticalMotion.value = monitor?.state ?: com.indigo.mobileobservatory.pointing.AuxiliaryOpticalMotionState(previousEpoch + 1)
    }

    private fun invalidateAuxiliaryMotion() {
        auxiliaryMotionMonitor.get()?.let { it.invalidate(); _auxiliaryOpticalMotion.value = it.state }
    }

    fun tickAuxiliaryMotion(nowElapsedMs: Long): com.indigo.mobileobservatory.pointing.AuxiliaryOpticalMotionState {
        auxiliaryMotionMonitor.get()?.let {
            _auxiliaryOpticalMotion.value = it.tick(nowElapsedMs, (_guideExposureUs.value / 1000).toLong() + 1500)
        }
        return _auxiliaryOpticalMotion.value
    }

    private fun observeAuxiliaryMotionFrame(cam: Camera, frame: FrameData, stars: List<GuideStar>) {
        val monitor = auxiliaryMotionMonitor.get() ?: return
        val time = synchronized(guideDeliveryLock) { guideDeliveryTimes.remove(frame.captureGeneration to frame.frameId) }
        if (time == null || frame.captureGeneration == null || frame.captureGeneration != cam.scienceCaptureGeneration ||
            guideCameraManager.activeCamera !== cam) { monitor.invalidate(); _auxiliaryOpticalMotion.value = monitor.state; return }
        try {
            val psf = com.indigo.mobileobservatory.pointing.OpticalPsfSampler.sample(frame, cam.sciencePixelTraits,
                stars.map { it.x.toDouble() to it.y.toDouble() })
            val evidence = com.indigo.mobileobservatory.pointing.OpticalFrameStability(frame.frameId, time.first, time.second,
                (cam.currentExposureUs / 1000).toLong(), requireNotNull(frame.captureGeneration), frame.width, frame.height, psf)
            val key = "${guideCameraManager.claimedUsbDevice?.deviceName}/${cam.cameraInfo?.serialNumber}/${cam.currentRoi}/${frame.width}x${frame.height}/${frame.pixelFormat}"
            _auxiliaryOpticalMotion.value = monitor.ingest(evidence, key)
        } catch (_: IllegalArgumentException) { monitor.invalidate(); _auxiliaryOpticalMotion.value = monitor.state }
    }

    private fun detectGuideStars(frame: FrameData, maxStars: Int = 16): List<GuideStar> {
        val traits = guideCameraManager.activeCamera?.sciencePixelTraits
        val bits = traits?.validBits?.coerceIn(8, 16) ?: frame.pixelFormat.nativeBits
        val maximum = when (traits?.alignment) {
            com.indigo.mobileobservatory.stacking.SampleAlignment.LEFT -> ((1 shl bits) - 1) shl (16 - bits)
            com.indigo.mobileobservatory.stacking.SampleAlignment.RIGHT -> (1 shl bits) - 1
            else -> null
        }
        return guideModule.detectStars(frame, maxStars, maximum)
    }

    private suspend fun awaitGuideStars(
        timeoutMs: Long = (_guideExposureUs.value / 1000).toLong() * 3 + 4000,
        afterSequence: Long = -1L,
        notBeforeMs: Long = android.os.SystemClock.elapsedRealtime() - (_guideExposureUs.value / 1000).toLong() * 2 - 1000
    ): List<GuideStar> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val measurement = guideMeasurements.fresh(afterSequence, notBeforeMs)
            if (measurement != null && measurement.stars.isNotEmpty()) {
                return if (_guideMultiStarEnabled.value) measurement.stars.take(12) else measurement.stars.take(1)
            }
            kotlinx.coroutines.delay(100)
        }
        return emptyList()
    }

    private suspend fun awaitGuideStarsAfterMotion(): List<GuideStar> = awaitGuideStars(
        afterSequence = guideMeasurements.boundary(),
        notBeforeMs = android.os.SystemClock.elapsedRealtime() + (_guideExposureUs.value / 1000).toLong() + 150)

    private fun measureStarShift(referenceStars: List<GuideStar>, currentStars: List<GuideStar>) =
        guideModule.matchShift(referenceStars, currentStars)

    private fun measureGuideError(currentStars: List<GuideStar>): GuideVector? {
        val references = if (_guideMultiStarEnabled.value) {
            _guideReferenceStars.value.ifEmpty { _guideReferenceStar.value?.let { listOf(it) } ?: emptyList() }
        } else {
            _guideReferenceStar.value?.let { listOf(it) } ?: emptyList()
        }
        return measureStarShift(references, currentStars)
    }

    private fun projectGuideError(calibration: GuideCalibration, dx: Float, dy: Float) =
        guideModule.projectError(calibration, dx, dy)

    private fun applyGuideAlgorithm(errorMs: Float, raAxis: Boolean): Float =
        guideModule.filter(
            errorMs = errorMs,
            raAxis = raAxis,
            algorithm = _guideAlgorithm.value,
            aggressiveness = if (raAxis) _guideRaAggressiveness.value else _guideDecAggressiveness.value
        )

    private fun resetGuideAlgorithmState() = synchronized(guideOperationLock) { guideModule.resetFilter() }

    private fun appendGuideHistory(timestampMs: Long, raErrorPx: Float, decErrorPx: Float) {
        val points = (_guideHistory.value + GuideHistoryPoint(timestampMs, raErrorPx, decErrorPx))
            .takeLast(180)
        _guideHistory.value = points
        val rms = guideModule.rms(points)
        _guideRaRmsPx.value = rms.raPx
        _guideDecRmsPx.value = rms.decPx
        _guideTotalRmsPx.value = rms.totalPx
    }
    private fun settleDelayMs(): Long {
        return (_guideExposureUs.value / 1000f).coerceIn(500f, 2500f).toLong()
    }

    private fun processGuideCorrection(stars: List<GuideStar>, badQuality: Boolean = false) {
        synchronized(guideOperationLock) {
            if (!_guideRunning.value) return
            val calibration = _guideCalibration.value ?: return
            if (stars.isEmpty()) {
                guideStarLost = true
                _guideStatus.value = app.getString(if (badQuality) R.string.guide_bad_frame else R.string.guide_star_lost)
                guideTasks.cancel()
                resetGuideAlgorithmState()
                return
            }
            if (!mountModule.isConnected || guideTasks.busy || !guideMeasurementIsFresh()) return

            val now = android.os.SystemClock.elapsedRealtime()
            val minIntervalMs = (_guideExposureUs.value / 1000f).coerceIn(500f, 2500f).toLong()
            if (now - lastGuideCorrectionMs < minIntervalMs) return

            val error = measureGuideError(stars) ?: run {
                guideStarLost = true
                guideTasks.cancel()
                resetGuideAlgorithmState()
                _guideStatus.value = app.getString(R.string.guide_stars_lost)
                return
            }
            guideStarLost = false
            var dx = error.x
            var dy = error.y
            pendingGuideDither.getAndSet(null)?.let { (offsetX, offsetY) ->
                dx += offsetX
                dy += offsetY
            }
            val minMove = _guideMinMovePx.value
            val axisError = projectGuideError(calibration, dx, dy) ?: run {
                _guideStatus.value = app.getString(R.string.guide_calibration_invalid)
                return
            }
            val raErrorPx = axisError.first * hypot(calibration.eastXPerMs, calibration.eastYPerMs)
            val decErrorPx = axisError.second * hypot(calibration.northXPerMs, calibration.northYPerMs)
            appendGuideHistory(now, raErrorPx, decErrorPx)
            val filteredRaError = applyGuideAlgorithm(axisError.first, true)
            val filteredDecError = applyGuideAlgorithm(axisError.second, false)
            val raPulse = if (abs(raErrorPx) >= minMove) abs(filteredRaError).roundToInt().coerceIn(20, 2000) else 0
            val decPulse = if (abs(decErrorPx) >= minMove) abs(filteredDecError).roundToInt().coerceIn(20, 2000) else 0
            if (raPulse == 0 && decPulse == 0) {
                _guideCorrection.value = GuideCorrection(dx, dy, 0, 0, null, null)
                _guideStatus.value = app.getString(
                    R.string.guiding_status,
                    dx,
                    dy,
                    if (_guideMultiStarEnabled.value) _guideReferenceStars.value.size else 1
                )
                return
            }

            val raDirection = if (raPulse > 0) {
                if ((filteredRaError > 0f) xor _guideReverseRa.value) MountDirection.EAST else MountDirection.WEST
            } else {
                null
            }
            val decDirection = if (decPulse > 0) {
                if ((filteredDecError > 0f) xor _guideReverseDec.value) MountDirection.NORTH else MountDirection.SOUTH
            } else {
                null
            }
            _guideCorrection.value = GuideCorrection(dx, dy, raPulse, decPulse, raDirection, decDirection)
            lastGuideCorrectionMs = now
            guideTasks.launch {
                try {
                    if (!_guideRunning.value || !guideMeasurementIsFresh()) return@launch
                    if (raDirection != null) pulseMount(raDirection, raPulse)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_guideRunning.value && guideMeasurementIsFresh() && decDirection != null) pulseMount(decDirection, decPulse)
                    if (_guideRunning.value) _guideStatus.value = app.getString(R.string.guide_pulse_status, raPulse, decPulse)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (e: Exception) {
                    _guideRunning.value = false
                    guideStarLost = true
                    _guideStatus.value = app.getString(R.string.guide_pulse_error_detail, e.message.orEmpty())
                    mountModule.reportError(e.message ?: app.getString(R.string.guide_pulse_failed))
                }
            }
        }
    }

    private suspend fun pulseMount(direction: MountDirection, durationMs: Int) = guideTasks.pulse(direction, durationMs)

    private fun startPreview() {
        val cam = cameraManager.activeCamera ?: return
        val runEpoch = previewEpoch.nextRun()
        val selection = loadCameraCfa(cam)
        val colorIdentity = "${CameraReferenceIdentity.of(cam)}|$selection|${cameraCfaLayoutIdentity(cam)}"
        if (colorIdentity != requestedPreviewColorIdentity) { previewPipeline.stop(clearFrame = true); _previewBitmap.value = null }
        requestedPreviewColorIdentity = colorIdentity
        val runTraits = AtomicReference(cameraCfaTraits(cam, selection))
        activePreviewTraits = runTraits.get()
        val runRecycler: (ByteArray) -> Unit = { buf -> if (!softwareBinning.release(buf)) cam.recycleBuffer(buf) }
        scienceProvenance.clear()
        _scienceCaptureReady.value = false

        val previewRun = previewPipeline.start(
            paused = { _previewPaused.value },
            onProcessed = {
                if (!previewEpoch.isCurrent(runEpoch)) return@start
                _previewBitmap.value = previewPipeline.frame.value?.bitmap
                val detectedBits = frameProcessor.getDetectedEffectiveBits()
                if (detectedBits != _detectedBitDepth.value) {
                    _detectedBitDepth.value = detectedBits
                }
            },
            recycleBuffer = runRecycler,
            beforeRender = {
                // This runs after the old synchronous renderer has finished, on the same
                // serialized render worker. Old AWB cannot write its gains back after reset.
                previewColorReset.apply(colorIdentity) {
                    frameProcessor.resetWb()
                    frameProcessor.resetBitShiftDetection()
                    _awbMode.value = FrameProcessor.AwbMode.OFF
                }
            }
        )

        startExposureTimer()
        cam.startCapture(object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                val input = frame
                if (!previewEpoch.isCurrent(runEpoch) || cameraManager.activeCamera !== cam) { cam.recycleBuffer(input.data); return }
                val verifiedRun = cam.scienceCaptureGeneration
                if (verifiedRun != null && input.captureGeneration != null && input.captureGeneration != verifiedRun) {
                    // A completed old SDK callback may arrive after a fresh session starts.
                    // Drop its owned delivery, without invalidating the newer stack or recording it.
                    cam.recycleBuffer(input.data)
                    return
                }
                val actualSelection = if (input.pixelFormat.isRgb) SerCfaInterpretation.AUTO else selection
                val interpretedFrame = CameraCfaLayout.interpret(input, actualSelection)
                val traits = runTraits.get()
                onFrameArrived()
                updateFps()

                // Science receives private raw bytes before display/capture binning and before
                // PreviewPipeline takes ownership. Recording keeps its separate bounded copy.
                stackFrameGeometry = Triple(interpretedFrame.width, interpretedFrame.height, interpretedFrame.pixelFormat)
                if (_liveStackPixelFormat.value != interpretedFrame.pixelFormat) _liveStackPixelFormat.value = interpretedFrame.pixelFormat
                val scienceKey = currentStackKey(interpretedFrame)
                _scienceCaptureReady.value = scienceKey != null
                if (stacking.live.state.value.phase == LiveStackPhase.RUNNING) {
                    if (filterWheelMoving.value) stacking.live.invalidate("filter_moving")
                    else if (scienceKey == null) stacking.live.invalidate("settings_changed")
                    else stacking.acceptFrame(interpretedFrame, scienceKey)
                }

                // SER/PSER retain sensor samples and geometry. Copy before display binning
                // can combine CFA colors or recycle the SDK-owned input buffer.
                if (_isRecording.value && cam !is SimulatedCamera && rawRecordingLayout != null) {
                    if (scienceKey?.rawRecordingKey() != recordingCaptureKey || scienceKey == null) recordingCaptureChanged = true
                    val initialTemperature = recordingCaptureTemperature
                    val temperature = calibrationTemperature()
                    if (initialTemperature != null && (temperature == null || kotlin.math.abs(temperature - initialTemperature) > 2.0)) recordingCaptureChanged = true
                    enqueueRecordingFrame(interpretedFrame)
                }

                val processed = softwareBinning.apply(interpretedFrame, traits)
                if (processed.data !== interpretedFrame.data) cam.recycleBuffer(interpretedFrame.data)
                if (!previewEpoch.isCurrent(runEpoch)) {
                    runRecycler(processed.data)
                    return
                }

                fulfillFrameSnapshot(processed, traits)

                if (autoExpFrameSkip++ % 10 == 0) {
                    val aeMax = if (_longExposureEnabled.value) {
                        ExposureLimits.absoluteMaxUs(cam)
                    } else {
                        ExposureLimits.uiMaxUs(cam, false)
                    }
                    autoExposureController.processFrame(processed, cam, exposureMaxUs = aeMax)
                    if (autoExposureController.mode != AutoExposureMode.OFF) {
                        _exposureUs.value = cam.currentExposureUs
                        _gain.value = cam.currentGain
                    }
                }

                updateSoftwareStackingProgress(cam)

                val display = CameraCfaLayout.displayFrame(processed, traits)
                if (_isRecording.value && cam !is SimulatedCamera && rawRecordingLayout == null) enqueueRecordingFrame(display)

                // Ownership transfers to the preview pipeline after all synchronous readers finish.
                previewPipeline.submit(display, traits?.copy(bayerPatternAtOutput = true), previewRun, runRecycler)
            }
        })
        runTraits.set(cameraCfaTraits(cam, selection))
        activePreviewTraits = runTraits.get()
        val generation = cam.scienceCaptureGeneration
        val snapshot = captureSettingsSnapshot(cam)
        // A concurrent control write clears the generation before changing any settings.
        if (generation != null && snapshot != null && cameraManager.activeCamera === cam &&
            cam.scienceCaptureGeneration == generation) scienceProvenance.bind(generation, snapshot)
    }

    private fun currentStackKey(frame: FrameData? = null): StackSessionKey? {
        val cam = cameraManager.activeCamera ?: return null
        if (pendingCaptureChanges.get() != 0 || filterWheelMoving.value || autoExposureController.mode != AutoExposureMode.OFF) return null
        val key = if (frame == null) scienceProvenance.latest(cam.scienceCaptureGeneration)
            else scienceProvenance.frame(frame, cam.scienceCaptureGeneration)
        return key?.takeIf { it.filter == scienceFilter() }
            ?.copy(demosaicAlgorithm = stacking.demosaicAlgorithm.value, target = stacking.liveTarget.value)
    }

    private fun cameraCfaTraits(cam: Camera, selection: SerCfaInterpretation): PixelTraits? =
        CameraCfaLayout.effectiveTraits(cam.sciencePixelTraits?.copy(roiX = cam.currentRoi.x,
            roiY = cam.currentRoi.y, binning = (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1), selection)

    private fun cameraCfaLayoutIdentity(cam: Camera): String =
        "${cam.currentPixelFormat}|${cam.currentRoi}|${cam.cropInfo}|${(cam as? CameraBinningCapable)?.currentHardwareBin ?: 1}|${softwareBinning.factor}|${cam.currentReadoutMode}|${(cam as? CameraNativeReadoutModeCapable)?.currentNativeReadoutModeId}"

    private fun cameraCfaPrefKey(identity: String): String = "camera_cfa_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun saveCameraCfa(cam: Camera, selection: SerCfaInterpretation) {
        val identity = CameraReferenceIdentity.of(cam)
        val layout = cameraCfaLayoutIdentity(cam)
        cameraCfaChoices[identity] = selection to layout
        if (!cam.cameraInfo?.serialNumber.isNullOrBlank()) prefs.edit()
            .putString(cameraCfaPrefKey(identity), selection.name).putString("${cameraCfaPrefKey(identity)}_layout", layout).apply()
        _cameraCfa.value = selection
    }

    private fun loadCameraCfa(cam: Camera): SerCfaInterpretation {
        val identity = CameraReferenceIdentity.of(cam)
        val stored = cameraCfaChoices[identity]
        val key = cameraCfaPrefKey(identity)
        val saved = stored?.first ?: runCatching { SerCfaInterpretation.valueOf(prefs.getString(key, "AUTO") ?: "AUTO") }
            .getOrDefault(SerCfaInterpretation.AUTO)
        val layout = cameraCfaLayoutIdentity(cam)
        val selected = if (cam.currentPixelFormat.isRgb) SerCfaInterpretation.AUTO else
            CameraCfaLayout.boundSelection(saved, stored?.second ?: prefs.getString("${key}_layout", null), layout)
        if (selected != saved) {
            saveCameraCfa(cam, SerCfaInterpretation.AUTO)
            _statusMessage.value = app.getString(R.string.camera_cfa_layout_changed)
        } else _cameraCfa.value = selected
        return selected
    }

    fun showCameraCfaSettings() {
        val cam = cameraManager.activeCamera ?: return
        cameraCfaDialogIdentity = CameraReferenceIdentity.of(cam)
        cameraCfaDialogLayout = cameraCfaLayoutIdentity(cam)
        _showCameraCfaDialog.value = true
    }
    fun dismissCameraCfaSettings() { _showCameraCfaDialog.value = false; cameraCfaDialogIdentity = null; cameraCfaDialogLayout = null }

    private fun cameraCfaChangeIssue(selection: SerCfaInterpretation, resetConfirmed: Boolean, includeSettings: Boolean = true): String? {
        val cam = cameraManager.activeCamera
        return CameraCfaChangePolicy.issue(CameraCfaChangeState(connected = cam?.isOpen?.value == true,
            format = cam?.currentPixelFormat ?: PixelFormat.MONO8,
            recording = _isRecording.value || recordingWriteSession != null || currentRecordingFile != null,
            calibration = stacking.calibration.state.value.busy,
            snapshot = pointingCaptureOwned.get() || pendingFrameSnapshot.get() != null || frameSnapshotMutex.isLocked,
            settings = includeSettings && (_cameraCfaApplying.value || pendingCaptureChanges.get() != 0 || _gainWriteInProgress.value || binningSwitching),
            stackExists = stacking.live.state.value.phase !in setOf(LiveStackPhase.EMPTY, LiveStackPhase.CLOSED)), selection, resetConfirmed)
    }

    fun cameraCfaChangeMessage(selection: SerCfaInterpretation, resetConfirmed: Boolean = true): String? =
        cameraCfaChangeIssue(selection, resetConfirmed)?.let(::cameraCfaIssueMessage)

    private fun cameraCfaIssueMessage(issue: String): String = app.getString(when (issue) {
        "recording" -> R.string.camera_cfa_busy_recording
        "calibration" -> R.string.camera_cfa_busy_calibration
        "snapshot" -> R.string.camera_cfa_busy_snapshot
        "settings" -> R.string.camera_cfa_busy_settings
        "reset_stack" -> R.string.camera_cfa_reset_stack
        "rgb_not_raw" -> R.string.camera_cfa_rgb
        else -> R.string.camera_cfa_device_changed
    })

    /** Apply is explicit and serialized with all acquisition writes. It never calls setPixelFormat. */
    fun applyCameraCfa(selection: SerCfaInterpretation, resetLiveStack: Boolean = false): Boolean = synchronized(recordingLifecycleLock) {
        val cam = cameraManager.activeCamera ?: return@synchronized false
        if (cameraCfaDialogIdentity != null && cameraCfaDialogIdentity != CameraReferenceIdentity.of(cam)) {
            _statusMessage.value = app.getString(R.string.camera_cfa_device_changed); return@synchronized false
        }
        if (cameraCfaDialogLayout != null && cameraCfaDialogLayout != cameraCfaLayoutIdentity(cam)) {
            _statusMessage.value = app.getString(R.string.camera_cfa_layout_changed); dismissCameraCfaSettings(); return@synchronized false
        }
        if (selection == _cameraCfa.value) { dismissCameraCfaSettings(); return@synchronized true }
        cameraCfaChangeIssue(selection, resetLiveStack)?.let {
            _statusMessage.value = cameraCfaIssueMessage(it); return@synchronized false
        }
        _cameraCfaApplying.value = true
        changeCaptureSettings(cam, onFinished = { _cameraCfaApplying.value = false }) {
            cameraCfaChangeIssue(selection, resetConfirmed = true, includeSettings = false)?.let { error(cameraCfaIssueMessage(it)) }
            if (resetLiveStack) stacking.resetLive()
            previewPipeline.stop(clearFrame = true)
            _previewBitmap.value = null
            saveCameraCfa(cam, selection)
            dismissCameraCfaSettings()
        }
        true
    }

    private fun scienceFilter(): String =
        if (filterWheelConnected.value) "${filterWheelPosition.value}:${currentFilterName()}" else "none"

    private fun captureSettingsSnapshot(cam: Camera): StackSessionKey? {
        val traits = cameraCfaTraits(cam, _cameraCfa.value) ?: return null
        return StackSessionKey(
            camera = "${cam.cameraInfo?.name}/${cam.cameraInfo?.serialNumber}",
            width = cam.currentRoi.width, height = cam.currentRoi.height, format = CameraCfaLayout.effectiveFormat(cam.currentPixelFormat, _cameraCfa.value),
            roi = cam.currentRoi,
            hardwareBin = (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1,
            softwareBin = softwareBinning.factor,
            exposureUs = cam.currentExposureUs.toDouble(), gain = cam.currentGain,
            filter = scienceFilter(),
            readout = "${cam.currentReadoutMode}/${(cam as? CameraNativeReadoutModeCapable)?.currentNativeReadoutModeId}",
            offset = (cam as? CameraOffsetCapable)?.takeIf { it.offsetSupported }?.currentOffset,
            pixelTraits = traits,
            demosaicAlgorithm = stacking.demosaicAlgorithm.value
        )
    }

    private fun calibrationTemperature(): Double? {
        // Legacy adapters initialise temperature to zero without a validity flag. Treat this
        // ambiguous value as unknown, including an actual 0 °C reading, instead of guessing.
        return (cameraManager.activeCamera as? CoolingCapable)?.sensorTempTenths?.value
            ?.takeIf { it != 0 }?.div(10.0)
    }

    /** Same readiness checks power the inline guide and the actual start action. */
    fun liveStackStartIssue(): String? {
        if (pointingCaptureOwned.get()) return "settings_busy"
        val cam = cameraManager.activeCamera ?: return "connect_camera"
        if (!cam.recordsLiveViewAsScience) return "preview_only_camera"
        if (cam.requiresHighBitScienceCapture) return "high_bit_science_required"
        if (cam.sciencePixelTraits == null) return "unknown_science_layout"
        if (filterWheelMoving.value || _gainWriteInProgress.value || binningSwitching || pendingCaptureChanges.get() != 0) {
            return "settings_busy"
        }
        // Industrial overlays may synthesize long exposures. Until an adapter supplies explicit
        // completed-exposure provenance, never count partial averages as independent exposures.
        if (cam.supportsSoftwareStacking && cam.currentExposureUs > cam.hwExposureMaxUs) {
            return "software_exposure_unknown"
        }
        if (autoExposureController.mode != AutoExposureMode.OFF) {
            return "disable_auto_exposure"
        }
        if (_previewPaused.value || !cam.isCapturing.value) return "capture_paused"
        val key = currentStackKey() ?: return "wait_frame"
        return stacking.liveInputIssue(key)
    }

    /** Explicit recovery, using actual device bins. Never silently crop or discard calibration. */
    fun recommendedLiveStackBin(): Int? {
        val calibration = stacking.calibration.state.value
        if (_isRecording.value || recordingWriteSession != null || currentRecordingFile != null ||
            !calibration.initialized || calibration.busy || calibration.selectedIds.isNotEmpty() ||
            stacking.live.state.value.phase != LiveStackPhase.EMPTY ||
            liveStackStartIssue() !in setOf("processing_size", "memory_budget")) return null
        val cam = cameraManager.activeCamera as? CameraBinningCapable ?: return null
        val info = cameraManager.activeCamera?.cameraInfo ?: return null
        val key = currentStackKey() ?: return null
        return LiveStackAdmission.recommendHardwareBin(key, info.sensorWidth, info.sensorHeight,
            cam.supportedHardwareBins, stacking.memoryBudgetBytes)
    }

    fun useRecommendedLiveStackBin() { recommendedLiveStackBin()?.let(::setBinning) }

    fun stackRoiContext(): StackRoiContext? = buildStackRoiContext()

    private fun buildStackRoiContext(ignorePendingCaptureChanges: Boolean = false): StackRoiContext? {
        val cam = cameraManager.activeCamera ?: return null
        if (!cam.recordsLiveViewAsScience || !cam.supportsHostRoi) return null
        val key = captureSettingsSnapshot(cam)?.copy(target = stacking.liveTarget.value,
            demosaicAlgorithm = stacking.demosaicAlgorithm.value) ?: return null
        if (key.width < 1 || key.height < 1 || key.softwareBin < 1) return null
        val calibration = stacking.calibration.state.value
        val blocked = when {
            _isRecording.value || recordingWriteSession != null || currentRecordingFile != null -> "recording_active"
            pointingCaptureOwned.get() || pendingFrameSnapshot.get() != null || frameSnapshotMutex.isLocked -> "roi_capture_busy"
            (!ignorePendingCaptureChanges && pendingCaptureChanges.get() != 0) || binningSwitching ||
                _cameraCfaApplying.value || _gainWriteInProgress.value -> "settings_busy"
            stacking.live.state.value.phase != LiveStackPhase.EMPTY -> "reset_live_first"
            stacking.hasPlanetaryWork() -> "finish_offline_first"
            !calibration.initialized || calibration.busy -> "calibration_busy"
            calibration.selectedIds.isNotEmpty() -> "roi_calibration_selected"
            else -> null
        }
        return StackRoiContext(key, _imagingWidth.value, _imagingHeight.value, stacking.memoryBudgetBytes,
            stacking.calibration.memoryBytes, calibration.selectedIds.isNotEmpty(), cam.roiMinWidth, cam.roiMinHeight,
            cam.roiWidthStep, cam.roiHeightStep, blocked)
    }

    /** Returns whether the request was accepted. Final SDK/restart outcome is published to
     * stackRoiApplyState, shared by the capture and stacking planners. */
    fun applyStackRoi(expected: StackRoiContext, roi: Roi): Boolean = synchronized(recordingLifecycleLock) {
        if (_stackRoiApplyState.value.phase == StackRoiApplyPhase.APPLYING) return@synchronized false
        fun rejected(reason: String = "settings_changed"): Boolean {
            _stackRoiApplyState.value = StackRoiApplyState(StackRoiApplyPhase.FAILED, reason)
            return false
        }
        val camera = cameraManager.activeCamera ?: return@synchronized rejected("camera_disconnected")
        val current = stackRoiContext() ?: return@synchronized rejected()
        if (current.layoutIdentity != expected.layoutIdentity) return@synchronized rejected()
        current.blockedReason?.let { return@synchronized rejected(it) }
        if (roi.x < 0 || roi.y < 0 || roi.width < current.minWidth || roi.height < current.minHeight ||
            roi.x.toLong() + roi.width > current.sensorWidth || roi.y.toLong() + roi.height > current.sensorHeight ||
            roi.width % current.widthStep != 0 || roi.height % current.heightStep != 0 || current.issue(roi) != null) {
            return@synchronized rejected()
        }
        if (cameraManager.activeCamera !== camera) return@synchronized rejected("camera_disconnected")
        _stackRoiApplyState.value = StackRoiApplyState(StackRoiApplyPhase.APPLYING)
        invalidateStackSettings(layoutChanged = true)
        var applied = false
        var failureReason = "roi_apply_failed"
        changeCaptureSettings(camera, onFinished = {
            _stackRoiApplyState.value = if (applied && cameraManager.activeCamera === camera)
                StackRoiApplyState(StackRoiApplyPhase.APPLIED)
            else StackRoiApplyState(StackRoiApplyPhase.FAILED, failureReason)
            if (_stackRoiApplyState.value.phase == StackRoiApplyPhase.FAILED)
                _statusMessage.value = app.getString(if (failureReason == "settings_changed")
                    R.string.stack_roi_changed else R.string.stack_roi_failed)
        }, onFailure = { failure ->
            applied = false
            failureReason = when (failure.message) {
                "settings_changed", "recording_active", "calibration_busy", "roi_calibration_selected",
                "roi_capture_busy", "reset_live_first", "finish_offline_first" -> failure.message!!
                else -> "roi_apply_failed"
            }
        }) {
            check(!_isRecording.value && recordingWriteSession == null && currentRecordingFile == null) { "recording_active" }
            val latest = buildStackRoiContext(ignorePendingCaptureChanges = true) ?: error("settings_changed")
            latest.blockedReason?.let { error(it) }
            check(latest.layoutIdentity == expected.layoutIdentity && latest.issue(roi) == null) { "settings_changed" }
            camera.setRoi(roi)
            _roi.value = camera.currentRoi
            check(camera.currentRoi == roi) { "roi_apply_failed" }
            applied = true
        }
        true
    }

    fun setStackPlanningBin(bin: Int) {
        val context = stackRoiContext() ?: return
        if (context.blockedReason == null) setBinning(bin)
    }

    fun startLiveStack() {
        FileLogger.i(TAG, "Live stack requested camera=${cameraManager.activeCamera?.cameraInfo?.name} " +
            "input=$stackFrameGeometry appBin=${_binning.value} target=${stacking.liveTarget.value} " +
            "demosaic=${stacking.demosaicAlgorithm.value} budget=${stacking.memoryBudgetBytes}")
        liveStackStartIssue()?.let { stacking.report(it); return }
        val key = currentStackKey() ?: run { stacking.report("wait_frame"); return }
        if (stacking.live.state.value.phase == LiveStackPhase.PAUSED) stacking.live.resume(key)
        else stacking.startLive(key)
        if (stacking.live.state.value.phase == LiveStackPhase.RUNNING) {
            if (_focusAssistEnabled.value) toggleFocusAssist()
            if (!stacking.showLiveStack.value) stacking.toggleLiveDisplay()
        }
    }

    fun resumeCaptureForStacking() {
        val cam = cameraManager.activeCamera ?: return
        _previewPaused.value = false
        synchronizePreviewCapture(cam)
    }

    /** Pause/resume obey the same boundary as settings writes. UI only changes the desired
     * state immediately; a resume must never start a new SDK reader in the middle of a write. */
    private fun synchronizePreviewCapture(cam: Camera) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                captureSettingsMutex.withLock {
                    if (cameraManager.activeCamera !== cam) return@withLock
                    if (_previewPaused.value) {
                        previewEpoch.invalidate()
                        cam.stopCapture()
                        scienceProvenance.clear()
                        _scienceCaptureReady.value = false
                        stopExposureTimer()
                    } else if (!cam.isCapturing.value) {
                        stacking.live.onCaptureRestarted()
                        startPreview()
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                Log.e(TAG, "Preview capture transition failed", failure)
                _statusMessage.value = failure.message.orEmpty()
            }
        }
    }

    private fun invalidateStackSettings(layoutChanged: Boolean = false) {
        previewEpoch.invalidate()
        stacking.live.invalidate("settings_changed")
        scienceProvenance.clear()
        _scienceCaptureReady.value = false
        if (_isRecording.value && rawRecordingLayout != null) recordingCaptureChanged = true
        if (layoutChanged) {
            // Finish queued bytes using their original CFA/alignment before a camera mode or
            // ROI can change the interpretation of subsequently delivered samples.
            if (_isRecording.value || recordingWriteSession != null || currentRecordingFile != null) stopRecording()
            stackFrameGeometry = null
            _liveStackPixelFormat.value = null
        }
    }

    /** Serialize stop -> control writes -> fresh SDK acquisition; never relabel buffered frames. */
    private fun changeCaptureSettings(cam: Camera, onFinished: () -> Unit = {},
                                      onFailure: (Throwable) -> Unit = {}, action: suspend () -> Unit) {
        pendingCaptureChanges.incrementAndGet()
        invalidateStackSettings()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                applyCaptureSettingsAndRestart(cam, action)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                Log.e(TAG, "Camera settings/acquisition restart failed", failure)
                _statusMessage.value = failure.message.orEmpty()
                onFailure(failure)
            } finally { pendingCaptureChanges.decrementAndGet(); onFinished() }
        }
    }

    private suspend fun applyCaptureSettingsAndRestart(cam: Camera, action: suspend () -> Unit) {
        captureSettingsMutex.withLock {
            if (cameraManager.activeCamera !== cam) return@withLock
            // Layout changes request stop synchronously. Finish the old file with its frozen
            // layout before the SDK or per-frame FITS metadata can observe new settings.
            val finishing = synchronized(recordingLifecycleLock) {
                recordingWriteSession?.takeIf { !_isRecording.value }
            }
            finishing?.awaitCompletion()
            if (cameraManager.activeCamera !== cam) return@withLock
            val restart = cam.isCapturing.value
            previewEpoch.invalidate()
            if (restart) cam.stopCapture()
            try { action() }
            finally {
                if (restart && cameraManager.activeCamera === cam && !_previewPaused.value) startPreview()
            }
        }
    }

    private fun reportRecordingDrop() {
        val dropped = recordDroppedFrames.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            Log.w(TAG, "Recording queue full, dropped=$dropped")
        }
    }

    private fun enqueueRecordingFrame(frame: FrameData) = synchronized(recordingEnqueueLock) {
        if (!_isRecording.value) return
        if (recordWriteQueue.remainingCapacity() == 0) { reportRecordingDrop(); return }
        val size = frame.width.toLong() * frame.height * frame.pixelFormat.bytesPerPixel
        if (size > frame.data.size || size > Int.MAX_VALUE) { reportRecordingDrop(); return }
        val copy = recordBufferPool.acquire(size.toInt())
        System.arraycopy(frame.data, 0, copy, 0, size.toInt())
        if (!recordWriteQueue.offer(frame.copy(data = copy))) {
            recordBufferPool.release(copy)
            reportRecordingDrop()
        }
    }

    private fun canonicalRecordingFrame(frame: FrameData): FrameData {
        val layout = rawRecordingLayout ?: return frame
        check(frame.width == layout.width && frame.height == layout.height && frame.pixelFormat == layout.format) {
            "Recording pixel layout changed; start a new recording"
        }
        // enqueueRecordingFrame already copied camera bytes into the recording-only pool.
        return RawRecordingNormalizer.normalizeOwnedInPlace(frame, layout.traits)
    }

    private fun startRecordWriteThread() {
        lateinit var session: RecordingWriteSession<FrameData>
        var limitReached = false
        session = RecordingWriteSession(
            queue = recordWriteQueue,
            write = { frame ->
                val ser = serWriter
                val pser = pserWriter
                val mp4 = mp4Writer
                val fits = fitsSequenceWriter
                when {
                    ser != null && ser.isOpen -> {
                        val before = ser.currentFrameCount
                        ser.writeFrame(canonicalRecordingFrame(frame))
                        check(ser.currentFrameCount == before + 1) { "recording_frame_not_written" }
                        _recordingFrameCount.value = ser.currentFrameCount
                        _recordingBytes.value = ser.totalBytesWritten
                    }
                    pser != null && pser.isOpen -> {
                        val before = pser.currentFrameCount
                        pser.writeFrame(canonicalRecordingFrame(frame))
                        check(pser.currentFrameCount == before + 1) { "recording_frame_not_written" }
                        _recordingFrameCount.value = pser.currentFrameCount
                        _recordingBytes.value = pser.totalBytesWritten
                    }
                    mp4 != null && mp4.isOpen -> {
                        mp4.wbRedGain = frameProcessor.wbRedGain
                        mp4.wbGreenGain = frameProcessor.wbGreenGain
                        mp4.wbBlueGain = frameProcessor.wbBlueGain
                        mp4.writeFrame(frame)
                        _recordingFrameCount.value = mp4.currentFrameCount
                        _recordingBytes.value = mp4.totalBytesWritten
                    }
                    fits != null && fits.isOpen -> {
                        applyFitsRecordingMeta(fits)
                        val canonical = CameraCfaLayout.canonicalOwned(frame, recordingDisplayTraits).frame
                        fits.configuredFormat = canonical.pixelFormat
                        fits.writeFrame(canonical)
                        _recordingFrameCount.value = fits.currentFrameCount
                        _recordingBytes.value = fits.totalBytesWritten
                    }
                    else -> error("recording_writer_unavailable")
                }
                _recordingDurationMs.value = System.currentTimeMillis() - recordingStartTime
                val limit = _recordLimit.value
                val shouldStop = when (limit.type) {
                    RecordLimitType.FRAMES -> _recordingFrameCount.value >= limit.value
                    RecordLimitType.TIME -> _recordingDurationMs.value >= limit.value * 1000L
                    RecordLimitType.NONE -> false
                }
                if (shouldStop && !limitReached && !session.hasFailed) {
                    limitReached = true
                    synchronized(recordingEnqueueLock) { _isRecording.value = false }
                    recordingSaveToGallery = true
                    _recordingFinalizing.value = true
                    session.requestStop()
                    _statusMessage.value = app.getString(R.string.recording_limit_reached)
                }
            },
            release = { recordBufferPool.release(it.data) },
            closeWriter = ::closeRecorders,
            onFailure = { outcome ->
                synchronized(recordingEnqueueLock) { _isRecording.value = false }
                _recordingFinalizing.value = true
                FileLogger.e(TAG, outcome.failure.orEmpty(), outcome.cause)
                _statusMessage.value = app.getString(R.string.recording_error_detail,
                    outcome.cause?.message ?: outcome.failure.orEmpty())
            },
            onFinished = { outcome -> synchronized(recordingLifecycleLock) {
                if (recordingWriteSession === session) {
                    val stoppedByUser = recordingSaveToGallery && !limitReached
                    finishRecordingResources(outcome)
                    recordingWriteSession = null
                    _recordingFinalizing.value = false
                    if (outcome.succeeded && stoppedByUser) _statusMessage.value = app.getString(R.string.recording_stopped)
                }
            } }
        )
        recordingWriteSession = session
        try { session.start() }
        catch (error: Throwable) { recordingWriteSession = null; throw error }
    }

    /** Only the owning session calls this once it has finished every accepted write. */
    private fun closeRecorders() {
        var failure: Throwable? = null
        fun close(action: () -> Unit) {
            try { action() }
            catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        close { serWriter?.close() }; serWriter = null
        close { pserWriter?.close() }; pserWriter = null
        close { mp4Writer?.close() }; mp4Writer = null
        close { fitsSequenceWriter?.close() }; fitsSequenceWriter = null
        failure?.let { throw it }
    }

    fun setExposure(us: Float) {
        invalidateStackSettings()
        val cam = cameraManager.activeCamera ?: return
        val uiMax = ExposureLimits.uiMaxUs(cam, _longExposureEnabled.value)
        val uiMin = cam.exposureRange.min
        val clamped = us.coerceIn(uiMin, uiMax)
        if (clamped != us) {
            _statusMessage.value = app.getString(
                R.string.exposure_clamped,
                ImageUtils.formatExposure(clamped)
            )
        }
        if (pendingCaptureChanges.get() == 0 && clamped == cam.currentExposureUs) {
            _exposureUs.value = cam.currentExposureUs
            return
        }
        changeCaptureSettings(cam) {
            exposureWriteMutex.withLock {
                cam.setExposureTime(clamped)
                if (cameraManager.activeCamera === cam) {
                    _exposureUs.value = cam.currentExposureUs
                    updateSoftwareStackingProgress(cam)
                }
            }
        }
    }

    fun toggleLongExposure() {
        invalidateStackSettings()
        val cam = cameraManager.activeCamera ?: return
        val newState = !_longExposureEnabled.value
        _longExposureEnabled.value = newState
        cam.longExposureEnabled = newState
        refreshExposureUiLimits()
        changeCaptureSettings(cam) {
            exposureWriteMutex.withLock {
                if (!newState) {
                    val shortMax = ExposureLimits.uiMaxUs(cam, false)
                    if (cam.currentExposureUs > shortMax) cam.setExposureTime(shortMax)
                    _longExposureProgress.value = ""
                }
                if (cameraManager.activeCamera === cam) _exposureUs.value = cam.currentExposureUs
            }
        }
    }

    fun getExposureMax(): Float = _exposureUiMaxUs.value

    private fun refreshExposureUiLimits() {
        val cam = cameraManager.activeCamera
        if (cam == null) {
            _exposureUiMinUs.value = 1f
            _exposureUiMaxUs.value = ExposureLimits.SHORT_MAX_US
            return
        }
        val long = _longExposureEnabled.value
        _exposureUiMinUs.value = ExposureLimits.uiMinUs(cam, long)
        _exposureUiMaxUs.value = ExposureLimits.uiMaxUs(cam, long)
    }

    private fun updateSoftwareStackingProgress(cam: Camera) {
        if (!cam.supportsSoftwareStacking) {
            if (_longExposureProgress.value.isNotEmpty()) _longExposureProgress.value = ""
            return
        }
        val progress = cam.softwareStackingProgress
        _longExposureProgress.value = if (progress != null && progress.second > 1) {
            "${progress.first}/${progress.second}"
        } else {
            ""
        }
    }

    fun setGain(value: Float) {
        invalidateStackSettings()
        val cam = cameraManager.activeCamera ?: return
        changeCaptureSettings(cam) {
            gainWriteMutex.withLock {
                _gainWriteInProgress.value = true
                try {
                    cam.setGain(value)
                    if (cameraManager.activeCamera === cam) {
                        syncGainState(cam)
                    }
                } finally {
                    _gainWriteInProgress.value = false
                }
            }
        }
    }

    fun setOffset(value: Float) {
        val cam = cameraManager.activeCamera ?: return
        val camera = (cam as? CameraOffsetCapable)
            ?.takeIf { it.offsetSupported } ?: return
        changeCaptureSettings(cam) {
            camera.setOffset(value)
            syncOffsetCapability(cam)
        }
    }

    fun setUsbBandwidth(value: Int) {
        val camera = cameraManager.activeCamera ?: return
        val capable = camera as? CameraUsbBandwidthCapable ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (capable.setUsbBandwidth(value)) {
                syncUsbBandwidthCapability(camera)
                val info = camera.cameraInfo ?: return@launch
                val applied = capable.currentUsbBandwidth ?: return@launch
                deviceSettings.saveCameraUsbBandwidth(
                    cameraSettingsId(info),
                    camera.currentPixelFormat,
                    applied
                )
            }
        }
    }

    @Volatile private var pixelFormatSwitching = false

    /**
     * QHY changes bit depth by re-initialising (and sometimes re-enumerating)
     * the camera, which takes seconds — never run it on the UI thread, and
     * ignore taps while one switch is still in flight.
     */
    fun setPixelFormat(format: PixelFormat) {
        val cam = cameraManager.activeCamera ?: return
        if (pixelFormatSwitching) return
        invalidateStackSettings(layoutChanged = true)
        pixelFormatSwitching = true
        changeCaptureSettings(cam) {
            try {
                frameProcessor.resetBitShiftDetection(
                    forceDeclaredLayout = cam is PlayerOneCamera &&
                        cam.currentReadoutMode == ReadoutMode.HDR
                )
                cam.setPixelFormat(format)
                applySavedUsbBandwidth(cam)
                if (cam.currentPixelFormat != format) {
                    _statusMessage.value =
                        app.getString(R.string.pixel_format_switch_failed, format.displayName)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "setPixelFormat failed", e)
            } finally {
                _pixelFormat.value = cam.currentPixelFormat
                _supportedPixelFormats.value = cam.supportedPixelFormats
                syncGainState(cam)
                syncOffsetCapability(cam)
                syncUsbBandwidthCapability(cam)
                pixelFormatSwitching = false
            }
        }
    }

    fun setReadoutMode(mode: ReadoutMode) {
        invalidateStackSettings(layoutChanged = true)
        val cam = cameraManager.activeCamera ?: return
        changeCaptureSettings(cam) {
            cam.setReadoutMode(mode)
            _readoutMode.value = cam.currentReadoutMode
            syncGainState(cam)
            _pixelFormat.value = cam.currentPixelFormat
            _supportedPixelFormats.value = cam.supportedPixelFormats
            syncOffsetCapability(cam)
            frameProcessor.resetBitShiftDetection(
                forceDeclaredLayout = cam is PlayerOneCamera && cam.currentReadoutMode == ReadoutMode.HDR
            )
        }
    }

    fun setNativeReadoutMode(id: String) {
        val cam = cameraManager.activeCamera ?: return
        val camera = cam as? CameraNativeReadoutModeCapable ?: return
        invalidateStackSettings(layoutChanged = true)
        changeCaptureSettings(cam) {
            if (camera.setNativeReadoutMode(id)) {
                _nativeReadoutModes.value = camera.supportedNativeReadoutModes
                _nativeReadoutModeId.value = camera.currentNativeReadoutModeId
                _pixelFormat.value = cameraManager.activeCamera?.currentPixelFormat ?: _pixelFormat.value
                _supportedPixelFormats.value = cameraManager.activeCamera?.supportedPixelFormats ?: _supportedPixelFormats.value
                _roi.value = cameraManager.activeCamera?.currentRoi ?: _roi.value
                cameraManager.activeCamera?.let(::updateImagingSize)
                cameraManager.activeCamera?.let(::syncGainState)
                syncOffsetCapability(cameraManager.activeCamera)
            }
        }
    }

    fun cameraDefaults(): CameraDefaults? {
        val info = cameraManager.activeCamera?.cameraInfo ?: return null
        return deviceSettings.cameraDefaults(cameraSettingsId(info))
    }

    fun saveCameraDefaults(settings: CameraDefaults, onSaved: (CameraDefaults) -> Unit = {}) =
        saveCameraDefaults(settings, onSaved, onFailure = {})

    fun saveCameraDefaults(settings: CameraDefaults, onSaved: (CameraDefaults) -> Unit = {},
        onFailure: (Throwable) -> Unit) {
        if (pointingCaptureOwned.get()) { onFailure(IllegalStateException("Reference capture in progress")); return }
        val camera = cameraManager.activeCamera
        val info = camera?.cameraInfo
        if (camera == null || info == null) { onFailure(IllegalStateException("device_changed")); return }
        val normalized = settings.gain?.let { GainValueNormalizer.normalize(camera.gainCapability, it) }
        val normalizedSettings = settings.copy(gain = normalized)
        pendingCaptureChanges.incrementAndGet()
        val layoutChanged = settings.pixelFormat?.let { it != camera.currentPixelFormat } == true ||
            settings.readoutMode?.let { it != camera.currentReadoutMode } == true ||
            settings.nativeReadoutModeId?.let { it != (camera as? CameraNativeReadoutModeCapable)?.currentNativeReadoutModeId } == true
        invalidateStackSettings(layoutChanged = layoutChanged)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var applied = false
                applyCaptureSettingsAndRestart(camera) {
                    if (cameraManager.activeCamera !== camera) error("device_changed")
                    applyCameraDefaults(camera, normalizedSettings)
                    applied = true
                }
                if (!applied || cameraManager.activeCamera !== camera) error("device_changed")
                val persistedSettings = normalizedSettings.copy(gain = normalized?.let { camera.currentGain },
                    offset = normalizedSettings.offset?.let { (camera as? CameraOffsetCapable)?.currentOffset },
                    pixelFormat = normalizedSettings.pixelFormat?.let { camera.currentPixelFormat },
                    readoutMode = normalizedSettings.readoutMode?.let { camera.currentReadoutMode },
                    nativeReadoutModeId = normalizedSettings.nativeReadoutModeId?.let { (camera as? CameraNativeReadoutModeCapable)?.currentNativeReadoutModeId })
                deviceSettings.saveCameraDefaults(cameraSettingsId(info), persistedSettings)
                withContext(Dispatchers.Main.immediate) {
                    if (cameraManager.activeCamera !== camera) { onFailure(IllegalStateException("device_changed")); return@withContext }
                    _gain.value = camera.currentGain
                    _gainCapability.value = camera.gainCapability
                    _pixelFormat.value = camera.currentPixelFormat
                    _supportedPixelFormats.value = camera.supportedPixelFormats
                    _readoutMode.value = camera.currentReadoutMode
                    syncOffsetCapability(camera)
                    onSaved(persistedSettings)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                Log.e(TAG, "Saving camera defaults failed", failure)
                withContext(Dispatchers.Main.immediate) { onFailure(failure) }
            } finally { pendingCaptureChanges.decrementAndGet() }
        }
    }

    fun focuserDefaults(): FocuserDefaults {
        val info = eafInfo.value
        val deviceId = info?.name ?: "focuser"
        if (deviceSettings.hasFocuserDefaults(deviceId)) {
            return deviceSettings.focuserDefaults(deviceId)
        }
        return info?.let {
            FocuserDefaults(
                fineStep = it.fineStep,
                coarseStep = it.coarseStep,
                maxStep = it.maxStep,
                direction = it.direction,
                backlashSteps = it.backlashSteps,
                backlashDirection = it.backlashDirection
            )
        } ?: FocuserDefaults()
    }

    fun saveFocuserDefaults(settings: FocuserDefaults) = saveFocuserDefaults(settings, onSaved = {}, onFailure = {})

    fun saveFocuserDefaults(settings: FocuserDefaults, onSaved: () -> Unit = {}, onFailure: (Throwable) -> Unit) {
        val deviceId = eafInfo.value?.name
        if (!eafConnected.value || deviceId == null) { onFailure(IllegalStateException("device_changed")); return }
        try {
            applyFocuserDefaults(settings)
            deviceSettings.saveFocuserDefaults(deviceId, settings)
            onSaved()
        } catch (failure: Throwable) { onFailure(failure) }
    }

    fun coverDefaults(): CoverDefaults =
        deviceSettings.coverDefaults(coverDeviceInfo.value ?: "cover")

    fun saveCoverDefaults(settings: CoverDefaults) = saveCoverDefaults(settings, onSaved = {}, onFailure = {})

    fun saveCoverDefaults(settings: CoverDefaults, onSaved: () -> Unit = {}, onFailure: (Throwable) -> Unit) {
        val deviceId = coverDeviceInfo.value
        if (!coverConnected.value || deviceId == null) { onFailure(IllegalStateException("device_changed")); return }
        try {
            settings.brightness?.let(::setCalibratorBrightness)
            deviceSettings.saveCoverDefaults(deviceId, settings)
            onSaved()
        } catch (failure: Throwable) { onFailure(failure) }
    }

    private suspend fun applyCameraDefaults(camera: Camera, requestedSettings: CameraDefaults? = null) {
        cameraDefaultsMutex.withLock {
            val info = camera.cameraInfo ?: return@withLock
            val settings = requestedSettings ?: deviceSettings.cameraDefaults(cameraSettingsId(info))
            settings.readoutMode
                ?.takeIf { it in camera.supportedReadoutModes }
                ?.let(camera::setReadoutMode)
            settings.nativeReadoutModeId?.let { id ->
                (camera as? CameraNativeReadoutModeCapable)?.setNativeReadoutMode(id)
            }
            settings.pixelFormat
                ?.takeIf { it in camera.supportedPixelFormats }
                ?.let(camera::setPixelFormat)
            settings.gain?.let { gain ->
                camera.setGain(GainValueNormalizer.normalize(camera.gainCapability, gain))
            }
            settings.offset?.let { offset ->
                (camera as? CameraOffsetCapable)?.takeIf { it.offsetSupported }?.let { offsetCapable ->
                    offsetCapable.setOffset(offset.coerceIn(offsetCapable.offsetRange.min, offsetCapable.offsetRange.max))
                }
            }
        }
    }

    private fun syncGainState(camera: Camera?) {
        if (camera == null) {
            _gainCapability.value = null
            return
        }
        _gain.value = camera.currentGain
        _gainCapability.value = camera.gainCapability
    }

    private fun syncOffsetCapability(camera: Camera?) {
        val offsetCapable = (camera as? CameraOffsetCapable)?.takeIf { it.offsetSupported }
        _offset.value = offsetCapable?.currentOffset
        _offsetRange.value = offsetCapable?.offsetRange
        _offsetLabel.value = offsetCapable?.offsetLabel ?: "Offset"
        _offsetStep.value = offsetCapable?.offsetStep ?: 1f
    }

    private fun syncUsbBandwidthCapability(camera: Camera?) {
        val capable = camera as? CameraUsbBandwidthCapable
        _usbBandwidthRange.value = capable?.usbBandwidthRange
        _usbBandwidth.value = capable?.currentUsbBandwidth
    }

    private fun applySavedUsbBandwidth(camera: Camera) {
        val capable = camera as? CameraUsbBandwidthCapable ?: return
        val range = capable.usbBandwidthRange ?: return
        val info = camera.cameraInfo ?: return
        val saved = deviceSettings.cameraUsbBandwidth(
            cameraSettingsId(info),
            camera.currentPixelFormat
        ) ?: return
        capable.setUsbBandwidth(saved.coerceIn(range.first, range.last))
    }

    private fun applyFocuserDefaults(settings: FocuserDefaults) {
        eafSetFineStep(settings.fineStep)
        eafSetCoarseStep(settings.coarseStep)
        settings.maxStep?.let(::eafSetMaxStep)
        eafSetDirection(settings.direction)
        eafSetBacklash(settings.backlashSteps, settings.backlashDirection)
    }

    private fun cameraSettingsId(info: CameraInfo): String =
        listOf(info.name, info.serialNumber.ifBlank { "unknown" }).joinToString("_")

    fun setRoi(roi: Roi) {
        invalidateStackSettings(layoutChanged = true)
        val cam = cameraManager.activeCamera ?: return
        changeCaptureSettings(cam) { cam.setRoi(roi); _roi.value = cam.currentRoi }
    }

    fun resetRoi() {
        invalidateStackSettings(layoutChanged = true)
        val cam = cameraManager.activeCamera ?: return
        changeCaptureSettings(cam) { cam.resetRoi(); _roi.value = cam.currentRoi }
    }

    @Volatile private var binningSwitching = false

    fun setBinning(bin: Int) {
        val cam = cameraManager.activeCamera ?: return
        if (binningSwitching) return
        val requested = bin.coerceAtLeast(1)
        if (requested == _binning.value) return
        invalidateStackSettings(layoutChanged = true)
        binningSwitching = true
        changeCaptureSettings(cam) {
            try {
                applyBinning(cam, requested)
            } catch (e: Throwable) {
                Log.e(TAG, "setBinning($requested) failed", e)
            } finally {
                binningSwitching = false
            }
        }
    }

    private fun applyBinning(cam: Camera, requested: Int) {
        val capable = cam as? CameraBinningCapable
        val app = requested.coerceAtLeast(1)
        val candidates = AppBinning.hardwareCandidates(app, capable?.supportedHardwareBins.orEmpty())
        var appliedHw = 1
        if (capable != null) {
            for (hw in candidates) {
                if (capable.currentHardwareBin == hw || capable.setHardwareBin(hw)) {
                    appliedHw = hw
                    break
                }
            }
            if (capable.currentHardwareBin != appliedHw) {
                if (capable.currentHardwareBin != 1) capable.setHardwareBin(1)
                appliedHw = 1
            }
        }
        val plan = AppBinPlan(appBin = app, hardwareBin = appliedHw, softwareBin = (app / appliedHw).coerceAtLeast(1))
        publishBinPlan(cam, plan)
    }

    private fun syncBinningState(cam: Camera, appBin: Int) {
        val capable = cam as? CameraBinningCapable
        val hw = capable?.currentHardwareBin ?: 1
        val app = appBin.coerceAtLeast(1)
        val software = if (hw > 0 && app % hw == 0) app / hw else app
        publishBinPlan(cam, AppBinPlan(appBin = app, hardwareBin = hw, softwareBin = software.coerceAtLeast(1)))
    }

    private fun publishBinPlan(cam: Camera, plan: AppBinPlan) {
        FileLogger.i(TAG, "Binning applied app=${plan.appBin} hardware=${plan.hardwareBin} " +
            "software=${plan.softwareBin} output=${cam.currentRoi.width}x${cam.currentRoi.height}")
        softwareBinning.factor = plan.softwareBin
        _binning.value = plan.appBin
        _binningUsesHardware.value = plan.usesHardware
        _binningUsesSoftware.value = plan.usesSoftware
        _roi.value = cam.currentRoi
        updateImagingSize(cam)
    }

    private fun updateImagingSize(cam: Camera) {
        val info = cam.cameraInfo
        val hw = (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1
        val fullW = info?.sensorWidth ?: cam.currentRoi.width
        val fullH = info?.sensorHeight ?: cam.currentRoi.height
        _imagingWidth.value = (fullW / hw).coerceAtLeast(1)
        _imagingHeight.value = (fullH / hw).coerceAtLeast(1)
    }

    private fun resetBinningState() {
        softwareBinning.factor = 1
        _binning.value = 1
        _binningUsesHardware.value = false
        _binningUsesSoftware.value = false
        _imagingWidth.value = 1920
        _imagingHeight.value = 1200
    }

    fun setAutoStretch(enabled: Boolean) {
        frameProcessor.autoStretchEnabled = enabled
        _autoStretch.value = enabled
    }

    fun setLp750PreviewEnabled(enabled: Boolean) {
        frameProcessor.lp750Enabled = enabled
        _lp750PreviewEnabled.value = enabled
    }

    private val _awbMode = MutableStateFlow(FrameProcessor.AwbMode.OFF)
    val awbMode: StateFlow<FrameProcessor.AwbMode> = _awbMode.asStateFlow()

    fun setAwbMode(mode: FrameProcessor.AwbMode) {
        when (mode) {
            FrameProcessor.AwbMode.OFF -> {
                frameProcessor.resetWb()
                _awbMode.value = FrameProcessor.AwbMode.OFF
            }
            FrameProcessor.AwbMode.ONCE -> {
                frameProcessor.triggerAwbOnce()
                _awbMode.value = FrameProcessor.AwbMode.ONCE
            }
            FrameProcessor.AwbMode.CONTINUOUS -> {
                frameProcessor.setAwbContinuous(true)
                _awbMode.value = FrameProcessor.AwbMode.CONTINUOUS
            }
        }
    }

    fun togglePreviewPause() {
        val cam = cameraManager.activeCamera ?: return
        val newPaused = !_previewPaused.value
        _previewPaused.value = newPaused
        if (newPaused) {
            stacking.live.pause("capture_paused")
        }
        synchronizePreviewCapture(cam)
    }
    fun toggleFlipH() { _flipH.value = !_flipH.value }
    fun toggleFlipV() { _flipV.value = !_flipV.value }
    fun setRotation(deg: Int) { _rotation.value = deg % 360 }

    fun toggleFocusAssist() {
        val newState = !_focusAssistEnabled.value
        _focusAssistEnabled.value = newState
        frameProcessor.focusAssistEnabled = newState
        if (newState) {
            if (stacking.showLiveStack.value) stacking.toggleLiveDisplay()
            frameProcessor.resetFocusRange()
            _focusHistory.value = emptyList()
        }
    }

    fun setFocusZoomCenter(nx: Float, ny: Float) {
        _focusZoomCenter.value = Pair(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    fun setFocusZoomFactor(factor: Float) {
        _focusZoomFactor.value = factor.coerceIn(2f, 6f)
    }

    fun setFilterWheelPosition(pos: Int) {
        if (!filterWheelMoving.value && pos == filterWheelPosition.value) return
        invalidateStackSettings()
        fwController.setPosition(pos)
    }

    fun resetFilterWheel() {
        invalidateStackSettings()
        fwController.resetWheel()
    }

    fun setFilterWheelSlotName(index: Int, name: String) {
        fwController.setSlotName(index, name)
        // The filter label is part of the immutable capture context, even if the wheel
        // did not physically move. Refresh that context after an active-slot rename.
        if (index == filterWheelPosition.value) cameraManager.activeCamera?.let { changeCaptureSettings(it) {} }
    }

    fun setFilterWheelBidirectional(enabled: Boolean) {
        fwController.setBidirectional(enabled)
    }

    fun setFilterWheelSlotCount(count: Int) {
        fwController.setSlotCount(count)
    }

    fun eafMoveTo(position: Int) { eafCtrl.moveTo(position) }
    fun eafMoveRelative(steps: Int) { eafCtrl.moveRelative(steps) }
    fun eafHalt() { eafCtrl.halt() }
    fun eafSetZero() { eafCtrl.setZero() }
    fun eafSetDirection(dir: Int) { eafCtrl.setDirection(dir) }
    fun eafSetFineStep(step: Int) { eafCtrl.setFineStep(step) }
    fun eafSetCoarseStep(step: Int) { eafCtrl.setCoarseStep(step) }
    fun eafSetMaxStep(maxStep: Int) { eafCtrl.setMaxStep(maxStep) }
    fun eafSetBacklash(steps: Int, direction: Int) { eafCtrl.setBacklash(steps, direction) }

    fun scanAccessories() {
        accessoryManager.scan()
    }

    fun connectAccessory(device: AccessoryDeviceEntry) {
        accessoryManager.connect(device)
    }

    fun connectSerialAuto(device: AccessoryDeviceEntry) = accessoryManager.connectSerialAuto(device)
    fun connectEfucoser(device: AccessoryDeviceEntry) = accessoryManager.connectEfucoser(device)
    fun connectCover(device: AccessoryDeviceEntry) = accessoryManager.connectCover(device)
    fun connectRotator(device: AccessoryDeviceEntry) = accessoryManager.connectRotator(device)
    fun connectPowerBox(device: AccessoryDeviceEntry) = accessoryManager.connectPowerBox(device)

    fun disconnectFilterWheel() {
        accessoryManager.disconnectFilterWheel()
    }

    fun disconnectEaf() {
        accessoryManager.disconnectFocuser()
    }

    fun disconnectCover() = accessoryManager.disconnectCover()
    fun disconnectRotator() = accessoryManager.disconnectRotator()
    fun disconnectPowerBox() = accessoryManager.disconnectPowerBox()
    fun setPowerBoxDcOutput(index: Int, enabled: Boolean) =
        accessoryManager.powerBoxController.setDcOutput(index, enabled)
    fun setPowerBoxUsbOutput(index: Int, enabled: Boolean) =
        accessoryManager.powerBoxController.setUsbOutput(index, enabled)
    fun setPowerBoxUsbMaster(enabled: Boolean) =
        accessoryManager.powerBoxController.setUsbMasterEnabled(enabled)
    fun setPowerBoxDewEnabled(index: Int, enabled: Boolean) =
        accessoryManager.powerBoxController.setDewEnabled(index, enabled)
    fun setPowerBoxDewMode(index: Int, mode: DewHeaterMode) =
        accessoryManager.powerBoxController.setDewMode(index, mode)
    fun setPowerBoxDewPower(index: Int, value: Int) =
        accessoryManager.powerBoxController.setDewPower(index, value)
    fun setPowerBoxInterfaceNames(names: Map<String, String>) {
        val identity = powerBoxCapabilities.value?.identity ?: return
        val normalized = PowerInterfaceNames.normalize(names)
        _powerBoxInterfaceNames.value = normalized
        viewModelScope.launch(Dispatchers.IO) {
            deviceSettings.savePowerInterfaceNames(identity, normalized)
        }
    }
    fun openCover() = accessoryManager.coverController.openCover()
    fun closeCover() = accessoryManager.coverController.closeCover()
    fun haltCover() = accessoryManager.coverController.halt()
    fun setCalibratorBrightness(value: Int) =
        accessoryManager.coverController.setBrightness(value)
    fun calibratorOff() = accessoryManager.coverController.calibratorOff()
    fun moveRotatorTo(angle: Double) {
        if (!angle.isFinite()) return
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.moveTo(angle)
    }
    fun moveRotatorRelative(delta: Double) {
        if (!delta.isFinite() || delta == 0.0) return
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.moveRelative(delta)
    }
    fun haltRotator() = accessoryManager.rotatorController.halt()
    fun homeRotator() {
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.home()
    }
    fun zeroRotator() {
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.setZero()
    }
    fun setRotatorReversed(reversed: Boolean) {
        if (reversed == rotatorReversed.value) return
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.setReversed(reversed)
    }
    fun setRotatorHold(enabled: Boolean) = accessoryManager.rotatorController.setHold(enabled)
    fun setRotatorStepsPerDegree(value: Int) {
        if (value == rotatorStepsPerDegree.value) return
        rotatorPointingGuard.beforeCommand(rotatorConnected.value)
        accessoryManager.rotatorController.setStepsPerDegree(value)
    }

    private fun currentFilterName(): String? {
        if (!fwController.isConnected.value) return null
        val pos = fwController.currentPosition.value
        if (pos < 0) return null
        return fwController.slotNames.value.getOrNull(pos)
    }

    private fun bindCoolingFlows(cam: Camera) {
        unbindCoolingFlows()
        val cooler = cam as? CoolingCapable ?: run {
            _coolingInfo.value = null
            return
        }
        coolingJobs += viewModelScope.launch { cooler.coolingInfo.collect { _coolingInfo.value = it } }
        coolingJobs += viewModelScope.launch { cooler.coolerOn.collect { _coolerOn.value = it } }
        coolingJobs += viewModelScope.launch { cooler.targetTempTenths.collect { _targetTempTenths.value = it } }
        coolingJobs += viewModelScope.launch { cooler.sensorTempTenths.collect { _sensorTempTenths.value = it } }
        coolingJobs += viewModelScope.launch { cooler.tecVoltageTenths.collect { _tecVoltageTenths.value = it } }
        coolingJobs += viewModelScope.launch { cooler.coolingPowerPct.collect { _coolingPowerPct.value = it } }
        coolingJobs += viewModelScope.launch { cooler.tempHistory.collect { _tempHistory.value = it } }
        coolingJobs += viewModelScope.launch { cooler.rampStatus.collect { _rampStatus.value = it } }
    }

    private fun unbindCoolingFlows() {
        coolingJobs.forEach { it.cancel() }
        coolingJobs.clear()
        _coolingInfo.value = null
        _tempHistory.value = emptyList()
        _rampStatus.value = ""
    }

    private fun bindEnvironmentFlows(cam: Camera) {
        unbindEnvironmentFlows()
        val env = cam as? CameraEnvironmentControlCapable ?: return
        _heaterSupported.value = env.heaterSupported
        _heaterMaxLevel.value = env.heaterMaxLevel
        _fanSupported.value = env.fanSupported
        _fanMaxLevel.value = env.fanMaxLevel
        environmentJobs += viewModelScope.launch { env.heaterLevel.collect { _heaterLevel.value = it } }
        environmentJobs += viewModelScope.launch { env.fanLevel.collect { _fanLevel.value = it } }
    }

    private fun unbindEnvironmentFlows() {
        environmentJobs.forEach { it.cancel() }
        environmentJobs.clear()
        _heaterSupported.value = false
        _heaterMaxLevel.value = 0
        _heaterLevel.value = 0
        _fanSupported.value = false
        _fanMaxLevel.value = 0
        _fanLevel.value = 0
    }

    fun setHeaterLevel(level: Int) {
        (cameraManager.activeCamera as? CameraEnvironmentControlCapable)?.setHeaterLevel(level)
    }

    fun setFanLevel(level: Int) {
        (cameraManager.activeCamera as? CameraEnvironmentControlCapable)?.setFanLevel(level)
    }

    fun setCoolerOn(on: Boolean) {
        (cameraManager.activeCamera as? CoolingCapable)?.setCoolerOn(on)
    }

    fun setTargetTemperature(tenthsDegC: Int) {
        (cameraManager.activeCamera as? CoolingCapable)?.setTargetTemperature(tenthsDegC)
    }

    fun startCoolDown(targetTenths: Int, durationMinutes: Int) {
        (cameraManager.activeCamera as? CoolingCapable)?.startCoolDown(targetTenths, durationMinutes)
    }

    fun startWarmUp(durationMinutes: Int) {
        (cameraManager.activeCamera as? CoolingCapable)?.startWarmUp(durationMinutes)
    }

    fun stopRamp() {
        (cameraManager.activeCamera as? CoolingCapable)?.stopRamp()
    }

    fun setAutoExposureMode(mode: AutoExposureMode) {
        val previous = autoExposureController.mode
        if (previous != mode) invalidateStackSettings()
        autoExposureController.mode = mode
        if (mode != AutoExposureMode.OFF) autoExposureController.reset()
        _autoExposureMode.value = mode
        if (mode == AutoExposureMode.OFF && previous != mode) {
            cameraManager.activeCamera?.let { cam -> changeCaptureSettings(cam) {} }
        }
    }

    private fun winJuposTimestamp(): String {
        val now = LocalDateTime.now()
        val tenthMinute = now.second / 6
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + "_$tenthMinute"
    }

    private var simulatedRecordingJob: Job? = null
    private val simulatedRecordingEpoch = java.util.concurrent.atomic.AtomicLong()

    private fun startSimulatedRecording(camera: SimulatedCamera) {
        if (!camera.isCapturing.value) { _statusMessage.value = app.getString(R.string.sim_camera_resume); return }
        val run = simulatedRecordingEpoch.incrementAndGet()
        val started = android.os.SystemClock.elapsedRealtime()
        val first = camera.framesEmitted
        _recordingFrameCount.value = 0; _recordingDurationMs.value = 0; _recordingBytes.value = 0
        _isRecording.value = true
        _statusMessage.value = app.getString(R.string.sim_camera_recording)
        simulatedRecordingJob = viewModelScope.launch {
            try {
                while (isActive && _isRecording.value && cameraManager.activeCamera === camera && simulatedRecordingEpoch.get() == run) {
                    delay(100)
                    _recordingDurationMs.value = android.os.SystemClock.elapsedRealtime() - started
                    _recordingFrameCount.value = (camera.framesEmitted - first).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    val limit = _recordLimit.value
                    if (limit.type == RecordLimitType.TIME && _recordingDurationMs.value >= limit.value * 1000L ||
                        limit.type == RecordLimitType.FRAMES && _recordingFrameCount.value >= limit.value) break
                }
            } finally {
                if (simulatedRecordingEpoch.get() == run) {
                    _isRecording.value = false; simulatedRecordingJob = null
                    _statusMessage.value = app.getString(R.string.sim_camera_recording_done)
                }
            }
        }
    }

    fun startRecording(): Unit = synchronized(recordingLifecycleLock) {
        val source = cameraManager.activeCamera
        FileLogger.i(TAG, "Recording requested format=${_recordFormat.value} camera=${source?.cameraInfo?.name} " +
            "roi=${source?.currentRoi} pixels=${source?.currentPixelFormat} capturing=${source?.isCapturing?.value} " +
            "cfaBusy=${_cameraCfaApplying.value} referenceBusy=${pointingCaptureOwned.get()} " +
            "recording=${_isRecording.value} writerBusy=${recordingWriteSession != null} license=${License.canRecord}")
        if (_cameraCfaApplying.value || _gainWriteInProgress.value || binningSwitching || pendingCaptureChanges.get() != 0) {
            _statusMessage.value = app.getString(R.string.camera_cfa_busy_settings)
            return@synchronized
        }
        if (pointingCaptureOwned.get()) { _statusMessage.value = "Reference capture in progress"; return }
        // A timed-out old worker still owns its file and queue until final cleanup returns.
        if (_isRecording.value) return
        if (recordingWriteSession != null || currentRecordingFile != null || hasOpenRecorder()) {
            _statusMessage.value = app.getString(R.string.recording_finishing)
            return
        }
        (cameraManager.activeCamera as? SimulatedCamera)?.let { startSimulatedRecording(it); return }
        if (!License.canRecord) {
            _statusMessage.value = app.getString(R.string.trial_recording_disabled)
            return
        }
        try {
            val cam = cameraManager.activeCamera ?: return
            if (_recordFormat.value != RecordFormat.MP4 &&
                (!cam.recordsLiveViewAsScience || cam.currentPixelFormat == PixelFormat.RGB24)) {
                _statusMessage.value = app.getString(R.string.dslr_live_view_not_science)
                return
            }
            val roi = cam.currentRoi
            val ts = winJuposTimestamp()
            val target = CaptureTargetNames.canonical(_targetName.value)
            val info = cam.cameraInfo
            val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
            val filterName = currentFilterName()
            val filterPart = if (filterName != null) "-$filterName" else ""

            val fmt = _recordFormat.value
            val rawFormat = CameraCfaLayout.effectiveFormat(cam.currentPixelFormat, _cameraCfa.value)
            val rawRecording = fmt == RecordFormat.SER || fmt == RecordFormat.PSER
            val captureKey = if (rawRecording) currentStackKey()?.rawRecordingKey() else null
            val geometry = stackFrameGeometry
            val rawTraits = if (rawRecording) (cameraCfaTraits(cam, _cameraCfa.value)
                ?: error(app.getString(R.string.stack_reason_layout))).copy(
                    roiX = roi.x, roiY = roi.y,
                    binning = (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1) else null
            if (rawRecording) check(geometry != null && geometry.third == rawFormat) { app.getString(R.string.stack_reason_wait) }
            val displayTraits = activePreviewTraits?.copy(bayerPatternAtOutput = true)
            val displayFormat = CameraCfaLayout.displayFormat(rawFormat, activePreviewTraits)
            val pixFmt = if (rawTraits != null) RawRecordingNormalizer.canonicalFormat(rawFormat, rawTraits)
                else if (displayTraits != null && displayTraits.alignment != com.indigo.mobileobservatory.stacking.SampleAlignment.AUTO)
                    RawRecordingNormalizer.canonicalFormat(displayFormat, displayTraits) else displayFormat
            recordingDisplayTraits = displayTraits
            val rawWidth = geometry?.first ?: roi.width
            val rawHeight = geometry?.second ?: roi.height
            rawRecordingLayout = if (rawTraits != null) RawRecordingLayout(rawWidth, rawHeight, rawFormat, rawTraits) else null
            val useSERForPser8bit = fmt == RecordFormat.PSER && pixFmt.nativeBits <= 8
            val effectiveExt = when {
                useSERForPser8bit -> "ser"
                fmt == RecordFormat.MP4 -> "mp4"
                fmt == RecordFormat.PSER -> "pser"
                fmt == RecordFormat.FITS -> "fits"
                else -> "ser"
            }
            val dir = File(
                getApplication<Application>().getExternalFilesDir("recordings"),
                effectiveExt.uppercase()
            )
            dir.mkdirs()
            val sessionName = "$ts-$target$filterPart-$cameraShort"
            var file = if (fmt == RecordFormat.FITS) {
                File(dir, sessionName)
            } else {
                File(dir, "$sessionName.$effectiveExt")
            }
            // Short calibration recordings can finish within the same six-second name slot.
            // Never overwrite an earlier sequence or leave its provenance attached to new data.
            var suffix = 1
            while (file.exists()) {
                file = File(dir, if (fmt == RecordFormat.FITS) "$sessionName-${suffix++}" else "$sessionName-${suffix++}.$effectiveExt")
            }

            currentRecordingFile = file
            recordingCaptureKey = captureKey
            recordingCaptureChanged = rawRecording && (captureKey == null || autoExposureController.mode != AutoExposureMode.OFF)
            recordingCaptureTemperature = if (rawRecording) calibrationTemperature() else null
            recordingSaveToGallery = false
            // Persist invalid before opening: partial headers and startup failures must not
            // be mistaken for a legacy recording with merely unknown capture settings.
            if (rawRecording) CalibrationRecordingContext.invalidate(file, "recording_in_progress")
            when {
                fmt == RecordFormat.SER || useSERForPser8bit -> {
                    val ser = SERWriter(file)
                    serWriter = ser
                    ser.open(rawWidth, rawHeight, pixFmt, cam.cameraInfo?.name, filterName)
                }
                fmt == RecordFormat.PSER -> {
                    val pser = PSERWriter(file)
                    pserWriter = pser
                    pser.open(rawWidth, rawHeight, pixFmt, cam.cameraInfo?.name, filterName)
                }
                fmt == RecordFormat.MP4 -> {
                    val mp4 = Mp4Writer(file)
                    mp4.sampleTraits = displayTraits
                    mp4.wbRedGain = frameProcessor.wbRedGain
                    mp4.wbGreenGain = frameProcessor.wbGreenGain
                    mp4.wbBlueGain = frameProcessor.wbBlueGain
                    val recordFps = _fps.value.toInt().coerceIn(1, 60)
                    mp4Writer = mp4
                    mp4.open(rawWidth / softwareBinning.factor, rawHeight / softwareBinning.factor, recordFps)
                }
                fmt == RecordFormat.FITS -> {
                    val fits = FitsSequenceWriter(file)
                    applyFitsRecordingMeta(fits)
                    fits.configuredFormat = pixFmt
                    fitsSequenceWriter = fits
                    fits.open()
                }
            }

            recordingStartTime = System.currentTimeMillis()
            _isRecording.value = true
            _recordingFrameCount.value = 0
            _recordingBytes.value = 0
            _recordingDurationMs.value = 0
            recordDroppedFrames.set(0)
            val bitsInfo = if (fmt == RecordFormat.PSER && !useSERForPser8bit) " ${pixFmt.nativeBits}bit" else ""
            _statusMessage.value = if (useSERForPser8bit) {
                "REC [SER]: ${file.name} (8bit→SER)"
            } else {
                "REC [$effectiveExt$bitsInfo]: ${file.name}"
            }
            FileLogger.i(TAG, "Recording started ($effectiveExt ${pixFmt.name}): ${file.name}")
            startRecordWriteThread()
        } catch (e: Throwable) {
            FileLogger.e(TAG, "startRecording failed format=${_recordFormat.value}", e)
            recordingWriteSession?.fail("recording_start_failed", e)
            closeRecordingResources(saveToGallery = false)
            val detail = e.message.orEmpty()
            _statusMessage.value = if (_recordFormat.value == RecordFormat.MP4) {
                app.getString(R.string.record_mp4_encoder_failed, detail)
            } else {
                app.getString(R.string.record_error_detail, detail)
            }
        }
    }

    fun stopRecording() {
        val wasSimulated = simulatedRecordingJob != null
        closeRecordingResources(saveToGallery = true)
        if (wasSimulated) _statusMessage.value = app.getString(R.string.sim_camera_recording_done)
    }

    private fun closeRecordingResources(saveToGallery: Boolean): RecordingWriteOutcome? {
        simulatedRecordingEpoch.incrementAndGet()
        simulatedRecordingJob?.cancel(); simulatedRecordingJob = null
        val session = synchronized(recordingLifecycleLock) {
            synchronized(recordingEnqueueLock) { _isRecording.value = false }
            recordingSaveToGallery = recordingSaveToGallery || saveToGallery
            recordingWriteSession?.also {
                _recordingFinalizing.value = true
                it.requestStop()
            } ?: run {
                // Opening failed before a worker could own the file. This is never success.
                if (currentRecordingFile != null || hasOpenRecorder()) {
                    val error = runCatching { closeRecorders() }.exceptionOrNull()
                    finishRecordingResources(RecordingWriteOutcome("recording_start_failed", error))
                } else {
                    rawRecordingLayout = null
                }
                null
            }
        }
        // Completion owns cleanup/status. UI callers return while the writer drains; the
        // SDK-settings coroutine awaits awaitCompletion before applying a new pixel layout.
        return session?.outcome
    }

    private fun finishRecordingResources(outcome: RecordingWriteOutcome) {
        val recordedFile = currentRecordingFile
        val captureKey = recordingCaptureKey
        if (recordedFile != null && (captureKey != null || recordingCaptureChanged)) {
            runCatching {
                if (!outcome.succeeded)
                    CalibrationRecordingContext.invalidate(recordedFile, outcome.failure ?: "recording_failed")
                else if (recordingCaptureChanged || captureKey == null)
                    CalibrationRecordingContext.invalidate(recordedFile, "capture_settings_changed")
                else CalibrationRecordingContext.writeForRecording(recordedFile, captureKey, recordingCaptureTemperature)
            }.onFailure { error ->
                Log.w(TAG, "Recording capture context could not be finalized", error)
                runCatching { CalibrationRecordingContext.invalidate(recordedFile, "recording_context_failed") }
                    .onFailure { Log.w(TAG, "Recording context invalidation also failed", it) }
            }
        }
        // Release the library's busy gate only after the sidecar is final (or explicitly invalid).
        currentRecordingFile = null
        recordingCaptureKey = null
        recordingCaptureChanged = false
        recordingCaptureTemperature = null
        rawRecordingLayout = null
        recordingDisplayTraits = null
        while (true) recordBufferPool.release((recordWriteQueue.poll() ?: break).data)
        recordBufferPool.clear()
        if (outcome.succeeded && recordingSaveToGallery && recordedFile != null &&
            recordedFile.extension.equals("mp4", ignoreCase = true) && recordedFile.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                saveMp4ToGallery(recordedFile)
            }
        }
        recordingSaveToGallery = false
    }

    private fun hasOpenRecorder(): Boolean {
        return serWriter?.isOpen == true ||
            pserWriter?.isOpen == true ||
            mp4Writer?.isOpen == true ||
            fitsSequenceWriter?.isOpen == true
    }

    private fun applyFitsRecordingMeta(writer: FitsSequenceWriter) {
        val cam = cameraManager.activeCamera
        writer.exposureSeconds = (cam?.currentExposureUs ?: _exposureUs.value) / 1_000_000f
        writer.gain = cam?.currentGain ?: _gain.value
        val cap = cam?.gainCapability
        writer.gainKind = cap?.kind ?: GainControlKind.NATIVE_GAIN
        writer.gainLabel = cap?.label ?: "Gain"
        writer.gainUnit = cap?.unit
        writer.gainDbEquivalent = cam?.gainDbEquivalent(writer.gain)
        writer.cameraName = cam?.cameraInfo?.name ?: writer.cameraName
        writer.filterName = currentFilterName()
        writer.configuredFormat = cam?.let { CameraCfaLayout.effectiveFormat(it.currentPixelFormat, _cameraCfa.value) } ?: writer.configuredFormat
        writer.pixelSizeUm = cam?.cameraInfo?.pixelSizeUm
        writer.focalLengthMm = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }
        writer.binning = _binning.value
    }

    private fun saveMp4ToGallery(mp4File: File) {
        val app = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, mp4File.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MobileObservatory")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        mp4File.inputStream().use { it.copyTo(os) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    app.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "MP4 saved to gallery: $uri")
                }
            } else {
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "MobileObservatory"
                )
                moviesDir.mkdirs()
                val galleryFile = File(moviesDir, mp4File.name)
                mp4File.copyTo(galleryFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    app, arrayOf(galleryFile.absolutePath), arrayOf("video/mp4")
                ) { path, uri -> Log.i(TAG, "MP4 scanned: $path -> $uri") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save MP4 to gallery: ${e.message}")
        }
    }

    fun capture() {
        if (cameraManager.activeCamera is SimulatedCamera) {
            _statusMessage.value = app.getString(R.string.sim_camera_capture_done)
            return
        }
        when (_captureFormat.value) {
            CaptureFormat.FITS -> captureFits()
            CaptureFormat.JPG -> captureJpg()
        }
    }

    suspend fun captureTempFitsForPlateSolve(): File? = withContext(Dispatchers.IO) {
        val cam = cameraManager.activeCamera ?: return@withContext null
        if (!cam.recordsLiveViewAsScience || cam.currentPixelFormat == PixelFormat.RGB24) {
            _statusMessage.value = app.getString(R.string.dslr_live_view_not_science)
            return@withContext null
        }
        val frame = awaitFrameSnapshot() ?: return@withContext null
        val dir = File(getApplication<Application>().cacheDir, "polar_align").also { it.mkdirs() }
        val file = File(dir, "polar_${System.currentTimeMillis()}.fits")
        val info = cam.cameraInfo
        val focalLengthMm = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }
        fitsWriter.write(
            file = file,
            frame = frame,
            exposureSeconds = cam.currentExposureUs / 1_000_000f,
            gain = cam.currentGain,
            gainKind = cam.gainCapability.kind,
            gainLabel = cam.gainCapability.label,
            gainUnit = cam.gainCapability.unit,
            gainDbEquivalent = cam.gainDbEquivalent(cam.currentGain),
            cameraName = info?.name,
            filterName = currentFilterName(),
            configuredFormat = frame.pixelFormat,
            pixelSizeUm = info?.pixelSizeUm,
            focalLengthMm = focalLengthMm,
            binning = _binning.value
        )
        file
    }

    private data class AuxiliaryCaptureContext(val camera: Camera, val info: CameraInfo, val roi: Roi,
        val gain: Float, val binning: Int, val roiSensorScale: Int, val focalLengthMm: Float?, val generation: Long,
        val acquisitionIdentity: String, val opticalRevision: com.indigo.mobileobservatory.pointing.PointingRepository.CaptureRevision)

    fun auxiliaryCalibrationFailure(error: Throwable): String {
        FileLogger.e(TAG, "Auxiliary optical sampling failed", error)
        val message = error.message.orEmpty()
        val resource = when {
            error is kotlinx.coroutines.TimeoutCancellationException -> R.string.wfa_retry_stable
            message.contains("Choose another direction") -> R.string.wfa_move_further
            message.contains("Validation error") || message.contains("roll validation") -> R.string.wfa_validation_failed
            message.contains("Stop recording") || message.contains("Pause the current capture") -> R.string.wfa_pause_capture
            else -> null
        }
        return resource?.let(app::getString) ?: if (message.any { it in '\u4e00'..'\u9fff' }) message
            else app.getString(R.string.wfa_stability_failed)
    }

    private fun auxiliaryFocalLengthMm(): Float? {
        val reader = object : com.indigo.mobileobservatory.astro.OpticsPrefReader {
            override fun getString(key: String, default: String?) = prefs.getString(key, default)
            override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
            override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
            override fun contains(key: String) = prefs.contains(key)
        }
        val config = com.indigo.mobileobservatory.astro.PointingOpticsConfiguration.load(reader,
            secondaryGeometry = _guideCameraGeometry.value)
        val id = com.indigo.mobileobservatory.astro.OpticsTrainId.SECONDARY
        if (id in config.confirmedTrains) {
            val train = config.secondary
            return train.focalLengthMm(config.telescopes)?.takeIf { it.isFinite() && it > 0 }?.toFloat()
        }
        // The legacy explicit plate focal-length setting describes the guide optical path.
        // A planning overlay's built-in 500 mm default is never passed to astrometry.
        return prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it.isFinite() && it > 0 }
    }

    private suspend fun <T> withAuxiliaryCapture(block: suspend (AuxiliaryCaptureContext,
        com.indigo.mobileobservatory.pointing.AuxiliaryFrameCollector) -> T): T {
        synchronized(guideOperationLock) {
            check(!_guideRunning.value && !_guideCalibrating.value) { app.getString(R.string.wfa_stop_guiding) }
            check(pendingGuideSettings == 0) { app.getString(R.string.wfa_wait_settings) }
            check(auxiliaryCaptureOwned.compareAndSet(false, true)) { app.getString(R.string.wfa_capture_busy) }
            _auxiliaryCaptureBusy.value = true
        }
        var collector: com.indigo.mobileobservatory.pointing.AuxiliaryFrameCollector? = null
        try {
            return withContext(Dispatchers.IO) {
                auxiliaryCaptureJob = currentCoroutineContext()[Job]
                check(d50Manager.status().installed) { app.getString(R.string.precision_goto_need_d50) }
                val cam = guideCameraManager.activeCamera ?: error(app.getString(R.string.connect_guide_camera_first))
                pointingRepository.observeConnectedCamera("secondary", CameraReferenceIdentity.of(cam))
                val opticalRevision = pointingRepository.captureRevision("secondary")
                check(cam.recordsLiveViewAsScience && cam.sciencePixelTraits != null && !cam.currentPixelFormat.isRgb &&
                    !cam.requiresHighBitScienceCapture) { app.getString(R.string.wfa_choose_raw) }
                check(cam.currentExposureUs in 500_000f..5_000_000f) { app.getString(R.string.wfa_exposure_range) }
                check(cam.isCapturing.value) { app.getString(R.string.wfa_resume_auxiliary) }
                val rawBytes = cam.currentRoi.width.toLong() * cam.currentRoi.height * cam.currentPixelFormat.bytesPerPixel
                val main = cameraManager.activeCamera
                val mainBytes = main?.let { it.currentRoi.width.toLong() * it.currentRoi.height * it.currentPixelFormat.bytesPerPixel } ?: 0
                val runtime = Runtime.getRuntime()
                val freeHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                check(freeHeap >= com.indigo.mobileobservatory.pointing.AuxiliaryCaptureMemoryBudget.requiredHeapBytes(rawBytes, mainBytes))
                    { app.getString(R.string.wfa_memory_budget) }
                val mainUsb = cameraManager.claimedUsbDevice
                check(mainUsb == null || !guideCameraManager.holdsUsbDevice(mainUsb)) { app.getString(R.string.select_different_guide_camera) }
                val captured = guideGainWriteMutex.withLock {
                    check(guideCameraManager.activeCamera === cam)
                    cam.stopCapture()
                    check(!cam.isCapturing.value) { app.getString(R.string.wfa_resume_auxiliary) }
                    val startedUtc = System.currentTimeMillis()
                    val started = android.os.SystemClock.elapsedRealtime()
                    val stream = com.indigo.mobileobservatory.pointing.AuxiliaryFrameCollector(started, startedUtc,
                        (cam.currentExposureUs / 1000).toLong(), rawBytes)
                    collector = stream; auxiliaryCollector.set(stream)
                    startGuidePreview()
                    AuxiliaryCaptureContext(cam, requireNotNull(cam.cameraInfo), cam.currentRoi, cam.currentGain,
                        (cam as? CameraBinningCapable)?.currentHardwareBin ?: 1, cam.roiSensorScale,
                        auxiliaryFocalLengthMm(), requireNotNull(cam.scienceCaptureGeneration), CameraReferenceIdentity.of(cam), opticalRevision) to stream
                }
                block(captured.first, captured.second)
            }
        } finally {
            collector?.let { auxiliaryCollector.compareAndSet(it, null); it.close() }
            auxiliaryCaptureJob = null
            auxiliaryCaptureOwned.set(false); _auxiliaryCaptureBusy.value = false
        }
    }

    private fun checkAuxiliaryContext(context: AuxiliaryCaptureContext, raw: com.indigo.mobileobservatory.pointing.AuxiliaryRawSample) {
        check(guideCameraManager.activeCamera === context.camera && context.camera.scienceCaptureGeneration == context.generation &&
            pointingRepository.isCurrent(context.opticalRevision) && CameraReferenceIdentity.of(context.camera) == context.acquisitionIdentity &&
            raw.frame.captureGeneration == context.generation && context.camera.currentRoi == context.roi &&
            context.camera.currentGain == context.gain) { app.getString(R.string.wfa_camera_changed) }
    }

    /** A new verified auxiliary observation for push-to; this is never substituted for a main-camera plate. */
    suspend fun captureAuxiliaryReference(): com.indigo.mobileobservatory.pointing.OpticalObservation = withAuxiliaryCapture { context, stream ->
        val raw = withTimeout(30_000) { stream.receive() }
        checkAuxiliaryContext(context, raw)
        auxiliaryCollector.compareAndSet(stream, null); stream.close()
        val solved = solveReferenceFrame(raw.frame, raw.stamp, context.roi, context.info, context.gain,
            context.focalLengthMm, context.binning, "secondary", roiSensorScale = context.roiSensorScale,
            acquisitionIdentity = context.acquisitionIdentity, opticalRevision = context.opticalRevision)
        checkAuxiliaryContext(context, raw)
        solved
    }

    /** Fresh final plate, after two independent solves, with continuous raw motion coverage
     * throughout solver work. A slow final solve is still old; the caller keeps the 10 s limit. */
    suspend fun captureAuxiliaryStableReference(site: ObserverSite, trackingSky: Boolean = false):
        com.indigo.mobileobservatory.pointing.AuxiliaryStableReference = withAuxiliaryCapture { context, stream ->
        withTimeout(40_000) { coroutineScope {
            val evidence = ArrayList<com.indigo.mobileobservatory.pointing.OpticalFrameStability>()
            val latest = AtomicReference<com.indigo.mobileobservatory.pointing.AuxiliaryRawSample?>(null)
            suspend fun receive(): com.indigo.mobileobservatory.pointing.AuxiliaryRawSample {
                val raw = stream.receive(); checkAuxiliaryContext(context, raw)
                val psf = com.indigo.mobileobservatory.pointing.OpticalPsfSampler.sample(raw.frame, raw.traits)
                val frameEvidence = com.indigo.mobileobservatory.pointing.OpticalFrameStability(raw.frame.frameId,
                    raw.startElapsedMs, raw.endElapsedMs, raw.exposureDurationMs, context.generation,
                    raw.frame.width, raw.frame.height, psf, raw.earliestExposureElapsedMs)
                frameEvidence.validate()
                synchronized(evidence) {
                    check(evidence.size < 64) { app.getString(R.string.wfa_retry_stable) }
                    evidence += frameEvidence
                }
                latest.set(raw); return raw
            }
            // Keep the first frame: discarding it would leave the conservative exposure
            // interval without optical motion coverage. A delayed start is a retry.
            val initial = stream.receive()
            checkAuxiliaryContext(context, initial)
            val firstEvidence = com.indigo.mobileobservatory.pointing.OpticalFrameStability(initial.frame.frameId,
                initial.startElapsedMs, initial.endElapsedMs, initial.exposureDurationMs, context.generation,
                initial.frame.width, initial.frame.height, com.indigo.mobileobservatory.pointing.OpticalPsfSampler.sample(initial.frame, initial.traits), initial.earliestExposureElapsedMs)
            firstEvidence.validate(); evidence += firstEvidence; latest.set(initial)
            val producer = launch { while (true) receive() }
            val solved = ArrayList<com.indigo.mobileobservatory.pointing.OpticalStabilitySample>()
            var selected = initial
            repeat(3) { index ->
                if (index > 0) {
                    while (latest.get()?.frame?.frameId?.let { it > selected.frame.frameId } != true) delay(20)
                    selected = requireNotNull(latest.get())
                }
                val raw = selected
                val observation = solveReferenceFrame(raw.frame, raw.stamp, context.roi, context.info, context.gain,
                    context.focalLengthMm, context.binning, "secondary", roiSensorScale = context.roiSensorScale,
                    acquisitionIdentity = context.acquisitionIdentity, opticalRevision = context.opticalRevision)
                checkAuxiliaryContext(context, raw)
                val psf = synchronized(evidence) { evidence.first { it.frameId == raw.frame.frameId }.stars }
                solved += com.indigo.mobileobservatory.pointing.OpticalStabilitySample(observation, psf,
                    raw.startElapsedMs, raw.endElapsedMs, context.generation, raw.frame.frameId, raw.earliestExposureElapsedMs)
            }
            producer.cancel(); producer.join()
            auxiliaryCollector.compareAndSet(stream, null); stream.close()
            val frames = synchronized(evidence) { evidence.toList() }
            val observation = solved.last().observation
            val bound = com.indigo.mobileobservatory.pointing.OpticalPairStability.motionBound(frames,
                requireNotNull(observation.plateScaleDegPerPixel), solved.first().observation, site, trackingSky)
            val stability = com.indigo.mobileobservatory.pointing.OpticalPairStabilityEvidence(solved, frames, bound, site, trackingSky)
            stability.validate(observation, solved.first().observation)
            auxiliaryMotionMonitor.get()?.let {
                it.configure(solved.first(), certifiedQuiet = bound <= .02, observedElapsedMs = frames.last().endElapsedMs)
                _auxiliaryOpticalMotion.value = it.state
            }
            com.indigo.mobileobservatory.pointing.AuxiliaryStableReference(observation, stability)
        } }
    }

    /** Capture first, then solve. The callback marks actual main capture/eyepiece confirmation,
     * not the time a potentially lengthy ASTAP job finishes. All intermediate PSFs are kept. */
    suspend fun captureAuxiliaryCalibrationPair(site: ObserverSite, trackingSky: Boolean,
        captureReference: suspend ((com.indigo.mobileobservatory.pointing.ObservationStamp) -> Unit) ->
            com.indigo.mobileobservatory.pointing.OpticalObservation): com.indigo.mobileobservatory.pointing.CalibrationPair =
        withAuxiliaryCapture { context, stream -> coroutineScope {
            val frameEvidence = ArrayList<com.indigo.mobileobservatory.pointing.OpticalFrameStability>()
            suspend fun next(): com.indigo.mobileobservatory.pointing.AuxiliaryRawSample {
                val raw = stream.receive(); checkAuxiliaryContext(context, raw)
                val psf = com.indigo.mobileobservatory.pointing.OpticalPsfSampler.sample(raw.frame, raw.traits)
                val evidence = com.indigo.mobileobservatory.pointing.OpticalFrameStability(raw.frame.frameId,
                    raw.startElapsedMs, raw.endElapsedMs, raw.exposureDurationMs, context.generation,
                    raw.frame.width, raw.frame.height, psf, raw.earliestExposureElapsedMs)
                evidence.validate(); frameEvidence += evidence
                check(frameEvidence.size <= 64) { app.getString(R.string.wfa_retry_stable) }
                return raw
            }
            val referenceStamp = AtomicReference<com.indigo.mobileobservatory.pointing.ObservationStamp?>(null)
            // A delayed first delivery fails validation instead of hiding missing coverage.
            val first = withTimeout(30_000) {
                val raw = stream.receive()
                checkAuxiliaryContext(context, raw)
                val evidence = com.indigo.mobileobservatory.pointing.OpticalFrameStability(raw.frame.frameId,
                    raw.startElapsedMs, raw.endElapsedMs, raw.exposureDurationMs, context.generation,
                    raw.frame.width, raw.frame.height, com.indigo.mobileobservatory.pointing.OpticalPsfSampler.sample(raw.frame, raw.traits), raw.earliestExposureElapsedMs)
                evidence.validate(); frameEvidence += evidence; raw
            }
            val referenceTask = async { captureReference { stamp -> referenceStamp.set(stamp) } }
            lateinit var previous: com.indigo.mobileobservatory.pointing.AuxiliaryRawSample
            var latest = first
            withTimeout(30_000) {
                do {
                    previous = latest; latest = next()
                    // Fail promptly if the reference path failed before producing a time bracket.
                    if (referenceTask.isCompleted && referenceStamp.get() == null) referenceTask.await()
                } while (frameEvidence.size < 3 || referenceStamp.get()?.let {
                        latest.endElapsedMs >= it.elapsedMidpointMs + it.clockUncertaintyMs
                    } != true)
            }
            auxiliaryCollector.compareAndSet(stream, null); stream.close()
            val reference = referenceTask.await()
            check(reference.stamp == referenceStamp.get()) { app.getString(R.string.wfa_camera_changed) }
            val solved = listOf(first, previous, latest).map { raw ->
                currentCoroutineContext().ensureActive(); checkAuxiliaryContext(context, raw)
                val observation = solveReferenceFrame(raw.frame, raw.stamp, context.roi, context.info, context.gain,
                    context.focalLengthMm, context.binning, "secondary", roiSensorScale = context.roiSensorScale,
                    acquisitionIdentity = context.acquisitionIdentity, opticalRevision = context.opticalRevision)
                val psf = frameEvidence.first { it.frameId == raw.frame.frameId }.stars
                com.indigo.mobileobservatory.pointing.OpticalStabilitySample(observation, psf, raw.startElapsedMs,
                    raw.endElapsedMs, context.generation, raw.frame.frameId, raw.earliestExposureElapsedMs)
            }
            checkAuxiliaryContext(context, latest)
            val camera = solved[1].observation
            val bound = com.indigo.mobileobservatory.pointing.OpticalPairStability.motionBound(frameEvidence,
                requireNotNull(camera.plateScaleDegPerPixel), solved.first().observation, site, trackingSky)
            val stability = com.indigo.mobileobservatory.pointing.OpticalPairStabilityEvidence(solved, frameEvidence, bound, site, trackingSky)
            com.indigo.mobileobservatory.pointing.ReferencePairing.pair(camera, reference, site, trackingSky, bound, stability)
        } }

    private fun requirePointingReferenceReady(ownedByThisCapture: Boolean = false, expectedCamera: Camera? = null): Camera {
        val camera = cameraManager.activeCamera
        check(expectedCamera == null || camera === expectedCamera) { app.getString(R.string.wfp_configuration_changed) }
        val state = PointingReferenceState(camera?.let { PointingReferenceCameraState.of(it) }, d50Manager.status().installed,
            recordingBusy = _isRecording.value || recordingWriteSession != null || currentRecordingFile != null,
            liveStackBusy = stacking.live.state.value.phase == LiveStackPhase.RUNNING,
            calibrationBusy = stacking.calibration.state.value.busy,
            referenceBusy = pointingCaptureOwned.get() && !ownedByThisCapture,
            snapshotBusy = pendingFrameSnapshot.get() != null || frameSnapshotMutex.isLocked,
            settingsBusy = pendingCaptureChanges.get() != 0 || _gainWriteInProgress.value || binningSwitching,
            autoExposureOff = autoExposureController.mode == AutoExposureMode.OFF,
            previewPaused = _previewPaused.value, filterMoving = filterWheelMoving.value,
            rotatorMoving = rotatorConnected.value && rotatorMoving.value)
        val issue = PointingReferencePreflight.issue(state)
        check(issue == null) { app.getString(when (issue) {
            PointingReferenceIssue.CAMERA -> R.string.precision_goto_need_camera
            PointingReferenceIssue.DATABASE -> R.string.precision_goto_need_d50
            PointingReferenceIssue.BUSY -> R.string.wfp_reference_busy
            PointingReferenceIssue.SETTINGS -> R.string.wfp_reference_settings
            PointingReferenceIssue.SCIENCE_MODE -> R.string.wfp_reference_science
            PointingReferenceIssue.AUTO_EXPOSURE -> R.string.wfp_reference_ae
            PointingReferenceIssue.PREVIEW -> R.string.wfp_reference_preview
            PointingReferenceIssue.FILTER -> R.string.wfp_reference_filter
            PointingReferenceIssue.ROTATOR -> R.string.wfp_reference_rotator
            PointingReferenceIssue.OPTICAL_AXIS -> R.string.wfp_reference_axis
            PointingReferenceIssue.EXPOSURE -> R.string.wfp_reference_exposure
            null -> R.string.wfp_reference_busy
        }) }
        return requireNotNull(camera)
    }

    /** Explicit calibration capture: briefly owns an idle camera and brackets a fresh acquisition run.
     * A USB delivery timestamp is not called an exact exposure time; the whole bracket is retained.
     */
    suspend fun capturePointingReference(hintCoordinates: MountCoordinates? = null,
        onCaptured: (com.indigo.mobileobservatory.pointing.ObservationStamp) -> Unit = {}): com.indigo.mobileobservatory.pointing.OpticalObservation {
        val cam = synchronized(recordingLifecycleLock) {
            val ready = requirePointingReferenceReady()
            check(pointingCaptureOwned.compareAndSet(false, true))
            ready
        }
        try {
            return withContext(Dispatchers.IO) {
                synchronized(recordingLifecycleLock) { requirePointingReferenceReady(ownedByThisCapture = true, expectedCamera = cam) }
                val info = requireNotNull(cam.cameraInfo)
                val referenceGain = cam.currentGain
                val referenceBinning = _binning.value
                val referenceRoiScale = cam.roiSensorScale
                val referenceIdentity = com.indigo.mobileobservatory.camera.CameraReferenceIdentity.of(cam)
                pointingRepository.observeConnectedCamera("primary", referenceIdentity)
                val opticalRevision = pointingRepository.captureRevision("primary")
                val referenceFocalLength = pointingOpticsConfiguration().primaryReferenceFocalLengthMm()
                val capture = captureSettingsMutex.withLock {
                    synchronized(recordingLifecycleLock) { requirePointingReferenceReady(ownedByThisCapture = true, expectedCamera = cam) }
                    cam.stopCapture()
                    val startedUtc = System.currentTimeMillis()
                    val started = android.os.SystemClock.elapsedRealtime()
                    try { startPreview() } catch (t: Throwable) {
                        if (!cam.isCapturing.value && cameraManager.activeCamera === cam) runCatching { startPreview() }
                        throw t
                    }
                    val generation = requireNotNull(cam.scienceCaptureGeneration)
                    val frame = awaitFrameSnapshot(PointingReferencePreflight.FRAME_TIMEOUT_MS) ?: error(app.getString(R.string.precision_goto_no_frame))
                    val ended = android.os.SystemClock.elapsedRealtime()
                    check(frame.captureGeneration == generation && cam.scienceCaptureGeneration == generation && cameraManager.activeCamera === cam)
                        { "Camera settings changed during reference capture" }
                    check(kotlin.math.abs((System.currentTimeMillis() - startedUtc) - (ended - started)) <= 250)
                        { "Clock changed during reference capture" }
                    val midpoint = (started + ended) / 2
                    val stamp = com.indigo.mobileobservatory.pointing.ObservationStamp(startedUtc + midpoint - started,
                        midpoint, (cam.currentExposureUs / 1000).toLong(), (ended - started + 1) / 2)
                    Triple(frame, stamp, cam.currentRoi)
                }
                val (frame, stamp, roi) = capture
                onCaptured(stamp)
                solveReferenceFrame(frame, stamp, roi, info, referenceGain,
                    referenceFocalLength, referenceBinning, "primary", hintCoordinates, referenceRoiScale, referenceIdentity, opticalRevision)
            }
        } finally { pointingCaptureOwned.set(false) }
    }

    private suspend fun solveReferenceFrame(frame: FrameData,
        stamp: com.indigo.mobileobservatory.pointing.ObservationStamp, roi: Roi,
        info: com.indigo.mobileobservatory.camera.CameraInfo, referenceGain: Float,
        focalLengthMm: Float?, binning: Int, opticalPath: String,
        hintCoordinates: MountCoordinates? = null, roiSensorScale: Int = 1,
        acquisitionIdentity: String = info.serialNumber.ifBlank { info.name },
        opticalRevision: com.indigo.mobileobservatory.pointing.PointingRepository.CaptureRevision): com.indigo.mobileobservatory.pointing.OpticalObservation {
        check(pointingRepository.isCurrent(opticalRevision)) { app.getString(R.string.wfp_configuration_changed) }
        val (axisX, axisY) = com.indigo.mobileobservatory.pointing.ReferencePixelMapper.sensorCenter(
            info.sensorWidth, info.sensorHeight, roi, roiSensorScale, frame.width, frame.height)
        val directory = File(app.filesDir, "pointing/references").also { it.mkdirs() }
        val file = File(directory, "${opticalPath}_${stamp.utcMidpointMs}_${frame.frameId}.fits")
        fitsWriter.write(file = file, frame = frame, exposureSeconds = stamp.exposureDurationMs / 1000f, gain = referenceGain,
            cameraName = info.name, configuredFormat = frame.pixelFormat, pixelSizeUm = info.pixelSizeUm,
            focalLengthMm = focalLengthMm, binning = binning)
        val hint = FitsSolveHintReader.read(file)
        val solved = astapRunner.solve(file, hint.fovHeightDeg?.takeIf { it > 0 } ?: 0.0,
            mountCoordinates = hintCoordinates, searchRadiusDeg = if (hintCoordinates != null) 5.0 else null)
        check(solved.success) { solved.message }
        val header = com.indigo.mobileobservatory.astrometry.FitsHeaderReader.read(File(requireNotNull(solved.wcsHeaderPath)))
        val wcs = com.indigo.mobileobservatory.astrometry.OpticalWcs.parse(header)
        val pose = wcs.pose(axisX, axisY)
        val observation = com.indigo.mobileobservatory.pointing.OpticalObservation(file.nameWithoutExtension,
            if (opticalPath == "primary") com.indigo.mobileobservatory.pointing.OpticalIdentity("telescope", "axis", opticalPath)
            else com.indigo.mobileobservatory.pointing.OpticalIdentity(acquisitionIdentity, "sensor", opticalPath),
            stamp, pose.forward.direction(), pose,
            com.indigo.mobileobservatory.pointing.CameraGeometry(frame.width, frame.height, roi.x, roi.y, roi.width, roi.height,
                wcs.parity, axisX - 1, axisY - 1, "$acquisitionIdentity/sensor-center-frame-v1", roiSensorScale),
            // A conservative operational bound, not a measured plate-fit RMS. Independent
            // validation still measures the installation error before this can be used.
            maxOf(solved.rmsResidualDeg ?: .05, 2 * wcs.scaleDegPerPixel),
            if (opticalPath == "primary") com.indigo.mobileobservatory.pointing.OpticalObservationKind.MAIN_PLATE
            else com.indigo.mobileobservatory.pointing.OpticalObservationKind.AUXILIARY_PLATE,
            acquisitionDeviceId = acquisitionIdentity, plateScaleDegPerPixel = wcs.scaleDegPerPixel)
        File(directory, "${file.nameWithoutExtension}.json").writeText(org.json.JSONObject().apply {
            put("camera", observation.acquisitionDeviceId); put("utcMidpointMs", stamp.utcMidpointMs)
            put("elapsedMidpointMs", stamp.elapsedMidpointMs); put("timeUncertaintyMs", stamp.clockUncertaintyMs)
            put("axisFitsX", axisX); put("axisFitsY", axisY); put("raDeg", observation.direction.raDeg)
            put("roiSensorScale", roiSensorScale)
            put("decDeg", observation.direction.decDeg); put("uncertaintyDeg", observation.uncertaintyDeg)
            put("scaleDegPerPixel", observation.plateScaleDegPerPixel)
            put("uncertaintyBasis", if (solved.rmsResidualDeg == null) "conservative_operational_bound" else "solver_rms_with_pixel_floor")
        }.toString(2))
        currentCoroutineContext().ensureActive()
        val activeCamera = if (opticalPath == "primary") cameraManager.activeCamera else guideCameraManager.activeCamera
        check(activeCamera?.let(CameraReferenceIdentity::of) == acquisitionIdentity) { app.getString(R.string.wfp_configuration_changed) }
        pointingRepository.recordDirectObservation(observation, opticalRevision)
        return observation
    }

    suspend fun captureSequenceLight(
        exposureSeconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int = 1,
        imageType: String = "LIGHT"
    ): SessionFrame = withContext(Dispatchers.IO) {
        check(!pointingCaptureOwned.get()) { "Reference capture in progress" }
        if (!License.canRecord) throw DeviceUnavailable("trial")
        val cam = cameraManager.activeCamera ?: throw DeviceUnavailable("camera")
        if (!cam.recordsLiveViewAsScience || cam.currentPixelFormat == PixelFormat.RGB24) {
            throw DeviceUnavailable("camera")
        }
        pendingCaptureChanges.incrementAndGet()
        invalidateStackSettings(layoutChanged = binning != _binning.value)
        try { applyCaptureSettingsAndRestart(cam) { exposureWriteMutex.withLock {
            val uiMax = ExposureLimits.uiMaxUs(cam, true)
            val requestedBinning = binning.coerceAtLeast(1)
            if (requestedBinning != _binning.value) applyBinning(cam, requestedBinning)
            cam.longExposureEnabled = true
            _longExposureEnabled.value = true
            cam.setExposureTime((exposureSeconds * 1_000_000.0).toFloat().coerceIn(cam.exposureRange.min, uiMax))
            if (gain >= 0) cam.setGain(gain.toFloat())
            if (offset >= 0) (cam as? CameraOffsetCapable)?.setOffset(offset.toFloat())
            if (cameraManager.activeCamera === cam) {
                _exposureUs.value = cam.currentExposureUs
                _gain.value = cam.currentGain
                _offset.value = (cam as? CameraOffsetCapable)?.currentOffset
            }
        } } } finally { pendingCaptureChanges.decrementAndGet() }
        val timeoutMs = (exposureSeconds * 1_000.0 + 20_000.0).toLong().coerceAtLeast(45_000L)
        val frame = awaitFrameSnapshot(timeoutMs) ?: throw IllegalStateException("frame")
        destDir.mkdirs()
        val filterName = currentFilterName()
        val stamp = DateTimeFormatter.ofPattern("HHmmss").format(LocalDateTime.now())
        val filterPart = filterName?.let { "-$it" }.orEmpty()
        val normalizedImageType = imageType.ifBlank { "LIGHT" }.uppercase()
        val typePart = normalizedImageType.lowercase()
        val file = File(destDir, "$stamp-$typePart$filterPart-${exposureSeconds.toInt()}s.fits")
        val info = cam.cameraInfo
        val focalLengthMm = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }
        fitsWriter.write(
            file = file,
            frame = frame,
            exposureSeconds = cam.currentExposureUs / 1_000_000f,
            gain = cam.currentGain,
            gainKind = cam.gainCapability.kind,
            gainLabel = cam.gainCapability.label,
            gainUnit = cam.gainCapability.unit,
            gainDbEquivalent = cam.gainDbEquivalent(cam.currentGain),
            cameraName = info?.name,
            filterName = filterName,
            configuredFormat = frame.pixelFormat,
            pixelSizeUm = info?.pixelSizeUm,
            focalLengthMm = focalLengthMm,
            binning = _binning.value,
            imageType = normalizedImageType
        )
        val luma = monoLuma(frame.data, frame.width, frame.height, frame.pixelFormat.bytesPerPixel)
        SessionFrame(
            name = file.name,
            filter = filterName,
            exposureSeconds = exposureSeconds,
            hfr = luma?.let { image ->
                medianHalfLightRadius(image.pixels, image.width, image.height)?.times(image.scale)
            },
            starCount = luma?.let { countStars(it.pixels, it.width, it.height) }
        )
    }

    suspend fun moveFocuserAndWait(position: Int) {
        if (!eafConnected.value) throw DeviceUnavailable("focuser")
        eafMoveTo(position)
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            if (abs(eafPosition.value - position) <= 2) return
            delay(200)
        }
        throw IllegalStateException("focuser")
    }

    suspend fun runSequenceAutofocus(destDir: File): AutofocusRun {
        if (!eafConnected.value) throw DeviceUnavailable("focuser")
        val origin = eafPosition.value
        val samples = mutableListOf<Pair<Int, Double>>()
        var bestPosition = origin
        var bestHfr = Double.POSITIVE_INFINITY
        for (delta in listOf(-200, -100, 0, 100, 200)) {
            val position = (origin + delta).coerceAtLeast(0)
            moveFocuserAndWait(position)
            val frame = captureSequenceLight(1.0, _gain.value.toInt(), _offset.value?.toInt() ?: 0, destDir)
            val hfr = frame.hfr ?: continue
            samples += position to hfr
            if (hfr < bestHfr) {
                bestHfr = hfr
                bestPosition = position
            }
        }
        if (samples.isEmpty()) {
            throw IllegalStateException("autofocus")
        }

        moveFocuserAndWait(bestPosition)
        return AutofocusRun(
            atMillis = System.currentTimeMillis(),
            temperatureC = (cameraManager.activeCamera as? CoolingCapable)?.let { sensorTempTenths.value / 10.0 },
            filter = currentFilterName(),
            position = bestPosition,
            hfr = bestHfr,
            curve = samples
        )
    }

    private fun sequenceTargetAltitude(): Double? {
        val site = mountSite.value ?: return null
        val target = sequenceRuntime.observingTarget()
        return CoordinateTransform.j2000ToTopocentric(
            coordinates = EquatorialCoordinates(target.first * 15.0, target.second),
            instant = Instant.now(),
            site = ObserverSite(site.latitudeDeg, site.longitudeDeg),
            refraction = null
        ).altitudeDeg
    }

    private fun sequenceMinutesToMeridian(): Double? {
        val site = mountSite.value ?: return null
        val target = sequenceRuntime.observingTarget()
        val lst = SkyWatcherEquatorialMath.localSiderealHours(site.longitudeDeg, Instant.now())
        val hourAngle = SkyWatcherEquatorialMath.hourAngleHours(lst, target.first)
        return -hourAngle * 60.0
    }

    private fun captureFits() {
        if (!License.canRecord) {
            _statusMessage.value = app.getString(R.string.trial_capture_disabled)
            return
        }
        val cam = cameraManager.activeCamera ?: return
        if (!cam.recordsLiveViewAsScience || cam.currentPixelFormat == PixelFormat.RGB24) {
            _statusMessage.value = app.getString(R.string.dslr_live_view_not_science)
            return
        }
        val filterName = currentFilterName()
        val target = CaptureTargetNames.canonical(_targetName.value) // Snapshot the capture task before awaiting a frame.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val frame = awaitFrameSnapshot() ?: return@launch
                val dir = File(
                    getApplication<Application>().getExternalFilesDir("captures"),
                    "FITS"
                )
                dir.mkdirs()
                val ts = winJuposTimestamp()
                val info = cam.cameraInfo
                val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
                val filterPart = if (filterName != null) "-$filterName" else ""
                val file = File(dir, "$ts-$target$filterPart-$cameraShort.fits")
                val focalLengthMm = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }
                fitsWriter.write(
                    file = file,
                    frame = frame,
                    exposureSeconds = cam.currentExposureUs / 1_000_000f,
                    gain = cam.currentGain,
                    gainKind = cam.gainCapability.kind,
                    gainLabel = cam.gainCapability.label,
                    gainUnit = cam.gainCapability.unit,
                    gainDbEquivalent = cam.gainDbEquivalent(cam.currentGain),
                    cameraName = info?.name,
                    filterName = filterName,
                    configuredFormat = frame.pixelFormat,
                    pixelSizeUm = info?.pixelSizeUm,
                    focalLengthMm = focalLengthMm,
                    binning = _binning.value
                )
                withContext(Dispatchers.Main) {
                    _statusMessage.value = app.getString(R.string.saved_file, file.name)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "captureFits failed", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = app.getString(R.string.capture_error_detail, e.message.orEmpty())
                }
            }
        }
    }

    private fun captureJpg() {
        if (!License.canRecord) {
            _statusMessage.value = app.getString(R.string.trial_capture_disabled)
            return
        }
        val cam = cameraManager.activeCamera ?: return
        val filterName = currentFilterName()
        val target = CaptureTargetNames.canonical(_targetName.value) // Snapshot the capture task before awaiting a frame.
        val still = cam as? CameraStillCaptureCapable
        if (still != null && still.stillCaptureSupported) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val dir = File(
                        getApplication<Application>().getExternalFilesDir("captures"),
                        "JPG"
                    )
                    dir.mkdirs()
                    val ts = winJuposTimestamp()
                    val info = cam.cameraInfo
                    val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
                    val filterPart = if (filterName != null) "-$filterName" else ""
                    val fileName = "$ts-$target$filterPart-$cameraShort.jpg"
                    val dest = File(dir, fileName)
                    val result = still.captureStill(DslrStillFormat.JPEG, dir)

                    val jpeg = result.jpegFile ?: error("Camera did not return a JPEG")
                    if (jpeg.canonicalPath != dest.canonicalPath) {
                        jpeg.copyTo(dest, overwrite = true)
                        if (jpeg.exists() && jpeg.canonicalPath != dest.canonicalPath) {
                            jpeg.delete()
                        }
                    }
                    saveJpegFileToGallery(dest, fileName)
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = app.getString(R.string.saved_file, fileName)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "captureJpg still failed", e)
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = app.getString(R.string.capture_error_detail, e.message.orEmpty())
                    }
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = awaitFrameSnapshotWithTraits() ?: return@launch
                val frame = snapshot.frame
                val dir = File(
                    getApplication<Application>().getExternalFilesDir("captures"),
                    "JPG"
                )
                dir.mkdirs()
                val ts = winJuposTimestamp()
                val info = cam.cameraInfo
                val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
                val filterPart = if (filterName != null) "-$filterName" else ""
                val fileName = "$ts-$target$filterPart-$cameraShort.jpg"
                val file = File(dir, fileName)

                val bmp = captureFrameProcessor.frameToBitmap(frame, snapshot.traits)
                FileOutputStream(file).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }

                saveJpgToGallery(bmp, fileName)

                withContext(Dispatchers.Main) {
                    _statusMessage.value = app.getString(R.string.saved_file, fileName)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "captureJpg failed", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = app.getString(R.string.capture_error_detail, e.message.orEmpty())
                }
            }
        }
    }

    private fun saveJpegFileToGallery(jpegFile: File, fileName: String) {
        val app = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MobileObservatory")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        jpegFile.inputStream().use { it.copyTo(os) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    app.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "JPG saved to gallery: $uri")
                }
            } else {
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "MobileObservatory"
                )
                picturesDir.mkdirs()
                val galleryFile = File(picturesDir, fileName)
                jpegFile.copyTo(galleryFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    app, arrayOf(galleryFile.absolutePath), arrayOf("image/jpeg")
                ) { path, uri -> Log.i(TAG, "JPG scanned: $path -> $uri") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save JPG to gallery: ${e.message}")
        }
    }

    private fun saveJpgToGallery(bitmap: Bitmap, fileName: String) {
        val app = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MobileObservatory")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    app.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "JPG saved to gallery: $uri")
                }
            } else {
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "MobileObservatory"
                )
                picturesDir.mkdirs()
                val galleryFile = File(picturesDir, fileName)
                FileOutputStream(galleryFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
                MediaScannerConnection.scanFile(
                    app, arrayOf(galleryFile.absolutePath), arrayOf("image/jpeg")
                ) { path, uri -> Log.i(TAG, "JPG scanned: $path -> $uri") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save JPG to gallery: ${e.message}")
        }
    }

    private fun fulfillFrameSnapshot(frame: FrameData, traits: PixelTraits?) {
        val request = pendingFrameSnapshot.getAndSet(null) ?: return
        if (!request.isActive) return
        try {
            request.complete(CameraCfaLayout.ownedSnapshot(frame, traits))
        } catch (error: Throwable) {
            request.completeExceptionally(error)
        }
    }

    private suspend fun awaitFrameSnapshot(timeoutMs: Long = 45_000L): FrameData? =
        awaitFrameSnapshotWithTraits(timeoutMs)?.frame

    private suspend fun awaitFrameSnapshotWithTraits(timeoutMs: Long = 45_000L): CameraCfaLayout.Snapshot? =
        frameSnapshotMutex.withLock {
            check(!_cameraCfaApplying.value) { app.getString(R.string.camera_cfa_busy_settings) }
            val request = CompletableDeferred<CameraCfaLayout.Snapshot>()
            check(pendingFrameSnapshot.compareAndSet(null, request))
            try {
                withTimeoutOrNull(timeoutMs) { request.await() }
            } finally {
                pendingFrameSnapshot.compareAndSet(request, null)
            }
        }

    private fun updateFps() {
        frameCount++
        val now = System.nanoTime()
        val elapsed = (now - fpsTimestamp) / 1_000_000_000.0
        if (elapsed >= 1.0) {
            _fps.value = (frameCount / elapsed).toFloat()
            frameCount = 0
            fpsTimestamp = now
        }
    }

    private fun startExposureTimer() {
        exposureTimerJob?.cancel()
        lastFrameArrivalMs = System.currentTimeMillis()
        exposureTimerJob = viewModelScope.launch {
            try {
                while (true) {
                    val expUs = _exposureUs.value

                    val expMs = expUs / 1000f
                    if (expMs >= 1000f) {
                        val elapsed = System.currentTimeMillis() - lastFrameArrivalMs
                        val remaining = (expMs - elapsed).coerceAtLeast(0f)
                        val fraction = (elapsed / expMs).coerceIn(0f, 1f)
                        _exposureProgressFraction.value = fraction
                        _exposureCountdown.value = remaining / 1000f
                    } else {
                        _exposureProgressFraction.value = 0f
                        _exposureCountdown.value = 0f
                    }
                    kotlinx.coroutines.delay(100)
                }
            } catch (_: kotlinx.coroutines.CancellationException) { }
        }
    }

    private fun stopExposureTimer() {
        exposureTimerJob?.cancel()
        exposureTimerJob = null
        _exposureProgressFraction.value = 0f
        _exposureCountdown.value = 0f
    }

    internal fun onFrameArrived() {
        lastFrameArrivalMs = System.currentTimeMillis()
    }
}
