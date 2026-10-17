package com.indigo.mobileobservatory.stacking

import android.app.Application
import com.indigo.mobileobservatory.util.FileLogger
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.provider.OpenableColumns
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SerEndianConvention
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** PLANETARY is the historic persisted name of the offline workflow; its target is separate. */
enum class StackMode { LIVE, PLANETARY }
enum class StackTaskPhase { EMPTY, READING, READY, ANALYZING, ANALYZED, REGISTERING, STACKING, PROCESSING, EXPORTING, COMPLETE, CANCELLED, FAILED }

data class StackSourceInfo(val file: File, val width: Int, val height: Int, val channels: Int,
    val bits: Int, val frames: Long, val identity: String, val isBayer: Boolean = false,
    val headerColorId: Int = 0, val effectiveLayout: String = "MONO",
    val decodingIdentity: String = identity,
    val canOverrideCfa: Boolean = true)

data class StackingTaskState(
    val phase: StackTaskPhase = StackTaskPhase.EMPTY,
    val completed: Long = 0,
    val total: Long = 0,
    val source: StackSourceInfo? = null,
    val histogram: List<Long> = emptyList(),
    val qualityFrames: Long = 0,
    val referenceFrame: Long = 0,
    val stats: PlanetaryStackStats? = null,
    val deepSkyStats: OfflineDeepSkyStats? = null,
    val message: String = "",
    val javaPeakBytes: Long = 0,
    val nativePeakBytes: Long = 0,
    val exportedFiles: List<String> = emptyList()
) {
    val busy get() = phase in setOf(StackTaskPhase.READING, StackTaskPhase.ANALYZING,
        StackTaskPhase.REGISTERING, StackTaskPhase.STACKING, StackTaskPhase.PROCESSING, StackTaskPhase.EXPORTING)
}

data class StackProduct(val image: LinearImage, val weights: FloatArray, val metadata: Map<String, String>)

/**
 * ViewModel-owned science workspace. A single CPU worker serializes analysis and accumulation;
 * a second worker renders at most one preview. No task owns an Activity or a camera connection.
 * Foreground-only processing: backgrounding pauses live capture consumption and cancels offline
 * work; configuration changes and page navigation keep the ViewModel and completed results.
 */
class StackingCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val captureTemperature: () -> Double? = { null },
    calibrationDirectory: File = File(app.filesDir, "stack-calibration"),
    private val isSourceBusy: (File) -> Boolean = { false }
) : Closeable {
    // Only short state transitions use this monitor; decoding/processing and joins happen outside.
    private val stateLock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "StackScience").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
    private val renderer = Executors.newSingleThreadExecutor { Thread(it, "StackPreview").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
    private val scienceScope = CoroutineScope(scope.coroutineContext + worker)
    private val _destination = MutableStateFlow<StackMode?>(null)
    val destination = _destination.asStateFlow()
    private val _liveTarget = MutableStateFlow(StackTarget.DEEP_SKY)
    val liveTarget = _liveTarget.asStateFlow()
    private val _offlineTarget = MutableStateFlow(StackTarget.PLANETARY)
    val offlineTarget = _offlineTarget.asStateFlow()
    private val _task = MutableStateFlow(StackingTaskState())
    val task = _task.asStateFlow()
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview = _preview.asStateFlow()
    private val _livePreview = MutableStateFlow<Bitmap?>(null)
    val livePreview = _livePreview.asStateFlow()
    private val _showLiveStack = MutableStateFlow(true)
    val showLiveStack = _showLiveStack.asStateFlow()
    private val _processing = MutableStateFlow(StackProcessingParameters.preset(ProcessingPreset.NATURAL))
    val processing = _processing.asStateFlow()
    private val _demosaicAlgorithm = MutableStateFlow(DemosaicAlgorithm.DDFAPD)
    val demosaicAlgorithm = _demosaicAlgorithm.asStateFlow()
    private val _serCfaInterpretation = MutableStateFlow(SerCfaInterpretation.AUTO)
    val serCfaInterpretation = _serCfaInterpretation.asStateFlow()
    // Keep only small model statistics in state; full processed float images are not retained.
    private val _backgroundWarning = MutableStateFlow("")
    val backgroundWarning = _backgroundWarning.asStateFlow()
    private val _rendering = MutableStateFlow(false)
    val rendering = _rendering.asStateFlow()
    private val recipes = mutableMapOf<Triple<StackMode, StackTarget, ProcessingPreset>, StackProcessingParameters>()
    private val selectedPresets = mutableMapOf<Pair<StackMode, StackTarget>, ProcessingPreset>()
    private var pendingAutoStretchMode: StackMode? = null
    // Consumed only when an estimate is committed. Navigation/cancellation cannot steal the
    // initial opportunity; deliberate recipe edits do, even before the first accepted frame.
    private var liveAutoStretchEligible = true
    @Volatile private var activeMode = StackMode.LIVE
    @Volatile private var liveProduct: StackProduct? = null
    @Volatile private var planetaryProduct: StackProduct? = null
    @Volatile private var renderSerial = 0L
    private var renderJob: Job? = null
    private var taskJob: Job? = null
    private var taskSerial = 0L
    private var taskRunning = false
    private var pendingRenderMode: StackMode? = null
    private var foreground = true
    private var closed = false
    private var planetaryPreview: Bitmap? = null
    private var analysis: PlanetaryAnalysis? = null
    private var analysisIdentity = ""
    private var analysisCalibration: CalibrationSelection? = null
    private var analysisDemosaic: DemosaicAlgorithm? = null
    private var sourceFile: File? = null
    var endianConvention: SerEndianConvention = SerEndianConvention.COMPATIBILITY
        private set
    // Use one explicit layout for the sequence: dim left-aligned frames must not rescale differently.
    var sampleAlignment: SampleAlignment = SampleAlignment.RIGHT
        private set
    val memoryBudgetBytes: Long = minOf(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() * 2 / 5)
    private val cache = File(app.cacheDir, "stack-analysis")
    private val engine = PlanetaryStacker()
    @Volatile private var liveCalibration = CalibrationSelection()
    @Volatile private var liveCalibrationWarnings: List<String> = emptyList()
    @Volatile private var liveDemosaicScratchBytes = 0L
    private val liveSensorWorkspace = LinearPixelDecoder.SensorWorkspace()
    private val liveCalibrationWorkspace = CalibrationSelection.Workspace()
    private val liveDemosaicWorkspace = DdfapdDemosaicer.Workspace()
    private val backgroundModelCache = BackgroundModelCache()
    val live: LiveStackSession = LiveStackSession(
        scope = scienceScope,
        engineFactory = ::createLiveEngine,
        decode = { frame, key ->
            val fixedCalibration = liveCalibration
            val generation = liveGeneration()
            val cancelCheck = {
                if (closed || liveGeneration() != generation) throw CancellationException("stack_cancelled")
            }
            val traits = key.pixelTraits.copy(
                roiX = key.roi.x, roiY = key.roi.y, binning = key.hardwareBin
            )
            val sensor = LinearPixelDecoder.decodeSensorBorrowed(frame, traits,
                maxPixels = LinearImage.MAX_PIXELS, workspace = liveSensorWorkspace, cancelCheck = cancelCheck)
            val geometry = CalibrationGeometry.from(frame.width, frame.height, frame.pixelFormat, traits)
            val calibrated = fixedCalibration.applyBorrowed(sensor, geometry, liveCapture(key),
                liveCalibrationWorkspace, cancelCheck)
            val decoded = if (key.demosaicAlgorithm == DemosaicAlgorithm.DDFAPD)
                LinearPixelDecoder.demosaicSensorBorrowed(calibrated, frame.pixelFormat, traits,
                    liveDemosaicWorkspace, cancelCheck)
            else LinearPixelDecoder.demosaicSensor(calibrated, frame.pixelFormat, traits,
                key.demosaicAlgorithm, liveDemosaicWorkspace, cancelCheck)
            LinearPixelDecoder.bin(decoded, key.softwareBin)
        },
        onSnapshot = { snapshot -> synchronized(stateLock) {
            // Validate and publish under the SAME monitor as resetLive. A delayed callback
            // cannot pass the epoch check and then repopulate a freshly reset workspace.
            if (snapshot == null || closed) {
                liveProduct = null
                _livePreview.value = null
                if (activeMode == StackMode.LIVE) _preview.value = null
            } else if (snapshot.state.generation == liveGeneration()) {
                val imageChanged = liveProduct?.image !== snapshot.image
                liveProduct = StackProduct(snapshot.image, snapshot.weights, liveMetadata(snapshot))
                if (imageChanged && activeMode == StackMode.LIVE) scheduleRender(StackMode.LIVE, replaceRunning = false)
            }
        } },
        nativeHeapBytes = { Debug.getNativeHeapAllocatedSize() },
        onWorkerReset = {
            liveSensorWorkspace.release()
            liveCalibrationWorkspace.release()
            liveDemosaicWorkspace.release()
        }
    )

    val calibration = CalibrationCoordinator(app, scienceScope, stateLock, memoryBudgetBytes,
        lockReason = {
            if (closed || taskRunning || sourceFile != null || analysis != null || planetaryProduct != null ||
                live.state.value.phase != LiveStackPhase.EMPTY) "calibration_reset_required" else null
        }, isSourceBusy = isSourceBusy, endian = { endianConvention }, alignment = { sampleAlignment }, directory = calibrationDirectory)

    init {
        scope.launch { combine(live.state, task) { _, _ -> Unit }.collect { calibration.updateLock() } }
    }

    private fun liveCapture(key: StackSessionKey) = CalibrationCapture.from(key).copy(temperatureC = captureTemperature())

    private fun liveGeneration(): Long = live.state.value.generation

    private fun createLiveEngine(): LiveStackEngine {
        val budget = memoryBudgetBytes - liveCalibration.memoryBytes - liveDemosaicScratchBytes
        return if (live.state.value.key?.target == StackTarget.PLANETARY) LivePlanetaryEngine(budget)
        else LiveDeepSkyEngine(budget)
    }

    fun open(mode: StackMode, file: File? = null) {
        synchronized(stateLock) {
            if (closed) return
            cancelRenderingLocked()
            activateModeLocked(mode)
            _destination.value = mode
            _preview.value = if (mode == StackMode.LIVE) _livePreview.value else planetaryPreview
            scheduleRender(mode)
        }
        if (mode == StackMode.PLANETARY && file != null && sourceFile != file) loadFile(file)
    }

    fun leave() = synchronized(stateLock) {
        _destination.value = null
        rememberRecipeLocked(_processing.value)
        // The camera screen displays livePreview. Returning from an offline workspace must
        // therefore resume live rendering, even if opening that workspace could not run a task.
        if (activeMode != StackMode.LIVE && live.state.value.phase !in setOf(LiveStackPhase.EMPTY, LiveStackPhase.CLOSED)) {
            cancelRenderingLocked()
            activateModeLocked(StackMode.LIVE)
            _preview.value = _livePreview.value
            scheduleRender(StackMode.LIVE)
        }
    }

    private fun rememberRecipeLocked(parameters: StackProcessingParameters) {
        recipes[Triple(activeMode, target(activeMode), parameters.preset)] = parameters
        selectedPresets[activeMode to target(activeMode)] = parameters.preset
    }

    private fun activateModeLocked(mode: StackMode) {
        rememberRecipeLocked(_processing.value)
        if (activeMode == mode) return
        pendingAutoStretchMode = null
        activeMode = mode
        restoreRecipeLocked(mode)
    }
    private fun restoreRecipeLocked(mode: StackMode) {
        val preset = selectedPresets[mode to target(mode)] ?: ProcessingPreset.NATURAL
        _processing.value = recipes[Triple(mode, target(mode), preset)] ?: StackProcessingParameters.preset(preset)
    }

    fun target(mode: StackMode): StackTarget = if (mode == StackMode.LIVE) _liveTarget.value else _offlineTarget.value

    fun targetChangeNeedsReset(mode: StackMode): Boolean = synchronized(stateLock) {
        if (mode == StackMode.LIVE) live.state.value.phase != LiveStackPhase.EMPTY || liveProduct != null
        else taskRunning || analysis != null || planetaryProduct != null ||
            _task.value.phase !in setOf(StackTaskPhase.EMPTY, StackTaskPhase.READY)
    }

    /** Saving owns an immutable result independently of whichever workflow is visible. */
    fun targetChangeBlockedReason(mode: StackMode): String? = synchronized(stateLock) {
        when {
            taskRunning && _task.value.phase == StackTaskPhase.EXPORTING -> "stack_export_busy"
            mode == StackMode.LIVE && taskRunning -> "finish_offline_first"
            else -> null
        }
    }

    /** A confirmed switch discards the old science session before any new target can start. */
    fun setTarget(mode: StackMode, newTarget: StackTarget, resetConfirmed: Boolean = false): Boolean = synchronized(stateLock) {
        if (closed || calibration.state.value.busy) return false
        if (target(mode) == newTarget) return true
        targetChangeBlockedReason(mode)?.let { report(it); return false }
        if (targetChangeNeedsReset(mode) && !resetConfirmed) return false
        // Live export owns the same task/CPU worker. Do not let a live target change silently
        // cancel an unrelated offline job or reinterpret its immutable output metadata.
        if (mode == StackMode.LIVE && taskRunning) return false
        if (activeMode == mode) rememberRecipeLocked(_processing.value)
        cancelRenderingLocked()
        backgroundModelCache.clear()
        if (pendingAutoStretchMode == mode) pendingAutoStretchMode = null
        if (mode == StackMode.LIVE) {
            resetLive()
            _liveTarget.value = newTarget
        } else {
            _offlineTarget.value = newTarget
            planetaryProduct = null
            planetaryPreview = null
            if (activeMode == mode) _preview.value = null
            val retainedFile = sourceFile ?: _task.value.source?.file
            if (live.state.value.phase != LiveStackPhase.EMPTY) {
                // The live science worker owns acquisition. Dropping an offline analysis is
                // safe here because no offline worker can run concurrently, but re-reading
                // would fail the live admission gate and leave the old histogram behind.
                analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
                analysisIdentity = ""
                sourceFile = retainedFile
                _task.value = StackingTaskState(phase = if (retainedFile == null) StackTaskPhase.EMPTY else StackTaskPhase.READY,
                    source = _task.value.source?.takeIf { it.file == retainedFile })
            } else if (retainedFile != null) loadFile(retainedFile) else clearPlanetary()
        }
        if (activeMode == mode) restoreRecipeLocked(mode)
        true
    }
    fun toggleLiveDisplay() { _showLiveStack.value = !_showLiveStack.value }
    fun hasPlanetaryWork(): Boolean = synchronized(stateLock) { taskRunning }
    fun hasResult(mode: StackMode = activeMode) = product(mode) != null
    private fun product(mode: StackMode) = if (mode == StackMode.LIVE) liveProduct else planetaryProduct

    fun liveInputIssue(key: StackSessionKey): String? =
        LiveStackAdmission.issue(key, memoryBudgetBytes, calibration.memoryBytes, calibration.memoryBytes > 0)

    fun startLive(key: StackSessionKey) = synchronized(stateLock) {
        if (closed) return
        FileLogger.i("Stacking", "Live requested input=${key.width}x${key.height} format=${key.format} " +
            "hardwareBin=${key.hardwareBin} softwareBin=${key.softwareBin} target=${key.target} " +
            "demosaic=${key.demosaicAlgorithm} budget=$memoryBudgetBytes calibrationBytes=${calibration.memoryBytes}")
        // Button recomposition can lag a rapid second tap; starting is idempotent here.
        if (live.state.value.phase != LiveStackPhase.EMPTY) return
        if (key.target != _liveTarget.value) { report("stack_target_changed"); return }
        if (key.demosaicAlgorithm != _demosaicAlgorithm.value) { report("demosaic_changed"); return }
        liveInputIssue(key)?.let { report(it); return }
        if (taskRunning || planetaryProduct != null) {
            report("finish_offline_first")
            return
        }
        val selectedCalibration = try { calibration.snapshot() } catch (error: Exception) {
            report(error.message ?: "calibration_failed"); return
        }
        val traits = key.pixelTraits.copy(roiX = key.roi.x, roiY = key.roi.y, binning = key.hardwareBin)
        val geometry = try { CalibrationGeometry.from(key.width, key.height, key.format, traits) }
            catch (error: Exception) { report(error.message ?: "calibration_geometry_mismatch"); return }
        val capture = liveCapture(key)
        try { selectedCalibration.validate(geometry, capture) }
        catch (error: Exception) { report(error.message ?: "calibration_settings_mismatch"); return }
        val demosaicScratch = LinearPixelDecoder.demosaicExtraBytes(key.width, key.height, key.format, key.demosaicAlgorithm)
        activateModeLocked(StackMode.LIVE)
        liveCalibration = selectedCalibration
        liveCalibrationWarnings = selectedCalibration.unknownWarnings(geometry, capture)
        liveDemosaicScratchBytes = demosaicScratch
        live.start(key)
        _task.value = _task.value.copy(message = "", exportedFiles = emptyList())
    }

    fun acceptFrame(frame: FrameData, key: StackSessionKey) { live.offer(frame, key) }
    fun resetLive() = synchronized(stateLock) {
        cancelRenderingLocked()
        backgroundModelCache.clear()
        if (pendingAutoStretchMode == StackMode.LIVE) pendingAutoStretchMode = null
        liveAutoStretchEligible = true
        live.reset()
        liveCalibration = CalibrationSelection()
        liveCalibrationWarnings = emptyList()
        liveDemosaicScratchBytes = 0L
    }

    /** One-shot display adaptation on the existing renderer; raw accumulation is unchanged. */
    fun autoStretch() = synchronized(stateLock) {
        if (closed) return
        if (product(activeMode) == null) { report("auto_stretch_unavailable"); return }
        if (activeMode == StackMode.LIVE) liveAutoStretchEligible = false
        pendingAutoStretchMode = activeMode
        scheduleRender(activeMode)
    }

    private fun userChangedRecipeLocked() {
        pendingAutoStretchMode = null
        if (activeMode == StackMode.LIVE) liveAutoStretchEligible = false
    }

    fun selectPreset(preset: ProcessingPreset) = synchronized(stateLock) {
        userChangedRecipeLocked()
        rememberRecipeLocked(_processing.value)
        _processing.value = recipes[Triple(activeMode, target(activeMode), preset)] ?: StackProcessingParameters.preset(preset)
        rememberRecipeLocked(_processing.value)
        scheduleRender(activeMode)
    }

    fun updateProcessing(parameters: StackProcessingParameters) = synchronized(stateLock) {
        userChangedRecipeLocked()
        _processing.value = parameters
        rememberRecipeLocked(parameters)
        scheduleRender(activeMode)
    }

    fun resetProcessing() = updateProcessing(StackProcessingParameters.preset(_processing.value.preset))

    /** A decoder choice is part of the scientific input, not a display recipe. */
    fun setDemosaicAlgorithm(algorithm: DemosaicAlgorithm): Unit = synchronized(stateLock) {
        if (closed || algorithm == _demosaicAlgorithm.value) return
        if (live.state.value.phase != LiveStackPhase.EMPTY) { report("reset_live_first"); return }
        if (taskRunning || renderJob != null || calibration.state.value.busy) { report("demosaic_busy"); return }
        cancelRenderingLocked()
        _demosaicAlgorithm.value = algorithm
        analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
        planetaryProduct = null; planetaryPreview = null
        if (activeMode == StackMode.PLANETARY) _preview.value = null
        val file = sourceFile
        _task.update { it.copy(phase = if (file == null) StackTaskPhase.EMPTY else StackTaskPhase.READY,
            completed = 0, total = 0, histogram = emptyList(), qualityFrames = 0, referenceFrame = 0,
            stats = null, deepSkyStats = null, message = "", exportedFiles = emptyList()) }
        // Reserve the reload before releasing the monitor; Analyze cannot use the old decode.
        if (file != null) loadFile(file)
    }

    fun setDecodeOptions(endian: SerEndianConvention, alignment: SampleAlignment): Unit = synchronized(stateLock) {
        if (taskRunning || calibration.state.value.busy) return
        require(alignment != SampleAlignment.AUTO) { "Choose an explicit sequence bit alignment" }
        endianConvention = endian
        sampleAlignment = alignment
        sourceFile?.let(::loadFile)
    }

    /** Reinterpret raw sensor samples, invalidate scientific products, and rebuild the preview. */
    fun setSerCfaInterpretation(interpretation: SerCfaInterpretation): Unit = synchronized(stateLock) {
        if (closed || interpretation == _serCfaInterpretation.value) return
        if (live.state.value.phase != LiveStackPhase.EMPTY) { report("reset_live_first"); return }
        if (taskRunning || renderJob != null || calibration.state.value.busy) { report("demosaic_busy"); return }
        if (_task.value.source?.canOverrideCfa == false && interpretation != SerCfaInterpretation.AUTO) {
            report("cfa_rgb_not_raw"); return
        }
        cancelRenderingLocked()
        _serCfaInterpretation.value = interpretation
        analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
        planetaryProduct = null; planetaryPreview = null
        if (activeMode == StackMode.PLANETARY) _preview.value = null
        val file = sourceFile
        _task.update { it.copy(phase = if (file == null) StackTaskPhase.EMPTY else StackTaskPhase.READY,
            completed = 0, total = 0, histogram = emptyList(), qualityFrames = 0, referenceFrame = 0,
            stats = null, deepSkyStats = null, message = "", exportedFiles = emptyList()) }
        if (file != null) loadFile(file)
    }

    fun loadFile(file: File) = runTask(StackTaskPhase.READING) {
        ensureOfflineAvailable()
        check(!isSourceBusy(file)) { "recording_active" }
        // Re-decoding the same source must not reset the user's ROI fields in the UI.
        val retainedSource = _task.value.source?.takeIf { it.identity == identity(file) }
        analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
        planetaryProduct = null
        sourceFile = null
        synchronized(stateLock) {
            planetaryPreview = null
            if (activeMode == StackMode.PLANETARY) _preview.value = null
        }
        _task.value = StackingTaskState(phase = StackTaskPhase.READING, source = retainedSource)
        sourceFile = file // Retain a chosen file even if Auto cannot understand its CFA declaration.
        source(file).use { source ->
            val info = source.info
            // Keep a validated source selectable when the chosen decoder exceeds the budget
            // or fails to decode. The user can then select a cheaper algorithm and retry.
            sourceFile = file
            _task.value = StackingTaskState(phase = StackTaskPhase.READING, source = info)
            checkBudget(source)
            val first = source.readFrame(0)
            val processed = StackPostProcessor.prepare(first, StackProcessingParameters.preset(ProcessingPreset.NATURAL), backgroundModelCache) { !isActive }
            val bitmap = StackBitmap.create(processed, 1280) { ensureActive() }
            publishPlanetaryPreview(bitmap)
            sourceFile = file
            _task.value = StackingTaskState(phase = StackTaskPhase.READY, source = info)
        }
    }

    fun importFile(uri: Uri) = runTask(StackTaskPhase.READING) {
        ensureOfflineAvailable()
        var displayName = "import.ser"
        var declaredSize = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val dir = File(app.getExternalFilesDir("recordings"), if (displayName.endsWith(".pser", true)) "PSER" else "SER")
        check(dir.isDirectory || dir.mkdirs()) { "storage_unavailable" }
        check(declaredSize < 0 || dir.usableSpace > declaredSize + 16L * 1024 * 1024) { "storage_full" }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(100)
        val target = File(dir, "import-${UUID.randomUUID()}-$safeName")
        val partial = File(dir, target.name + ".partial")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        _task.update { it.copy(completed = copied, total = maxOf(0L, declaredSize)) }
                    }
                }
            } ?: error("source_unreadable")
            // Validate the recording before publishing the import, but defer processing
            // budget checks: a valid file may fit a cheaper demosaic algorithm.
            try {
                source(partial).use { }
            } catch (failure: IllegalStateException) {
                if (_serCfaInterpretation.value != SerCfaInterpretation.AUTO || failure.message != "cfa_unknown_header") throw failure
                // A valid single-sample container with missing CFA metadata can be retained
                // for the user's explicit choice. This inspection never decodes any pixels.
                SERReader(partial, endianConvention, SerCfaInterpretation.MONO).use { reader ->
                    check(reader.open()) { reader.lastError ?: "invalid_source" }
                }
            }
            check(partial.renameTo(target)) { "storage_unavailable" }
            analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
            planetaryProduct = null
            sourceFile = null
            synchronized(stateLock) {
                planetaryPreview = null
                if (activeMode == StackMode.PLANETARY) _preview.value = null
            }
            _task.value = StackingTaskState(phase = StackTaskPhase.READING)
            sourceFile = target
            source(target).use {
                sourceFile = target
                _task.value = StackingTaskState(phase = StackTaskPhase.READING, source = it.info)
                checkBudget(it)
                val first = it.readFrame(0)
                val processed = StackPostProcessor.prepare(first, StackProcessingParameters.preset(ProcessingPreset.NATURAL), backgroundModelCache) { !isActive }
                publishPlanetaryPreview(StackBitmap.create(processed, 1280) { ensureActive() })
                sourceFile = target
                _task.value = StackingTaskState(phase = StackTaskPhase.READY, source = it.info)
            }
        } finally { partial.delete() }
    }

    fun analyze(roi: PixelRoi?, localAlignment: Boolean) = runTask(StackTaskPhase.ANALYZING) {
        ensureOfflineAvailable()
        check(_offlineTarget.value == StackTarget.PLANETARY) { "stack_target_changed" }
        val file = sourceFile ?: error("choose_source")
        check(!isSourceBusy(file)) { "recording_active" }
        analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
        planetaryProduct = null
        _task.update { it.copy(histogram = emptyList(), qualityFrames = 0, referenceFrame = 0, stats = null, deepSkyStats = null) }
        source(file).use { source ->
            checkBudget(source)
            val options = PlanetaryOptions(roi = roi, localAlignment = localAlignment,
                memoryBudgetBytes = memoryBudgetBytes - source.processingOverheadBytes)
            val result = engine.analyze(source, options, cache, control())
            try {
                synchronized(stateLock) {
                    ensureActive()
                    check(_offlineTarget.value == StackTarget.PLANETARY) { "stack_target_changed" }
                    analysis = result
                    analysisIdentity = source.info.decodingIdentity
                    analysisCalibration = source.calibration
                    analysisDemosaic = source.algorithm
                    _task.update { it.copy(phase = StackTaskPhase.ANALYZED, source = source.info,
                        histogram = result.histogram.toList(), qualityFrames = result.validFrames,
                        referenceFrame = result.referenceFrame, message = "", completed = result.frameCount, total = result.frameCount) }
                }
            }
            catch (failure: Throwable) { result.close(); throw failure }
        }
    }

    fun stack(selection: PlanetarySelection) = runTask(StackTaskPhase.REGISTERING) {
        ensureOfflineAvailable()
        check(_offlineTarget.value == StackTarget.PLANETARY) { "stack_target_changed" }
        val file = sourceFile ?: error("choose_source")
        check(!isSourceBusy(file)) { "recording_active" }
        val quality = analysis ?: error("analyze_first")
        // Keep the display bitmap, but release the previous full-resolution product before
        // allocating another stack. Export and rendering were already joined by runTask.
        planetaryProduct = null
        source(file).use { source ->
            check(source.info.decodingIdentity == analysisIdentity) { "source_changed" }
            check(source.calibration === analysisCalibration) { "calibration_changed_analyze_again" }
            check(source.algorithm == analysisDemosaic) { "demosaic_changed_analyze_again" }
            val result = engine.stack(source, quality, selection, control())
            ensureActive()
            check(identity(file) == source.info.identity) { "source_changed" }
            val stats = result.stats.copy(estimatedPeakBytes = result.stats.estimatedPeakBytes + source.processingOverheadBytes)
            val meta = linkedMapOf(
                "kind" to "planetary", "workflow" to "offline", "target" to "planetary",
                "source" to file.name, "sourceIdentity" to source.info.identity,
                "decodingIdentity" to source.info.decodingIdentity,
                "cfaInterpretation" to source.interpretation.name,
                "headerColorId" to source.info.headerColorId.toString(),
                "effectiveSensorLayout" to source.info.effectiveLayout,
                "frameCount" to stats.stackedFrames.toString(), "exposureSeconds" to stats.effectiveExposureSeconds.toString(),
                "unknownExposureFrames" to stats.framesWithUnknownExposure.toString(),
                "inputFormat" to "${source.reader.header?.colorId}/${source.info.bits}bit",
                "byteOrderConvention" to endianConvention.name, "sampleAlignment" to sampleAlignment.name,
                "demosaicAlgorithm" to if (source.info.isBayer) source.algorithm.name else "NONE",
                "demosaicScratchBytes" to source.demosaicScratchBytes.toString(),
                "qualitySelection" to JSONObject().apply { put("percent", selection.percent); put("count", selection.count ?: JSONObject.NULL) }.toString(),
                "registration" to JSONObject().apply {
                    put("referenceFrame", stats.referenceFrame); put("alignmentPoints", stats.alignmentPoints)
                    put("modeCounts", JSONObject(stats.modeCounts.mapKeys { it.key.name }))
                    put("rejections", JSONObject(stats.rejectionCounts.mapKeys { it.key.name }))
                    put("meanCorrelation", stats.meanCorrelation); put("selectedFrames", stats.selectedFrames)
                    put("elapsedMillis", stats.elapsedMillis); put("estimatedPeakBytes", stats.estimatedPeakBytes)
                    put("localRequested", quality.options.localAlignment)
                    put("roi", quality.options.roi?.let { JSONArray(listOf(it.left, it.top, it.width, it.height)) } ?: JSONObject.NULL)
                    put("quality", "noise-corrected smoothed gradient within ROI; global frame ranking")
                }.toString()
            )
            meta.putAll(source.calibrationMetadata())
            synchronized(stateLock) {
                ensureActive()
                check(_offlineTarget.value == StackTarget.PLANETARY) { "stack_target_changed" }
                planetaryProduct = StackProduct(result.image, result.weights, meta)
                _task.update { it.copy(phase = StackTaskPhase.COMPLETE, stats = stats, deepSkyStats = null, message = "") }
                scheduleRender(StackMode.PLANETARY)
            }
        }
    }

    fun stackDeepSky() = runTask(StackTaskPhase.STACKING) {
        ensureOfflineAvailable()
        check(_offlineTarget.value == StackTarget.DEEP_SKY) { "stack_target_changed" }
        val file = sourceFile ?: error("choose_source")
        check(!isSourceBusy(file)) { "recording_active" }
        analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
        planetaryProduct = null
        _task.update { it.copy(histogram = emptyList(), qualityFrames = 0, referenceFrame = 0, stats = null, deepSkyStats = null) }
        source(file).use { source ->
            checkBudget(source)
            val result = OfflineDeepSkyStacker().stack(source,
                DeepSkyConfig(memoryBudgetBytes = memoryBudgetBytes - source.processingOverheadBytes),
                checkCancelled = { ensureActive() },
                progress = { completed, total, stats ->
                    ensureActive()
                    _task.update { it.copy(phase = StackTaskPhase.STACKING, completed = completed, total = total,
                        qualityFrames = stats.accepted.toLong()) }
                    recordMemory()
                })
            ensureActive()
            check(identity(file) == source.info.identity) { "source_changed" }
            val stats = result.stats.copy(stack = result.stats.stack.copy(
                estimatedWorkingBytes = result.stats.stack.estimatedWorkingBytes + source.processingOverheadBytes))
            val meta = linkedMapOf(
                "kind" to "offline_deep_sky", "workflow" to "offline", "target" to "deep_sky",
                "algorithm" to "rigid_star_ransac_online_welford_sigma",
                "source" to file.name, "sourceIdentity" to source.info.identity,
                "decodingIdentity" to source.info.decodingIdentity,
                "cfaInterpretation" to source.interpretation.name,
                "headerColorId" to source.info.headerColorId.toString(),
                "effectiveSensorLayout" to source.info.effectiveLayout,
                "frameCount" to stats.stack.accepted.toString(),
                "exposureSeconds" to stats.stack.exposureSeconds.toString(),
                "unknownExposureFrames" to stats.unknownExposureFrames.toString(),
                "exposureSource" to "validated_capture_sidecar_or_unknown",
                "inputFormat" to "${source.reader.header?.colorId}/${source.info.bits}bit",
                "byteOrderConvention" to endianConvention.name, "sampleAlignment" to sampleAlignment.name,
                "demosaicAlgorithm" to if (source.info.isBayer) source.algorithm.name else "NONE",
                "demosaicScratchBytes" to source.demosaicScratchBytes.toString(),
                "qualitySelection" to "all_frames_passing_star_field_and_registration_checks",
                "registration" to JSONObject().apply {
                    put("referenceFrame", stats.referenceFrame); put("referencePolicy", "first_accepted_star_field")
                    put("inputFrames", stats.inputFrames); put("acceptedFrames", stats.stack.accepted)
                    put("rejectedFrames", stats.stack.rejected); put("rejections", JSONObject(stats.rejectionCounts.mapKeys { it.key.name }))
                    put("registrationRmsPx", stats.stack.registrationRmsPx); put("rotationDegrees", stats.stack.rotationDegrees)
                    put("stars", stats.stack.stars); put("rejectedPixelSamples", stats.stack.rejectedPixelSamples)
                    put("elapsedMillis", stats.elapsedMillis); put("estimatedWorkingBytes", stats.stack.estimatedWorkingBytes)
                }.toString(),
                "rejectionLimit" to DeepSkyStacker.REJECTION_LIMITATION
            )
            meta.putAll(source.calibrationMetadata())
            synchronized(stateLock) {
                ensureActive()
                check(_offlineTarget.value == StackTarget.DEEP_SKY) { "stack_target_changed" }
                planetaryProduct = StackProduct(result.image, result.weights, meta)
                _task.update { it.copy(phase = StackTaskPhase.COMPLETE, deepSkyStats = stats, stats = null,
                    qualityFrames = stats.stack.accepted.toLong(), referenceFrame = stats.referenceFrame, message = "") }
                scheduleRender(StackMode.PLANETARY)
            }
        }
    }

    fun clearPlanetary() = runTask(StackTaskPhase.EMPTY) {
        backgroundModelCache.clear()
        analysis?.close(); analysis = null
        analysisCalibration = null
        analysisDemosaic = null
        planetaryProduct = null; sourceFile = null
        synchronized(stateLock) {
            planetaryPreview = null
            if (activeMode == StackMode.PLANETARY) _preview.value = null
        }
        _task.value = StackingTaskState()
    }

    fun export(): Unit = synchronized(stateLock) {
        val mode = activeMode
        val snapshot = product(mode) ?: run { report("no_result"); return }
        val parameters = _processing.value
        val capturedTarget = target(mode)
        val capturedLiveGeneration = if (mode == StackMode.LIVE) liveGeneration() else -1L
        val adapt = needsAutoStretchLocked(mode, parameters)
        val allRecipes = ProcessingPreset.values().associate { preset -> preset.name to
            StackResultMetadata.parameters(if (preset == parameters.preset) parameters else
                recipes[Triple(mode, target(mode), preset)] ?: StackProcessingParameters.preset(preset)) }
        runTask(StackTaskPhase.EXPORTING) {
            // The immutable snapshot and recipe are captured together before the export starts.
            // Live accumulation may continue; these arrays belong to this completed snapshot.
            // Reserve EXPORTING immediately, including automatic display adaptation. Navigation
            // can no longer cancel a Save request by cancelling its former preview renderer.
            val effective = if (adapt) StackAutoStretch.estimate(snapshot.image, parameters, snapshot.weights) { !isActive }
                ?: parameters else parameters
            ensureActive()
            synchronized(stateLock) {
                ensureActive()
                if (adapt && (mode != StackMode.LIVE || capturedLiveGeneration == liveGeneration())) {
                    if (mode == StackMode.LIVE) liveAutoStretchEligible = false
                    if (pendingAutoStretchMode == mode) pendingAutoStretchMode = null
                    // Preserve edits made after Save. The output still uses the captured recipe;
                    // an unchanged workspace adopts it so its preview agrees with the PNG.
                    if (activeMode == mode && target(mode) == capturedTarget && _processing.value == parameters) {
                        _processing.value = effective
                        rememberRecipeLocked(effective)
                    } else if (recipes[Triple(mode, capturedTarget, parameters.preset)] == parameters) {
                        recipes[Triple(mode, capturedTarget, parameters.preset)] = effective
                    }
                }
            }
            val processed = StackPostProcessor.prepare(snapshot.image, effective, backgroundModelCache) { !isActive }
            val root = app.getExternalFilesDir("captures") ?: error("storage_unavailable")
            val fitsDir = File(root, "FITS").also { check(it.isDirectory || it.mkdirs()) }
            val pngDir = File(root, "JPG").also { check(it.isDirectory || it.mkdirs()) }
            val expectedBytes = snapshot.image.pixels.size.toLong() * 12L + 16L * 1024 * 1024
            check(root.usableSpace >= expectedBytes) { "storage_full" }
            val stem = "stack-${snapshot.metadata["workflow"] ?: mode.name.lowercase()}-${snapshot.metadata["target"] ?: "planetary"}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-${effective.preset.name.lowercase()}"
            val fits = File(fitsDir, "$stem-linear.fits")
            val png = File(pngDir, "$stem.png")
            val recipe = File(pngDir, "$stem.json")
            val temporaryPng = File(pngDir, "$stem.png.partial")
            val metadata = snapshot.metadata + mapOf(
                "schema" to "indigo-stack-1", "linearFits" to fits.name, "processedPng" to png.name,
                "processing" to StackResultMetadata.processing(processed).toString(),
                "comparisonRecipes" to JSONObject(allRecipes + (effective.preset.name to StackResultMetadata.parameters(effective))).toString(),
                "width" to snapshot.image.width.toString(), "height" to snapshot.image.height.toString(),
                "channels" to snapshot.image.channels.toString(), "pixelScale" to "normalized_linear_0_to_1",
                "weightMin" to (snapshot.weights.minOrNull() ?: 0f).toString(),
                "weightMax" to (snapshot.weights.maxOrNull() ?: 0f).toString()
            )
            val created = mutableListOf<File>()
            try {
                StackFitsWriter.write(fits, snapshot.image, metadata) { ensureActive() }
                created += fits
                _task.update { it.copy(completed = 1, total = 3) }
                val bitmap = StackBitmap.create(processed) { ensureActive() }
                try {
                    temporaryPng.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "png_failed" } }
                } finally { bitmap.recycle() }
                ensureActive()
                check(temporaryPng.renameTo(png)) { "storage_unavailable" }; created += png
                _task.update { it.copy(completed = 2, total = 3) }
                recipe.writeText(JSONObject(metadata).toString(2), Charsets.UTF_8); created += recipe
                ensureActive()
                _task.update { it.copy(phase = StackTaskPhase.COMPLETE, completed = 3, total = 3,
                    exportedFiles = created.map(File::getAbsolutePath), message = "export_success") }
            } catch (error: Throwable) {
                created.forEach { it.delete() }; recipe.delete(); throw error
            } finally { temporaryPng.delete() }
        }
    }

    fun cancel(reason: String = "cancelled") = synchronized(stateLock) {
        taskJob?.cancel(CancellationException(reason))
        cancelRenderingLocked()
    }

    fun cancelRendering() = synchronized(stateLock) {
        userChangedRecipeLocked()
        cancelRenderingLocked()
    }

    fun onBackground() = synchronized(stateLock) {
        foreground = false
        live.pause("background_paused")
        cancel("background_cancelled")
        calibration.cancel()
    }

    fun onForeground() = synchronized(stateLock) {
        foreground = true
        if (!closed) scheduleRender(activeMode)
    }

    fun report(reason: String) {
        if (reason.isNotBlank() && _task.value.message != reason) FileLogger.i("Stacking", "Message mode=$activeMode: $reason")
        _task.update { it.copy(message = reason) }
    }

    private fun publishPlanetaryPreview(bitmap: Bitmap) = synchronized(stateLock) {
        if (closed) bitmap.recycle() else {
            planetaryPreview = bitmap
            if (activeMode == StackMode.PLANETARY) _preview.value = bitmap
        }
    }

    private fun cancelRenderingLocked() {
        renderSerial++
        pendingRenderMode = null
        renderJob?.cancel()
    }

    private fun scheduleRender(mode: StackMode, replaceRunning: Boolean = true) = synchronized(stateLock) {
        if (closed || !foreground || activeMode != mode || product(mode) == null) return
        pendingRenderMode = mode
        if (replaceRunning) {
            renderSerial++
            renderJob?.cancel()
        }
        startPendingRenderLocked()
    }

    private fun needsAutoStretchLocked(mode: StackMode, parameters: StackProcessingParameters): Boolean =
        pendingAutoStretchMode == mode ||
            (mode == StackMode.LIVE && liveAutoStretchEligible && parameters.preset != ProcessingPreset.RAW)

    /** At most one running render and one conflated mode token, never a queue of image copies.
     * Live snapshots do not cancel a slow render; the next render picks the latest product.
     * Recipe/mode/reset changes cancel it and cannot publish the old recipe afterward.
     */
    private fun startPendingRenderLocked() {
        if (closed || !foreground || taskRunning || renderJob != null) return
        val mode = pendingRenderMode ?: return
        pendingRenderMode = null
        if (mode != activeMode) return
        val snapshot = product(mode) ?: return
        val p = _processing.value
        val adapt = needsAutoStretchLocked(mode, p)
        val serial = renderSerial
        val liveEpoch = if (mode == StackMode.LIVE) liveGeneration() else -1L
        _rendering.value = true
        val job = scope.launch(renderer, start = CoroutineStart.LAZY) {
            try {
                delay(120)
                val estimated = if (adapt) StackAutoStretch.estimate(snapshot.image, p, snapshot.weights) {
                    !isActive || serial != renderSerial
                } else null
                ensureActive()
                val effective = synchronized(stateLock) {
                    if (closed || !foreground || serial != renderSerial || activeMode != mode ||
                        (mode == StackMode.LIVE && liveEpoch != liveGeneration())) throw CancellationException("Obsolete display recipe")
                    if (adapt) {
                        if (mode == StackMode.LIVE) liveAutoStretchEligible = false
                        if (pendingAutoStretchMode == mode) pendingAutoStretchMode = null
                        if (estimated != null) {
                            rememberRecipeLocked(p)
                            _processing.value = estimated
                            rememberRecipeLocked(estimated)
                            report("")
                        } else report("auto_stretch_unavailable")
                    }
                    estimated ?: p
                }
                val result = StackPostProcessor.prepare(snapshot.image, effective, backgroundModelCache) { !isActive || serial != renderSerial }
                ensureActive()
                val bitmap = StackBitmap.create(result, 1280) { ensureActive() }
                synchronized(stateLock) {
                    if (!closed && foreground && serial == renderSerial && activeMode == mode &&
                        (mode != StackMode.LIVE || liveEpoch == liveGeneration())) {
                        _preview.value = bitmap
                        if (mode == StackMode.LIVE) _livePreview.value = bitmap else planetaryPreview = bitmap
                        _backgroundWarning.value = result.background?.takeIf { !it.applied }?.reason.orEmpty()
                    } else bitmap.recycle()
                }
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                report(error.message ?: "processing_failed")
            } catch (_: OutOfMemoryError) {
                report("memory_budget")
            }
        }
        renderJob = job
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (renderJob === job) {
                    renderJob = null
                    _rendering.value = false
                    startPendingRenderLocked()
                }
            }
        }
        job.start()
    }

    private fun runTask(phase: StackTaskPhase, action: suspend CoroutineScope.() -> Unit): Unit = synchronized(stateLock) {
        if (closed) return
        if (calibration.state.value.busy && phase != StackTaskPhase.EMPTY) { report("calibration_busy"); return }
        if (phase !in setOf(StackTaskPhase.EXPORTING, StackTaskPhase.EMPTY) && live.state.value.phase != LiveStackPhase.EMPTY) {
            report("reset_live_first")
            return
        }
        val serial = ++taskSerial
        // Requests for an old offline image must not be applied to a replacement source/stack.
        if (phase != StackTaskPhase.EXPORTING && pendingAutoStretchMode == StackMode.PLANETARY) pendingAutoStretchMode = null
        val previous = taskJob
        previous?.cancel()
        taskRunning = true // Reserve synchronously, before another button can start live capture.
        val oldRender = renderJob
        cancelRenderingLocked()
        _task.update { it.copy(phase = phase, completed = 0, total = 0, message = "", exportedFiles = emptyList()) }
        fun failed(next: StackTaskPhase, message: String) = synchronized(stateLock) {
            if (serial == taskSerial) _task.update { it.copy(phase = next, message = message) }
        }
        val job = scienceScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                oldRender?.join()
                ensureActive()
                synchronized(stateLock) {
                    if (serial == taskSerial) _task.update { it.copy(phase = phase, completed = 0, total = 0, message = "") }
                }
                action()
            }
            catch (error: CancellationException) { failed(StackTaskPhase.CANCELLED, error.message ?: "cancelled") }
            catch (error: Exception) {
                FileLogger.e("Stacking", "Task failed phase=$phase source=${_task.value.source?.width}x${_task.value.source?.height}", error)
                failed(StackTaskPhase.FAILED, error.message ?: "processing_failed")
            }
            catch (error: OutOfMemoryError) {
                FileLogger.e("Stacking", "Task exhausted memory phase=$phase budget=$memoryBudgetBytes", error)
                failed(StackTaskPhase.FAILED, "memory_budget")
            }
            finally { recordMemory() }
        }
        taskJob = job
        job.invokeOnCompletion { cause -> synchronized(stateLock) {
            if (serial == taskSerial) {
                taskRunning = false
                if (cause is CancellationException) failed(StackTaskPhase.CANCELLED, cause.message ?: "cancelled")
                if (!closed && foreground) scheduleRender(activeMode, replaceRunning = false)
            }
        } }
        job.start()
    }

    private fun ensureOfflineAvailable() {
        check(live.state.value.phase == LiveStackPhase.EMPTY) { "reset_live_first" }
    }

    private fun checkBudget(source: SerSource) {
        val engineBytes = if (_offlineTarget.value == StackTarget.DEEP_SKY)
            DeepSkyStacker.estimateWorkingBytes(source.width, source.height, source.channels)
        else PlanetaryStacker.estimatedPeakBytes(source.width, source.height, source.channels)
        require(source.width.toLong() * source.height <= PlanetaryStacker.MAX_PIXELS &&
            engineBytes + source.processingOverheadBytes <= memoryBudgetBytes) { "memory_budget" }
    }

    private fun CoroutineScope.control() = PlanetaryControl(checkCancelled = { ensureActive() }, progress = { progress ->
        ensureActive()
        _task.update { it.copy(phase = when (progress.phase) {
            PlanetaryPhase.READ -> if (it.phase == StackTaskPhase.ANALYZING || it.phase == StackTaskPhase.READING) StackTaskPhase.ANALYZING else it.phase
            PlanetaryPhase.ANALYZE -> StackTaskPhase.ANALYZING
            PlanetaryPhase.REGISTER -> StackTaskPhase.REGISTERING
            PlanetaryPhase.STACK -> StackTaskPhase.STACKING
            PlanetaryPhase.COMPLETE -> StackTaskPhase.COMPLETE
        }, completed = progress.completed, total = progress.total) }
        recordMemory()
    })

    private fun recordMemory() {
        val java = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val native = Debug.getNativeHeapAllocatedSize()
        _task.update { it.copy(javaPeakBytes = maxOf(java, it.javaPeakBytes), nativePeakBytes = maxOf(native, it.nativePeakBytes)) }
    }

    private suspend fun CoroutineScope.source(file: File): SerSource =
        SerSource(file, endianConvention, sampleAlignment, calibration.snapshot(), _demosaicAlgorithm.value,
            _serCfaInterpretation.value) { ensureActive() }

    private class SerSource(file: File, endian: SerEndianConvention, alignment: SampleAlignment,
        val calibration: CalibrationSelection, val algorithm: DemosaicAlgorithm,
        val interpretation: SerCfaInterpretation,
        private val cancelCheck: () -> Unit) : PlanetaryFrameSource {
        val reader = SERReader(file, endian, interpretation).apply { sampleAlignment = alignment }
        private val demosaicWorkspace = DdfapdDemosaicer.Workspace()
        val info: StackSourceInfo
        private val capture: CalibrationCapture
        private val geometry: CalibrationGeometry
        val calibrationOverheadBytes: Long
        val demosaicScratchBytes: Long
        val processingOverheadBytes: Long get() = calibrationOverheadBytes + demosaicScratchBytes
        init {
            try {
                check(reader.open()) { reader.lastError ?: "invalid_source" }
                val header = reader.header!!
                geometry = CalibrationGeometry.from(header.width, header.height, reader.sensorFormat, reader.sensorTraits)
                val fileIdentity = identity(file)
                info = StackSourceInfo(file, header.width, header.height,
                    if (reader.sensorFormat.isMono) 1 else 3, header.pixelDepth, header.frameCount.toLong(), fileIdentity,
                    reader.sensorFormat.isBayer, header.colorId, geometry.layout,
                    interpretation.decodingIdentity(fileIdentity, endian.name, alignment.name, algorithm.name),
                    header.colorId in 0..99)
                capture = if (calibration.enabled) CalibrationCoordinator.recordingCapture(file)
                    else runCatching { CalibrationCoordinator.recordingCapture(file) }.getOrDefault(CalibrationCapture())
                calibration.validate(geometry, capture)
                calibrationOverheadBytes = calibration.memoryBytes + if (calibration.enabled) geometry.sampleCount * 4L else 0L
                demosaicScratchBytes = LinearPixelDecoder.demosaicExtraBytes(header.width, header.height, reader.sensorFormat, algorithm)
            } catch (failure: Throwable) { reader.close(); throw failure }
        }
        override val width get() = info.width
        override val height get() = info.height
        override val channels get() = info.channels
        override val frameCount get() = info.frames
        override fun readFrame(index: Long): LinearImage {
            require(index in 0 until frameCount && index <= Int.MAX_VALUE)
            cancelCheck()
            val sensor = reader.readSensorFrame(index.toInt(), maxPixels = LinearImage.MAX_PIXELS, cancelCheck = cancelCheck)
                ?: error(reader.lastError ?: "read_failed")
            return LinearPixelDecoder.demosaicSensor(calibration.apply(sensor, geometry, capture, cancelCheck),
                reader.sensorFormat, reader.sensorTraits, algorithm, demosaicWorkspace, cancelCheck)
        }
        override fun exposureSeconds(index: Long): Double = capture.exposureUs?.div(1_000_000.0) ?: 0.0
        fun calibrationMetadata() = calibration.metadata() + mapOf(
            "calibrationWorkingBytes" to calibrationOverheadBytes.toString(),
            "calibrationUnknownSettings" to JSONArray(calibration.unknownWarnings(geometry, capture)).toString()
        )
        override fun close() { try { reader.close() } finally { demosaicWorkspace.release() } }
    }

    private fun liveMetadata(snapshot: LiveStackSnapshot): Map<String, String> {
        val s = snapshot.state
        return snapshot.details.mapValues { (_, value) -> when (value) {
            is Map<*, *> -> JSONObject(value).toString()
            is Collection<*> -> JSONArray(value).toString()
            else -> value.toString()
        } } + liveCalibration.metadata() + mapOf(
            "calibrationMemoryBytes" to liveCalibration.memoryBytes.toString(),
            "calibrationUnknownSettings" to JSONArray(liveCalibrationWarnings).toString(),
            "demosaicAlgorithm" to (s.key?.takeIf { it.format.isBayer }?.demosaicAlgorithm?.name ?: "NONE"),
            "demosaicScratchBytes" to liveDemosaicScratchBytes.toString(),
            "kind" to if (s.key?.target == StackTarget.PLANETARY) "live_planetary" else "live_deep_sky",
            "workflow" to "live", "target" to if (s.key?.target == StackTarget.PLANETARY) "planetary" else "deep_sky",
            "source" to s.key?.camera.orEmpty(), "captureSettings" to s.key.toString(),
            "frameCount" to s.accepted.toString(), "exposureSeconds" to s.exposureSeconds.toString(),
            "received" to s.received.toString(), "rejected" to s.rejected.toString(), "queueDropped" to s.dropped.toString(),
            "registrationRmsPx" to if (s.key?.target == StackTarget.PLANETARY) "not_measured" else s.rmsPixels.toString(),
            "rotationDegrees" to if (s.key?.target == StackTarget.PLANETARY) "not_measured" else s.rotationDegrees.toString(),
            (if (s.key?.target == StackTarget.PLANETARY) "alignmentPoints" else "stars") to s.stars.toString(),
            "exposureSource" to "verified_acquisition_generation",
            "javaPeakBytes" to s.javaPeakBytes.toString(), "nativePeakBytes" to s.nativePeakBytes.toString()
        )
    }

    override fun close() {
        val jobs = synchronized(stateLock) {
            if (closed) return
            closed = true
            foreground = false
            cancelRenderingLocked()
            taskJob?.cancel()
            backgroundModelCache.clear()
            calibration.close()
            live.close()
            liveProduct = null; planetaryProduct = null; planetaryPreview = null
            _preview.value = null; _livePreview.value = null
            taskJob to renderJob
        }
        // Defer index cleanup until the running single worker has observed cancellation.
        CoroutineScope(worker + SupervisorJob()).launch {
            jobs.first?.join(); jobs.second?.join(); live.awaitClosed(); calibration.awaitClosed()
            analysis?.close(); analysis = null; analysisCalibration = null; analysisDemosaic = null
            liveCalibration = CalibrationSelection()
            worker.close(); renderer.close()
        }
    }

    companion object {
        private fun identity(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                var remaining = 1024 * 1024
                while (remaining > 0) {
                    val read = input.read(block, 0, minOf(block.size, remaining))
                    if (read < 0) break
                    digest.update(block, 0, read); remaining -= read
                }
            }
            return "${file.canonicalPath}|${file.length()}|${file.lastModified()}|prefix-sha256:${digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }}"
        }
    }
}
