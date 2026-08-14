package com.indigo.mobileobservatory.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.accessories.AccessoryDeviceManager
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.camera.playerone.PlayerOneCamera
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
import com.indigo.mobileobservatory.camera.toupcam.FilterWheelInfo
import com.indigo.mobileobservatory.license.License
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.astrometry.FitsSolveHintReader
import com.indigo.mobileobservatory.mount.MountModule
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.MountProtocolType
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.mount.PrecisionGotoProgress
import com.indigo.mobileobservatory.ui.components.RecordLimit
import com.indigo.mobileobservatory.ui.components.RecordLimitType
import com.indigo.mobileobservatory.util.DeterministicResourceCleaner
import com.indigo.mobileobservatory.util.ImageUtils
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.recording.Mp4Writer
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERWriter
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CaptureFormat { FITS, JPG }
enum class RecordFormat { SER, PSER, MP4 }

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
        private const val ACTION_MOUNT_USB_PERMISSION = "com.indigo.mobileobservatory.MOUNT_USB_PERMISSION"
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
    private val guideModule = com.indigo.mobileobservatory.guide.GuideModule()
    private val previewPipeline = PreviewPipeline(viewModelScope, frameProcessor, targetFps = 30)
    private val guidePreviewPipeline = PreviewPipeline(viewModelScope, guideFrameProcessor, targetFps = 12)
    private val autoExposureController = AutoExposureController()
    private val fitsWriter = FITSWriter()
    private val mountModule = MountModule(application, viewModelScope)
    val mountMotionState: StateFlow<MountMotionState> = mountModule.motionState
    val precisionGotoProgress: StateFlow<PrecisionGotoProgress> = mountModule.precisionGotoProgress
    private val astapRunner = AstapRunner(application)
    private val d50Manager = D50Manager(application)

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()
    val previewPerformance: StateFlow<PreviewPerformanceStats> = previewPipeline.performance

    val histogram: StateFlow<HistogramData?> = frameProcessor.histogram
    val focusScore: StateFlow<Float?> = frameProcessor.focusScore

    private val _focusAssistEnabled = MutableStateFlow(false)
    val focusAssistEnabled: StateFlow<Boolean> = _focusAssistEnabled.asStateFlow()

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
    val activeFocuserDeviceId: StateFlow<Int?> = accessoryManager.activeFocuserDeviceId
    val activeCoverDeviceId: StateFlow<Int?> = accessoryManager.activeCoverDeviceId
    val activeRotatorDeviceId: StateFlow<Int?> = accessoryManager.activeRotatorDeviceId

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

    val connectionState: StateFlow<ConnectionState> = cameraManager.connectionState
    val devices: StateFlow<List<DeviceEntry>> = cameraManager.devices

    private val _exposureUs = MutableStateFlow(10_000f)
    val exposureUs: StateFlow<Float> = _exposureUs.asStateFlow()

    private val _gain = MutableStateFlow(0f)
    val gain: StateFlow<Float> = _gain.asStateFlow()
    private val _gainDbEquivalent = MutableStateFlow<Float?>(null)
    val gainDbEquivalent: StateFlow<Float?> = _gainDbEquivalent.asStateFlow()
    private val _gainCapability = MutableStateFlow<GainCapability?>(null)
    val gainCapability: StateFlow<GainCapability?> = _gainCapability.asStateFlow()

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

    private val _usbBandwidth = MutableStateFlow<Int?>(null)
    val usbBandwidth: StateFlow<Int?> = _usbBandwidth.asStateFlow()
    private val _usbBandwidthRange = MutableStateFlow<IntRange?>(null)
    val usbBandwidthRange: StateFlow<IntRange?> = _usbBandwidthRange.asStateFlow()

    private val _pixelFormat = MutableStateFlow(PixelFormat.MONO8)
    val pixelFormat: StateFlow<PixelFormat> = _pixelFormat.asStateFlow()

    private val _supportedPixelFormats = MutableStateFlow<List<PixelFormat>>(listOf(PixelFormat.MONO8))
    val supportedPixelFormats: StateFlow<List<PixelFormat>> = _supportedPixelFormats.asStateFlow()

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

    private val pendingFrameSnapshot = AtomicReference<CompletableDeferred<FrameData>?>(null)
    private val frameSnapshotMutex = Mutex()
    private val latestGuideStars = AtomicReference<List<GuideStar>>(emptyList())
    @Volatile private var guideFrameSequence = 0L
    @Volatile private var pendingGuideConnect = false
    @Volatile private var guidePulseInProgress = false
    private var lastGuideCorrectionMs = 0L
    private var lastRaGuideOutputPx = 0f
    private var lastDecGuideOutputPx = 0f
    private var lastRaGuideErrorPx = 0f
    private var lastDecGuideErrorPx = 0f
    @Volatile private var serWriter: SERWriter? = null
    @Volatile private var pserWriter: PSERWriter? = null
    @Volatile private var mp4Writer: Mp4Writer? = null
    @Volatile private var currentRecordingFile: File? = null
    private var recordingStartTime: Long = 0
    private val recordWriteQueue = LinkedBlockingQueue<FrameData>(RECORD_QUEUE_CAPACITY)
    private val recordBufferPool = ReusableByteArrayPool(RECORD_QUEUE_CAPACITY)
    private val recordDroppedFrames = AtomicLong(0)
    private var recordWriteThread: Thread? = null

    private val _captureFormat = MutableStateFlow(CaptureFormat.JPG)
    val captureFormat: StateFlow<CaptureFormat> = _captureFormat.asStateFlow()

    private val _recordFormat = MutableStateFlow(RecordFormat.MP4)
    val recordFormat: StateFlow<RecordFormat> = _recordFormat.asStateFlow()

    private val _recordLimit = MutableStateFlow(RecordLimit(RecordLimitType.NONE, 0))
    val recordLimit: StateFlow<RecordLimit> = _recordLimit.asStateFlow()

    private val _targetName = MutableStateFlow("Pla")
    val targetName: StateFlow<String> = _targetName.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    private val prefs = application.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val deviceSettings = DeviceSettingsRepository(application)

    val mountConnectionState = mountModule.mountConnectionState
    val mountHost = mountModule.mountHost
    val mountPort = mountModule.mountPort
    val synScanHost = mountModule.synScanHost
    val synScanPort = mountModule.synScanPort
    val mountTransport = mountModule.mountTransport
    val mountUsbDevices = mountModule.mountUsbDevices
    val mountUsbDeviceId = mountModule.mountUsbDeviceId
    val mountBaudRate = mountModule.mountBaudRate
    val mountBluetoothDevices = mountModule.mountBluetoothDevices
    val mountBluetoothAddress = mountModule.mountBluetoothAddress
    val mountProtocol = mountModule.mountProtocol
    val mountDetectedInfo = mountModule.mountDetectedInfo
    val mountCoordinates = mountModule.mountCoordinates
    val mountSite = mountModule.mountSite
    val mountBusy = mountModule.mountBusy
    val mountConnectionMessage = mountModule.mountConnectionMessage
    val mountMoveStatus = mountModule.mountMoveStatus
    val mountSlewRate = mountModule.mountSlewRate
    val mountTrackingEnabled = mountModule.mountTrackingEnabled
    private var frameCount = 0L
    private var fpsTimestamp = System.nanoTime()
    private var autoExpFrameSkip = 0
    @Volatile private var pendingConnect = false
    fun setTargetName(name: String) {
        _targetName.value = name
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
            else -> RecordFormat.SER
        }
    }

    fun toggleRecordFormat() {
        _recordFormat.value = when (_recordFormat.value) {
            RecordFormat.SER -> RecordFormat.PSER
            RecordFormat.PSER -> RecordFormat.MP4
            RecordFormat.MP4 -> RecordFormat.SER
        }
    }

    fun setRecordLimit(limit: RecordLimit) {
        _recordLimit.value = limit
    }

    private val _showPlayer = MutableStateFlow(false)
    val showPlayer: StateFlow<Boolean> = _showPlayer.asStateFlow()

    fun openPlayer() {
        _previewPaused.value = true
        _showPlayer.value = true
    }

    fun closePlayer() {
        _showPlayer.value = false
        _previewPaused.value = false
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

    init {
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
            cameraManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val cam = cameraManager.activeCamera ?: return@collect
                        _exposureUs.value = cam.currentExposureUs
                        _gainCapability.value = cam.gainCapability
                        _gain.value = cam.currentGain
                        _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
                        syncOffsetCapability(cam)
                        _pixelFormat.value = cam.currentPixelFormat
                        _supportedPixelFormats.value = cam.supportedPixelFormats
                        _readoutMode.value = cam.currentReadoutMode
                        _supportedReadoutModes.value = cam.supportedReadoutModes
                        (cam as? CameraNativeReadoutModeCapable)?.let { readoutCapable ->
                            _nativeReadoutModes.value = readoutCapable.supportedNativeReadoutModes
                            _nativeReadoutModeId.value = readoutCapable.currentNativeReadoutModeId
                        } ?: run {
                            _nativeReadoutModes.value = emptyList()
                            _nativeReadoutModeId.value = null
                        }
                        applyCameraDefaults(cam)
                        applySavedUsbBandwidth(cam)
                        _gain.value = cam.currentGain
                        _gainCapability.value = cam.gainCapability
                        _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
                        _pixelFormat.value = cam.currentPixelFormat
                        _readoutMode.value = cam.currentReadoutMode
                        (cam as? CameraNativeReadoutModeCapable)?.let { readoutCapable ->
                            _nativeReadoutModes.value = readoutCapable.supportedNativeReadoutModes
                            _nativeReadoutModeId.value = readoutCapable.currentNativeReadoutModeId
                        }
                        syncOffsetCapability(cam)
                        syncUsbBandwidthCapability(cam)
                        _roi.value = cam.currentRoi
                        _longExposureEnabled.value = cam.longExposureEnabled
                        refreshExposureUiLimits()
                        _statusMessage.value = app.getString(R.string.camera_connected_status, cam.cameraInfo?.name.orEmpty(), cam.cameraInfo?.serialNumber.orEmpty())
                        bindCoolingFlows(cam)
                        startPreview()
                    }
                    is ConnectionState.Error -> {
                        _statusMessage.value = state.message
                    }
                    is ConnectionState.Disconnected -> {
                        previewPipeline.stop()
                        _previewBitmap.value = null
                        unbindCoolingFlows()
                        syncOffsetCapability(null)
                        syncUsbBandwidthCapability(null)
                        _nativeReadoutModes.value = emptyList()
                        _nativeReadoutModeId.value = null
                        _gainCapability.value = null
                        _gainDbEquivalent.value = null
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
                        _guideStatus.value = app.getString(R.string.guide_camera_connected_status, cam.cameraInfo?.name.orEmpty())
                        startGuidePreview()
                    }
                    is ConnectionState.Error -> {
                        _guideStatus.value = state.message
                        _guideRunning.value = false
                    }
                    is ConnectionState.Disconnected -> {
                        guidePreviewPipeline.stop()
                        _guidePreviewBitmap.value = null
                        _guideStar.value = null
                        _guideStars.value = emptyList()
                        _guideReferenceStar.value = null
                        _guideReferenceStars.value = emptyList()
                        _guideGainCapability.value = null
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
            cameraManager.devices.collect { devs ->
                if (pendingConnect && devs.isNotEmpty()) {
                    pendingConnect = false
                    kotlinx.coroutines.delay(500)
                    requestConnect()
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
        viewModelScope.coroutineContext.cancelChildren()
        previewPipeline.close()
        guidePreviewPipeline.close()
        stopExposureTimer()
        mountModule.cancelJobs()
        coolingJobs.forEach { it.cancel() }
        coolingJobs.clear()
        _guideRunning.value = false
        _guideCalibrating.value = false
        pendingConnect = false
        pendingGuideConnect = false
    }

    private fun unregisterOwnedReceiver() {
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
    fun setMountPort(value: String) = mountModule.setMountPort(value)
    fun setSynScanHost(value: String) = mountModule.setSynScanHost(value)
    fun setSynScanPort(value: String) = mountModule.setSynScanPort(value)
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
    fun gotoMountTarget(name: String, raHours: Double, decDeg: Double) =
        mountModule.gotoMountTarget(name, raHours, decDeg)
    fun syncMountToTarget(name: String, raHours: Double, decDeg: Double) =
        mountModule.syncMountToTarget(name, raHours, decDeg)

    fun startPrecisionGoto(name: String, raHours: Double, decDeg: Double): Boolean {
        if (connectionState.value !is ConnectionState.Connected) {
            _statusMessage.value = app.getString(R.string.precision_goto_need_camera)
            return false
        }
        if (!d50Manager.status().installed) {
            _statusMessage.value = app.getString(R.string.precision_goto_need_d50)
            return false
        }
        return mountModule.startPrecisionGoto(
            name = name,
            raHours = raHours,
            decDeg = decDeg,
            captureAndSolve = { hint -> captureAndSolveForPrecisionGoto(hint) }
        )
    }

    private suspend fun captureAndSolveForPrecisionGoto(hint: MountCoordinates?): MountCoordinates {
        val file = captureTempFitsForPlateSolve()
            ?: error(app.getString(R.string.precision_goto_no_frame))
        val hints = withContext(Dispatchers.IO) { FitsSolveHintReader.read(file) }
        val fovDeg = hints.fovHeightDeg?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val result = astapRunner.solve(
            inputFile = file,
            fovDeg = fovDeg,
            mountCoordinates = hint,
            searchRadiusDeg = 5.0
        )
        runCatching { file.delete() }
        if (!result.success) {
            error(result.message.ifBlank { app.getString(R.string.plate_solve_failed) })
        }
        val raDeg = result.raDeg ?: error(app.getString(R.string.plate_solve_failed))
        val decDeg = result.decDeg ?: error(app.getString(R.string.plate_solve_failed))
        return MountCoordinates(raHours = raDeg / 15.0, decDeg = decDeg)
    }
    fun moveMountRaBy(distanceDeg: Double, east: Boolean, rateDegPerSec: Double) =
        mountModule.moveMountRaBy(distanceDeg, east, rateDegPerSec)
    fun stopMountRaMove() = mountModule.stopMountRaMove()
    fun stopMountMotion() = mountModule.stopMountMotion()
    fun startMountManualMove(direction: MountDirection) = mountModule.startMountManualMove(direction)
    fun stopMountManualMove(direction: MountDirection? = null) = mountModule.stopMountManualMove(direction)
    fun setMountSlewRate(rate: MountSlewRate) = mountModule.setMountSlewRate(rate)
    fun setMountTracking(enabled: Boolean) = mountModule.setMountTracking(enabled)
    fun goMountHome() = mountModule.goMountHome()
    fun setMountHomeHere() = mountModule.setMountHomeHere()
    fun readMountCoordinates() = mountModule.readMountCoordinates()
    fun connectCamera(index: Int = 0) {
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _statusMessage.value = app.getString(R.string.connecting_serial, sn)
                cameraManager.openCameraBySn(sn)
            } catch (e: Throwable) {
                Log.e(TAG, "connectCameraBySn failed", e)
                _statusMessage.value = app.getString(R.string.generic_error_detail, e.message.orEmpty())
            }
        }
    }

    fun requestConnect() {
        val devs = cameraManager.devices.value
        when {
            devs.isEmpty() -> {
                pendingConnect = true
                cameraManager.enumerateDevices()
            }
            devs.size == 1 -> {
                connectCameraBySn(devs[0].serialNumber)
            }
            else -> {
                _showDevicePicker.value = true
            }
        }
    }

    fun disconnectCamera() {
        stopRecording()
        stopExposureTimer()
        previewPipeline.stop()
        cameraManager.closeCamera()
        _previewBitmap.value = null
        _statusMessage.value = app.getString(R.string.disconnected)
    }

    fun showGuideDevicePicker() { _showGuideDevicePicker.value = true }
    fun hideGuideDevicePicker() { _showGuideDevicePicker.value = false }

    fun requestGuideConnect() {
        val mainSn = cameraManager.activeCamera?.cameraInfo?.serialNumber
        val devs = guideCameraManager.devices.value
            .filter { it.serialNumber != mainSn }
        when {
            devs.isEmpty() -> {
                pendingGuideConnect = true
                guideCameraManager.enumerateDevices()
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
        val mainSn = cameraManager.activeCamera?.cameraInfo?.serialNumber
        if (sn == mainSn) {
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
        _guideExposureUs.value = requestedUs
        prefs.edit().putFloat("guide_exposure_us", requestedUs).apply()
        val cam = guideCameraManager.activeCamera ?: return
        cam.setExposureTime(requestedUs)
        _guideExposureUs.value = cam.currentExposureUs
        prefs.edit().putFloat("guide_exposure_us", _guideExposureUs.value).apply()
    }

    fun setGuideGain(value: Float) {
        val cam = guideCameraManager.activeCamera ?: return
        cam.setGain(value)
        _guideGain.value = cam.currentGain
    }

    fun lockGuideStar() {
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
        _guideRunning.value = false
        _guideReferenceStar.value = null
        _guideReferenceStars.value = emptyList()
        _guideCorrection.value = null
        resetGuideAlgorithmState()
        _guideStatus.value = app.getString(R.string.guide_lock_cleared)
    }

    fun setGuideRunning(enabled: Boolean) {
        if (enabled) {
            if (guideCameraManager.activeCamera == null) {
                _guideStatus.value = app.getString(R.string.connect_guide_camera_first)
                return
            }
            if (_guideReferenceStar.value == null) {
                lockGuideStar()
            }
            if (_guideReferenceStar.value == null) return
            if (_guideCalibration.value == null) {
                _guideStatus.value = app.getString(R.string.calibrate_before_guiding)
                return
            }
            if (!mountModule.isConnected) {
                _guideStatus.value = app.getString(R.string.connect_mount_before_guiding)
                return
            }
            viewModelScope.launch {
                runCatching { mountModule.setGuideRate() }
            }
            if (!_guideRunning.value) {
                clearGuideHistory()
            }
            _guideStatus.value = app.getString(R.string.guiding)
        } else {
            _guideStatus.value = app.getString(R.string.guiding_stopped)
        }
        _guideRunning.value = enabled
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
        if (guideCameraManager.activeCamera == null) {
            _guideStatus.value = app.getString(R.string.connect_guide_camera_first)
            return
        }
        if (!mountModule.isConnected) {
            _guideStatus.value = app.getString(R.string.connect_mount_before_calibration)
            return
        }
        if (_guideCalibrating.value) return

        viewModelScope.launch {
            _guideRunning.value = false
            _guideCalibrating.value = true
            _guideCalibrationState.value = GuideCalibrationState.RUNNING
            _guideCalibration.value = null
            resetGuideAlgorithmState()
            runCatching { mountModule.setGuideRate() }

            val pulseMs = _guideCalibrationPulseMs.value
            try {
                _guideStatus.value = app.getString(R.string.calibration_locking_reference)
                val reference = awaitGuideStars()
                if (reference.isEmpty()) error(app.getString(R.string.no_guide_stars_detected))

                _guideStatus.value = app.getString(R.string.calibration_ra_east)
                val beforeEastSeq = guideFrameSequence
                pulseMount(MountDirection.EAST, pulseMs)
                kotlinx.coroutines.delay(settleDelayMs())
                val eastStars = awaitGuideStars(afterSequence = beforeEastSeq)
                if (eastStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_ra_pulse))
                val eastShift = measureStarShift(reference, eastStars)
                    ?: error(app.getString(R.string.unable_match_ra_stars))
                val eastVector = GuideVector(
                    eastShift.x / pulseMs,
                    eastShift.y / pulseMs
                )
                if (eastVector.length * pulseMs < 1.5f) error(app.getString(R.string.ra_calibration_too_small))

                _guideStatus.value = app.getString(R.string.calibration_ra_return)
                val beforeReturnSeq = guideFrameSequence
                pulseMount(MountDirection.WEST, pulseMs)
                kotlinx.coroutines.delay(settleDelayMs())
                val decReferenceStars = awaitGuideStars(afterSequence = beforeReturnSeq)
                if (decReferenceStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_ra_return))

                _guideStatus.value = app.getString(R.string.calibration_dec_north)
                val beforeNorthSeq = guideFrameSequence
                pulseMount(MountDirection.NORTH, pulseMs)
                kotlinx.coroutines.delay(settleDelayMs())
                val northStars = awaitGuideStars(afterSequence = beforeNorthSeq)
                if (northStars.isEmpty()) error(app.getString(R.string.no_guide_stars_after_dec_pulse))
                val northShift = measureStarShift(decReferenceStars, northStars)
                    ?: error(app.getString(R.string.unable_match_dec_stars))
                val northVector = GuideVector(
                    northShift.x / pulseMs,
                    northShift.y / pulseMs
                )
                if (northVector.length * pulseMs < 1.5f) error(app.getString(R.string.dec_calibration_too_small))

                _guideStatus.value = app.getString(R.string.calibration_dec_return)
                val beforeDecReturnSeq = guideFrameSequence
                pulseMount(MountDirection.SOUTH, pulseMs)
                kotlinx.coroutines.delay(settleDelayMs())
                val finalReference = awaitGuideStars(afterSequence = beforeDecReturnSeq).ifEmpty { reference }

                _guideCalibration.value = GuideCalibration(
                    eastXPerMs = eastVector.x,
                    eastYPerMs = eastVector.y,
                    northXPerMs = northVector.x,
                    northYPerMs = northVector.y,
                    pulseMs = pulseMs
                )
                _guideCalibrationState.value = GuideCalibrationState.COMPLETE
                _guideReferenceStars.value = finalReference
                _guideReferenceStar.value = finalReference.firstOrNull()
                _guideStatus.value = app.getString(R.string.calibration_complete_rates, _guideCalibration.value?.eastRatePxPerSec ?: 0f, _guideCalibration.value?.northRatePxPerSec ?: 0f)
            } catch (e: Throwable) {
                _guideCalibrationState.value = GuideCalibrationState.FAILED
                _guideStatus.value = app.getString(R.string.calibration_failed_detail, e.message.orEmpty())
                runCatching { mountModule.stopAllMotion() }
            } finally {
                _guideCalibrating.value = false
            }
        }
    }

    fun clearGuideCalibration() {
        _guideRunning.value = false
        _guideCalibration.value = null
        _guideCalibrationState.value = GuideCalibrationState.IDLE
        resetGuideAlgorithmState()
        _guideStatus.value = app.getString(R.string.guide_calibration_cleared)
    }

    private fun startGuidePreview() {
        val cam = guideCameraManager.activeCamera ?: return

        guidePreviewPipeline.start(
            onProcessed = { frame ->
                _guidePreviewBitmap.value = guidePreviewPipeline.frame.value?.bitmap
                val stars = detectGuideStars(frame)
                latestGuideStars.set(stars)
                _guideStars.value = stars
                _guideStar.value = stars.firstOrNull()
                processGuideCorrection(stars)
            },
            recycleBuffer = cam::recycleBuffer
        )

        cam.startCapture(object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                guideFrameSequence++
                guidePreviewPipeline.submit(frame)
            }
        })
    }

    private fun detectGuideStars(frame: FrameData, maxStars: Int = 16): List<GuideStar> =
        guideModule.detectStars(frame, maxStars)

    private suspend fun awaitGuideStars(
        timeoutMs: Long = 4000L,
        afterSequence: Long = -1L
    ): List<GuideStar> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val stars = latestGuideStars.get()
            if (stars.isNotEmpty() && guideFrameSequence > afterSequence) {
                return if (_guideMultiStarEnabled.value) stars.take(12) else stars.take(1)
            }
            kotlinx.coroutines.delay(100)
        }
        return emptyList()
    }

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

    private fun resetGuideAlgorithmState() = guideModule.resetFilter()

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

    private fun processGuideCorrection(stars: List<GuideStar>) {
        if (!_guideRunning.value) return
        val calibration = _guideCalibration.value ?: return
        if (stars.isEmpty()) {
            _guideStatus.value = app.getString(R.string.guide_star_lost)
            return
        }
        if (!mountModule.isConnected || guidePulseInProgress) return

        val now = System.currentTimeMillis()
        val minIntervalMs = (_guideExposureUs.value / 1000f).coerceIn(500f, 2500f).toLong()
        if (now - lastGuideCorrectionMs < minIntervalMs) return

        val error = measureGuideError(stars) ?: run {
            _guideStatus.value = app.getString(R.string.guide_stars_lost)
            return
        }
        val dx = error.x
        val dy = error.y
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
        viewModelScope.launch {
            guidePulseInProgress = true
            try {
                if (raDirection != null) pulseMount(raDirection, raPulse)
                if (decDirection != null) pulseMount(decDirection, decPulse)
                _guideStatus.value = app.getString(R.string.guide_pulse_status, raPulse, decPulse)
            } catch (e: Throwable) {
                _guideStatus.value = app.getString(R.string.guide_pulse_error_detail, e.message.orEmpty())
                mountModule.reportError(e.message ?: app.getString(R.string.guide_pulse_failed))
            } finally {
                guidePulseInProgress = false
            }
        }
    }

    private suspend fun pulseMount(direction: MountDirection, durationMs: Int) {
        mountModule.startGuidePulse(direction)
        kotlinx.coroutines.delay(durationMs.toLong())
        mountModule.stopGuidePulse(direction)
    }

    private fun startPreview() {
        val cam = cameraManager.activeCamera ?: return

        previewPipeline.start(
            paused = { _previewPaused.value },
            onProcessed = {
                _previewBitmap.value = previewPipeline.frame.value?.bitmap
                val detectedBits = frameProcessor.getDetectedEffectiveBits()
                if (detectedBits != _detectedBitDepth.value) {
                    _detectedBitDepth.value = detectedBits
                }
            },
            recycleBuffer = cam::recycleBuffer
        )

        startExposureTimer()
        cam.startCapture(object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                onFrameArrived()
                updateFps()

                fulfillFrameSnapshot(frame)

                if (autoExpFrameSkip++ % 10 == 0) {
                    val aeMax = if (_longExposureEnabled.value) {
                        ExposureLimits.absoluteMaxUs(cam)
                    } else {
                        ExposureLimits.uiMaxUs(cam, false)
                    }
                    autoExposureController.processFrame(frame, cam, exposureMaxUs = aeMax)
                    if (autoExposureController.mode != AutoExposureMode.OFF) {
                        _exposureUs.value = cam.currentExposureUs
                        _gain.value = cam.currentGain
                        _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
                    }
                }

                updateSoftwareStackingProgress(cam)

                if (_isRecording.value) {
                    if (recordWriteQueue.remainingCapacity() == 0) {
                        reportRecordingDrop()
                    } else {
                        val bpp = frame.pixelFormat.bytesPerPixel
                        val size = frame.width * frame.height * bpp
                        val copySize = size.coerceAtMost(frame.data.size)
                        val copy = recordBufferPool.acquire(copySize)
                        System.arraycopy(frame.data, 0, copy, 0, copySize)
                        val recFrame = frame.copy(data = copy)
                        if (!recordWriteQueue.offer(recFrame)) {
                            recordBufferPool.release(copy)
                            reportRecordingDrop()
                        }
                    }
                }

                // Ownership transfers to the preview pipeline after all synchronous readers finish.
                previewPipeline.submit(frame)
            }
        })
    }

    private fun reportRecordingDrop() {
        val dropped = recordDroppedFrames.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            Log.w(TAG, "Recording queue full, dropped=$dropped")
        }
    }

    private fun startRecordWriteThread() {
        recordWriteThread = Thread({
            while (true) {
                try {
                    val frame = recordWriteQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        var writerAvailable = true
                        try {
                            val ser = serWriter
                            val pser = pserWriter
                            val mp4 = mp4Writer
                            when {
                                ser != null && ser.isOpen -> {
                                    ser.writeFrame(frame)
                                    _recordingFrameCount.value = ser.currentFrameCount
                                    _recordingBytes.value = ser.totalBytesWritten
                                }
                                pser != null && pser.isOpen -> {
                                    pser.writeFrame(frame)
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
                                else -> writerAvailable = false
                            }
                        } finally {
                            recordBufferPool.release(frame.data)
                        }
                        if (!writerAvailable) break
                        _recordingDurationMs.value = System.currentTimeMillis() - recordingStartTime
                        
                        val limit = _recordLimit.value
                        val shouldStop = when (limit.type) {
                            RecordLimitType.FRAMES -> _recordingFrameCount.value >= limit.value
                            RecordLimitType.TIME -> _recordingDurationMs.value >= limit.value * 1000L
                            RecordLimitType.NONE -> false
                        }
                        if (shouldStop) {
                            viewModelScope.launch(Dispatchers.Main) {
                                stopRecording()
                                _statusMessage.value = app.getString(R.string.recording_limit_reached)
                            }
                            break
                        }
                    }
                    if (serWriter?.isOpen != true && pserWriter?.isOpen != true && mp4Writer?.isOpen != true) break
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Log.e(TAG, "RecordWriter error", e)
                    _statusMessage.value = app.getString(R.string.recording_error_detail, e.message.orEmpty())
                    break
                }
            }
        }, "RecordWriter").apply { start() }
    }

    private fun stopRecordWriteThread() {
        recordWriteThread?.interrupt()
        recordWriteThread?.join(3000)
        recordWriteThread = null
        // Drain remaining frames
        while (true) {
            val frame = recordWriteQueue.poll() ?: break
            var writeSucceeded = true
            try {
                val ser = serWriter
                val pser = pserWriter
                val mp4 = mp4Writer
                when {
                    ser != null && ser.isOpen -> ser.writeFrame(frame)
                    pser != null && pser.isOpen -> pser.writeFrame(frame)
                    mp4 != null && mp4.isOpen -> mp4.writeFrame(frame)
                }
            } catch (_: Throwable) {
                writeSucceeded = false
            } finally {
                recordBufferPool.release(frame.data)
            }
            if (!writeSucceeded) break
        }
        while (true) {
            val frame = recordWriteQueue.poll() ?: break
            recordBufferPool.release(frame.data)
        }
        recordBufferPool.clear()
    }

    fun setExposure(us: Float) {
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
        cam.setExposureTime(clamped)
        _exposureUs.value = cam.currentExposureUs
        updateSoftwareStackingProgress(cam)
    }

    fun toggleLongExposure() {
        val cam = cameraManager.activeCamera ?: return
        val newState = !_longExposureEnabled.value
        _longExposureEnabled.value = newState
        cam.longExposureEnabled = newState
        refreshExposureUiLimits()
        if (!newState) {
            val shortMax = ExposureLimits.uiMaxUs(cam, false)
            if (cam.currentExposureUs > shortMax) {
                cam.setExposureTime(shortMax)
                _exposureUs.value = cam.currentExposureUs
            }
            _longExposureProgress.value = ""
        }
        _statusMessage.value = app.getString(
            if (newState) R.string.long_exposure_enabled else R.string.long_exposure_disabled
        )
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
        val cam = cameraManager.activeCamera ?: return
        cam.setGain(value)
        _gain.value = cam.currentGain
        _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
    }

    fun setOffset(value: Float) {
        val camera = (cameraManager.activeCamera as? CameraOffsetCapable)
            ?.takeIf { it.offsetSupported } ?: return
        camera.setOffset(value)
        syncOffsetCapability(cameraManager.activeCamera)
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
        pixelFormatSwitching = true
        viewModelScope.launch(Dispatchers.IO) {
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
                syncOffsetCapability(cam)
                syncUsbBandwidthCapability(cam)
                pixelFormatSwitching = false
            }
        }
    }

    fun setReadoutMode(mode: ReadoutMode) {
        val cam = cameraManager.activeCamera ?: return
        cam.setReadoutMode(mode)
        _readoutMode.value = cam.currentReadoutMode
        _gain.value = cam.currentGain
        _gainCapability.value = cam.gainCapability
        _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
                _pixelFormat.value = cam.currentPixelFormat
                _supportedPixelFormats.value = cam.supportedPixelFormats
                _gainCapability.value = cam.gainCapability
                _gainDbEquivalent.value = cam.gainDbEquivalent(cam.currentGain)
        syncOffsetCapability(cam)
        frameProcessor.resetBitShiftDetection(
            forceDeclaredLayout = cam is PlayerOneCamera &&
                cam.currentReadoutMode == ReadoutMode.HDR
        )
    }

    fun setNativeReadoutMode(id: String) {
        val camera = cameraManager.activeCamera as? CameraNativeReadoutModeCapable ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (camera.setNativeReadoutMode(id)) {
                _nativeReadoutModes.value = camera.supportedNativeReadoutModes
                _nativeReadoutModeId.value = camera.currentNativeReadoutModeId
                _pixelFormat.value = cameraManager.activeCamera?.currentPixelFormat ?: _pixelFormat.value
                _supportedPixelFormats.value = cameraManager.activeCamera?.supportedPixelFormats ?: _supportedPixelFormats.value
                _roi.value = cameraManager.activeCamera?.currentRoi ?: _roi.value
                _gainCapability.value = cameraManager.activeCamera?.gainCapability
                _gainDbEquivalent.value = cameraManager.activeCamera?.let { it.gainDbEquivalent(it.currentGain) }
                syncOffsetCapability(cameraManager.activeCamera)
            }
        }
    }

    fun cameraDefaults(): CameraDefaults? {
        val info = cameraManager.activeCamera?.cameraInfo ?: return null
        return deviceSettings.cameraDefaults(cameraSettingsId(info))
    }

    fun saveCameraDefaults(settings: CameraDefaults): CameraDefaults? {
        val camera = cameraManager.activeCamera ?: return null
        val info = camera.cameraInfo ?: return null
        val normalized = settings.gain?.let { GainValueNormalizer.normalize(camera.gainCapability, it) }
        val normalizedSettings = settings.copy(gain = normalized)
        deviceSettings.saveCameraDefaults(cameraSettingsId(info), normalizedSettings)
        applyCameraDefaults(camera)
        val persistedSettings = normalizedSettings.copy(
            gain = normalized?.let { camera.currentGain }
        )
        deviceSettings.saveCameraDefaults(cameraSettingsId(info), persistedSettings)
        _gain.value = camera.currentGain
        _gainCapability.value = camera.gainCapability
        _gainDbEquivalent.value = camera.gainDbEquivalent(camera.currentGain)
        _pixelFormat.value = camera.currentPixelFormat
        _readoutMode.value = camera.currentReadoutMode
        syncOffsetCapability(camera)
        return persistedSettings
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

    fun saveFocuserDefaults(settings: FocuserDefaults) {
        val deviceId = eafInfo.value?.name ?: "focuser"
        deviceSettings.saveFocuserDefaults(deviceId, settings)
        applyFocuserDefaults(settings)
    }

    fun coverDefaults(): CoverDefaults =
        deviceSettings.coverDefaults(coverDeviceInfo.value ?: "cover")

    fun saveCoverDefaults(settings: CoverDefaults) {
        val deviceId = coverDeviceInfo.value ?: "cover"
        deviceSettings.saveCoverDefaults(deviceId, settings)
        settings.brightness?.let(::setCalibratorBrightness)
    }

    private fun applyCameraDefaults(camera: Camera) {
        val info = camera.cameraInfo ?: return
        val settings = deviceSettings.cameraDefaults(cameraSettingsId(info))
        settings.readoutMode
            ?.takeIf { it in camera.supportedReadoutModes }
            ?.let(camera::setReadoutMode)
        settings.nativeReadoutModeId?.let { id ->
            (camera as? CameraNativeReadoutModeCapable)?.let { readoutCapable ->
                viewModelScope.launch(Dispatchers.IO) {
                    if (readoutCapable.setNativeReadoutMode(id)) {
                        _nativeReadoutModes.value = readoutCapable.supportedNativeReadoutModes
                        _nativeReadoutModeId.value = readoutCapable.currentNativeReadoutModeId
                    }
                }
            }
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
        val cam = cameraManager.activeCamera ?: return
        cam.setRoi(roi)
        _roi.value = cam.currentRoi
    }

    fun resetRoi() {
        val cam = cameraManager.activeCamera ?: return
        cam.resetRoi()
        _roi.value = cam.currentRoi
    }

    fun setAutoStretch(enabled: Boolean) {
        frameProcessor.autoStretchEnabled = enabled
        _autoStretch.value = enabled
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
            cam.stopCapture()
            stopExposureTimer()
        } else {
            startPreview()
        }
    }
    fun toggleFlipH() { _flipH.value = !_flipH.value }
    fun toggleFlipV() { _flipV.value = !_flipV.value }
    fun setRotation(deg: Int) { _rotation.value = deg % 360 }

    fun toggleFocusAssist() {
        val newState = !_focusAssistEnabled.value
        _focusAssistEnabled.value = newState
        frameProcessor.focusAssistEnabled = newState
        if (newState) {
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
        fwController.setPosition(pos)
    }

    fun resetFilterWheel() {
        fwController.resetWheel()
    }

    fun setFilterWheelSlotName(index: Int, name: String) {
        fwController.setSlotName(index, name)
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

    fun disconnectFilterWheel() {
        accessoryManager.disconnectFilterWheel()
    }

    fun disconnectEaf() {
        accessoryManager.disconnectFocuser()
    }

    fun disconnectCover() = accessoryManager.disconnectCover()
    fun disconnectRotator() = accessoryManager.disconnectRotator()
    fun openCover() = accessoryManager.coverController.openCover()
    fun closeCover() = accessoryManager.coverController.closeCover()
    fun haltCover() = accessoryManager.coverController.halt()
    fun setCalibratorBrightness(value: Int) =
        accessoryManager.coverController.setBrightness(value)
    fun calibratorOff() = accessoryManager.coverController.calibratorOff()
    fun moveRotatorTo(angle: Double) = accessoryManager.rotatorController.moveTo(angle)
    fun moveRotatorRelative(delta: Double) =
        accessoryManager.rotatorController.moveRelative(delta)
    fun haltRotator() = accessoryManager.rotatorController.halt()
    fun homeRotator() = accessoryManager.rotatorController.home()
    fun zeroRotator() = accessoryManager.rotatorController.setZero()
    fun setRotatorReversed(reversed: Boolean) =
        accessoryManager.rotatorController.setReversed(reversed)
    fun setRotatorHold(enabled: Boolean) = accessoryManager.rotatorController.setHold(enabled)
    fun setRotatorStepsPerDegree(value: Int) =
        accessoryManager.rotatorController.setStepsPerDegree(value)

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
        autoExposureController.mode = mode
        if (mode != AutoExposureMode.OFF) autoExposureController.reset()
        _autoExposureMode.value = mode
    }

    private fun winJuposTimestamp(): String {
        val now = LocalDateTime.now()
        val tenthMinute = now.second / 6
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + "_$tenthMinute"
    }

    fun startRecording() {
        if (!License.canRecord) {
            _statusMessage.value = app.getString(R.string.trial_recording_disabled)
            return
        }
        try {
            val cam = cameraManager.activeCamera ?: return
            val roi = cam.currentRoi
            val ts = winJuposTimestamp()
            val target = _targetName.value.ifBlank { "Pla" }
            val info = cam.cameraInfo
            val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
            val filterName = currentFilterName()
            val filterPart = if (filterName != null) "-$filterName" else ""

            val fmt = _recordFormat.value
            val pixFmt = cam.currentPixelFormat
            val useSERForPser8bit = fmt == RecordFormat.PSER && pixFmt.nativeBits <= 8
            val effectiveExt = when {
                useSERForPser8bit -> "ser"
                fmt == RecordFormat.MP4 -> "mp4"
                fmt == RecordFormat.PSER -> "pser"
                else -> "ser"
            }
            val dir = File(
                getApplication<Application>().getExternalFilesDir("recordings"),
                effectiveExt.uppercase()
            )
            dir.mkdirs()
            val file = File(dir, "$ts-$target$filterPart-$cameraShort.$effectiveExt")

            when {
                fmt == RecordFormat.SER || useSERForPser8bit -> {
                    val ser = SERWriter(file)
                    ser.open(roi.width, roi.height, pixFmt, cam.cameraInfo?.name, filterName)
                    serWriter = ser
                }
                fmt == RecordFormat.PSER -> {
                    val pser = PSERWriter(file)
                    pser.open(roi.width, roi.height, pixFmt, cam.cameraInfo?.name, filterName)
                    pserWriter = pser
                }
                fmt == RecordFormat.MP4 -> {
                    val mp4 = Mp4Writer(file)
                    mp4.wbRedGain = frameProcessor.wbRedGain
                    mp4.wbGreenGain = frameProcessor.wbGreenGain
                    mp4.wbBlueGain = frameProcessor.wbBlueGain
                    val recordFps = _fps.value.toInt().coerceIn(1, 60)
                    mp4.open(roi.width, roi.height, recordFps)
                    mp4Writer = mp4
                }
            }

            currentRecordingFile = file
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
            startRecordWriteThread()
            Log.i(TAG, "Recording started ($effectiveExt ${pixFmt.name}): ${file.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed", e)
            _statusMessage.value = app.getString(R.string.record_error_detail, e.message.orEmpty())
        }
    }

    fun stopRecording() {
        closeRecordingResources(saveToGallery = true)
        _statusMessage.value = app.getString(R.string.recording_stopped)
    }

    private fun closeRecordingResources(saveToGallery: Boolean) {
        _isRecording.value = false
        stopRecordWriteThread()
        try { serWriter?.close() } catch (e: Throwable) { Log.e(TAG, "SER close error", e) }
        serWriter = null
        try { pserWriter?.close() } catch (e: Throwable) { Log.e(TAG, "PSER close error", e) }
        pserWriter = null
        try { mp4Writer?.close() } catch (e: Throwable) { Log.e(TAG, "MP4 close error", e) }
        mp4Writer = null

        val recordedFile = currentRecordingFile
        currentRecordingFile = null
        if (saveToGallery && recordedFile != null &&
            recordedFile.extension.equals("mp4", ignoreCase = true) && recordedFile.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                saveMp4ToGallery(recordedFile)
            }
        }
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
        when (_captureFormat.value) {
            CaptureFormat.FITS -> captureFits()
            CaptureFormat.JPG -> captureJpg()
        }
    }

    suspend fun captureTempFitsForPlateSolve(): File? = withContext(Dispatchers.IO) {
        val cam = cameraManager.activeCamera ?: return@withContext null
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
            configuredFormat = cam.currentPixelFormat,
            pixelSizeUm = info?.pixelSizeUm,
            focalLengthMm = focalLengthMm,
            binning = 1
        )
        file
    }

    private fun captureFits() {
        if (!License.canRecord) {
            _statusMessage.value = app.getString(R.string.trial_capture_disabled)
            return
        }
        val cam = cameraManager.activeCamera ?: return
        val filterName = currentFilterName()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val frame = awaitFrameSnapshot() ?: return@launch
                val dir = File(
                    getApplication<Application>().getExternalFilesDir("captures"),
                    "FITS"
                )
                dir.mkdirs()
                val ts = winJuposTimestamp()
                val target = _targetName.value.ifBlank { "Pla" }
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
                    configuredFormat = cam.currentPixelFormat,
                    pixelSizeUm = info?.pixelSizeUm,
                    focalLengthMm = focalLengthMm,
                    binning = 1
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val frame = awaitFrameSnapshot() ?: return@launch
                val dir = File(
                    getApplication<Application>().getExternalFilesDir("captures"),
                    "JPG"
                )
                dir.mkdirs()
                val ts = winJuposTimestamp()
                val target = _targetName.value.ifBlank { "Pla" }
                val info = cam.cameraInfo
                val cameraShort = (info?.sensorName ?: info?.name)?.replace(" ", "")?.take(20) ?: "Camera"
                val filterPart = if (filterName != null) "-$filterName" else ""
                val fileName = "$ts-$target$filterPart-$cameraShort.jpg"
                val file = File(dir, fileName)

                val bmp = captureFrameProcessor.frameToBitmap(frame)
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

    private fun fulfillFrameSnapshot(frame: FrameData) {
        val request = pendingFrameSnapshot.getAndSet(null) ?: return
        if (!request.isActive) return
        try {
            val expectedSize = (
                frame.width.toLong() * frame.height * frame.pixelFormat.bytesPerPixel
            ).coerceAtMost(frame.data.size.toLong()).toInt()
            request.complete(frame.copy(data = frame.data.copyOf(expectedSize)))
        } catch (error: Throwable) {
            request.completeExceptionally(error)
        }
    }

    private suspend fun awaitFrameSnapshot(timeoutMs: Long = 45_000L): FrameData? =
        frameSnapshotMutex.withLock {
            val request = CompletableDeferred<FrameData>()
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
