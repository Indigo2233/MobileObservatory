package com.indigo.mobileobservatory.ui.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaUsageRegistry
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import com.indigo.mobileobservatory.sequence.SequenceEphemeris
import com.indigo.mobileobservatory.solar.SolarOutputProduct
import com.indigo.mobileobservatory.solar.SolarBandingAxis
import com.indigo.mobileobservatory.solar.SolarCorrectionReport
import com.indigo.mobileobservatory.solar.SolarMemorySampleSummary
import com.indigo.mobileobservatory.solar.SolarReconstructionSettings
import com.indigo.mobileobservatory.solar.SolarScanConfiguration
import com.indigo.mobileobservatory.solar.SolarScanDirection
import com.indigo.mobileobservatory.solar.SolarScanLegRecord
import com.indigo.mobileobservatory.solar.SolarScanPhase
import com.indigo.mobileobservatory.solar.SolarScanPreflight
import com.indigo.mobileobservatory.solar.SolarScanPreflightEvaluator
import com.indigo.mobileobservatory.solar.SolarScanRepository
import com.indigo.mobileobservatory.solar.SolarScanSimulator
import com.indigo.mobileobservatory.solar.SolarScanTaskRecord
import com.indigo.mobileobservatory.solar.SolarScanNative
import com.indigo.mobileobservatory.solar.SolarSourceDispositionState
import com.indigo.mobileobservatory.solar.SolarSourceIdentity
import com.indigo.mobileobservatory.solar.SolarSpectrumAnalysis
import com.indigo.mobileobservatory.solar.SolarSourcePolicy
import com.indigo.mobileobservatory.solar.SolarReconstructionSummary
import com.indigo.mobileobservatory.solar.SolarNativeObserver
import com.indigo.mobileobservatory.solar.NativeSolarAnalysis
import com.indigo.mobileobservatory.solar.NativeSolarPreview
import com.indigo.mobileobservatory.solar.legPlans
import com.indigo.mobileobservatory.solar.SolarRoi
import com.indigo.mobileobservatory.solar.SolarPoint
import com.indigo.mobileobservatory.solar.SOLAR_REPEAT_SWEEP_COUNTS
import com.indigo.mobileobservatory.solar.reverseSolar
import com.indigo.mobileobservatory.util.FileLogger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SolarScanUiState(
    val files: List<VideoFileInfo> = emptyList(),
    val selectedFile: File? = null,
    val preview: Bitmap? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val analysis: SolarSpectrumAnalysis? = null,
    val configuration: SolarScanConfiguration,
    val reconstructionSettings: SolarReconstructionSettings = SolarReconstructionSettings(),
    val preflight: SolarScanPreflight,
    val runs: List<SolarScanTaskRecord> = emptyList(),
    val busy: Boolean = false,
    val progress: Float = 0f,
    val phase: SolarScanPhase = SolarScanPhase.IDLE,
    val currentLeg: Int = 0,
    val totalLegs: Int = 0,
    val currentDirection: SolarScanDirection? = null,
    val completedLegs: Int = 0,
    val hardwareActive: Boolean = false,
    val trialMoving: Boolean = false,
    val message: String? = null,
    val lastResultDirectory: File? = null
)

internal fun incompleteSimulationSourcesToDelete(
    createdSources: Collection<File>,
    sealedSources: Collection<File>
): List<File> = createdSources.filter { it.exists() && it !in sealedSources }

internal fun publishSealedSimulationSource(
    partial: File,
    destination: File,
    readIdentity: (File, String) -> SolarSourceIdentity = { file, name -> SolarSourceIdentity.of(file, name) }
): SolarSourceIdentity {
    require(partial.isFile) { "Simulation source was not finalized." }
    require(!destination.exists()) { "Simulation source already exists." }
    check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
    val identity = try {
        readIdentity(partial, destination.name)
    } catch (failure: Exception) {
        try {
            moveSealedSimulationSource(partial, destination)
        } catch (publishFailure: Exception) {
            failure.addSuppressed(publishFailure)
        }
        throw failure
    }
    moveSealedSimulationSource(partial, destination)
    return identity
}

private fun moveSealedSimulationSource(partial: File, destination: File) {
    try {
        Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial.toPath(), destination.toPath())
    }
}

internal fun isCurrentSolarPreview(
    selected: File?,
    requested: File,
    jobActive: Boolean,
    generation: Long,
    currentGeneration: Long
): Boolean = jobActive &&
    generation == currentGeneration &&
    selected?.absoluteFile == requested.absoluteFile

internal class SolarReconstructionSettingsSession(
    initial: SolarReconstructionSettings,
    private val persist: (SolarReconstructionSettings) -> Unit
) {
    private var draft = initial

    @Synchronized
    fun edit(transform: (SolarReconstructionSettings) -> SolarReconstructionSettings):
        SolarReconstructionSettings {
        draft = transform(draft)
        return draft
    }

    @Synchronized
    fun markUsed(settings: SolarReconstructionSettings): SolarReconstructionSettings {
        draft = settings
        persist(settings)
        return draft
    }
}

class SolarScanViewModel(application: Application) : AndroidViewModel(application) {
    private val recordingsDir = application.getExternalFilesDir("recordings")?.apply { mkdirs() }
    private val capturesDir = application.getExternalFilesDir("captures")?.apply { mkdirs() }
    private val preferences = application.getSharedPreferences("solar_scan", Application.MODE_PRIVATE)
    private val repository = if (recordingsDir != null && capturesDir != null &&
        recordingsDir.isDirectory && capturesDir.isDirectory
    ) SolarScanRepository(
        recordingsDir,
        capturesDir,
        recoveryPermits = File(application.filesDir, "solar-disposition")
    ) else null
    private val initialConfiguration = loadConfiguration() ?: defaultConfiguration()
    private val initialReconstruction = loadReconstructionSettings()
    private val reconstructionSettingsSession = SolarReconstructionSettingsSession(
        initialReconstruction,
        ::saveReconstructionSettings
    )
    private val preparedConfiguration = initialConfiguration.copy(
        products = initialReconstruction.products
    )
    private val _state = MutableStateFlow(
        SolarScanUiState(
            configuration = preparedConfiguration,
            reconstructionSettings = initialReconstruction,
            preflight = preflight(preparedConfiguration),
            message = if (repository == null) application.getString(
                R.string.solar_scan_message_storage_unavailable
            ) else null
        )
    )
    val state: StateFlow<SolarScanUiState> = _state.asStateFlow()
    private var operation: Job? = null
    @Volatile private var recoveryTaskId: String? = null
    private val simulationStopRequested = AtomicBoolean()
    private val sourceDispositionCommit = Any()
    private var previewOperation: Job? = null
    private val previewGeneration = AtomicLong()
    private var trialFinish: CompletableDeferred<Unit>? = null
    private var calibratedSetup: SolarCaptureSetup? = null
    private val _trials = MutableStateFlow<List<SolarCapturedSweep>>(emptyList())
    internal val trials = _trials.asStateFlow()

    internal fun startTrial(camera: CameraViewModel, direction: MountDirection) {
        if (_state.value.busy || operation?.isCompleted == false || repository == null) return
        val finish = CompletableDeferred<Unit>()
        trialFinish = finish
        launchHardware(1) {
            _state.update { it.copy(currentDirection = direction.scanDirection()) }
            val key = camera.solarCaptureKey()
            _trials.update { trials -> trials.filter { it.motion.direction != direction } }
            camera.withSolarMount { mount ->
                val setup = SolarCaptureSetup(key, mount.revision, mount.rate, mount.pier)
                if (calibratedSetup != setup) _trials.value = emptyList()
                calibratedSetup = setup
                val result = SolarHardwareCapture(camera, setup, mount, ::inspectSweepSpectrum).capture(
                    direction, null, finish,
                    onMoving = { _state.update { it.copy(trialMoving = true) } },
                    onSettling = { _state.update { it.copy(trialMoving = false, phase = SolarScanPhase.SETTLING) } },
                    onChecking = { _state.update { it.copy(phase = SolarScanPhase.ANALYZING) } }
                )
                currentCoroutineContext().ensureActive()
                _trials.update { it + result }
                _state.update { it.copy(completedLegs = 1) }
            }
            getApplication<Application>().getString(R.string.solar_hardware_trial_saved)
        }
    }

    internal fun finishTrial() {
        if (_state.value.trialMoving) {
            trialFinish?.complete(Unit)
            _state.update { it.copy(trialMoving = false) }
        }
    }

    internal fun startReciprocating(camera: CameraViewModel, count: Int) {
        val setup = calibratedSetup ?: return
        val pair = _trials.value
        if (count !in SOLAR_REPEAT_SWEEP_COUNTS || pair.size != 2 || !pair[0].motion.pairsWith(pair[1].motion)) return
        val repository = repository ?: return
        val settings = _state.value.reconstructionSettings
        val baseConfiguration = _state.value.configuration
        launchHardware(count) { observer ->
            val key = camera.solarCaptureKey()
            val captures = mutableListOf<SolarCapturedSweep>()
            val createdAt = Instant.now()
            camera.withSolarMount { mount ->
                check(setup == SolarCaptureSetup(key, mount.revision, mount.rate, mount.pier)) {
                    "The capture or mount configuration changed. Repeat both trials."
                }
                val position = SolarPoint.from(mount.read(), Instant.now())
                var next = checkNotNull(pair.find { it.motion.matchesStart(position) }) {
                    "Manually return to either calibrated starting position."
                }.motion.direction
                val capture = SolarHardwareCapture(camera, setup, mount, ::inspectSweepSpectrum)
                val reserve = pair.maxOf { it.file.length() }
                check(reserve > 0 && pair.all { it.file.isFile }) {
                    "Both calibration SER recordings must still be available."
                }
                check(checkNotNull(recordingsDir).usableSpace / reserve / 2 >= count) {
                    "Insufficient space for the requested SER recordings."
                }
                repeat(count) { index ->
                    val trial = pair.first { it.motion.direction == next }
                    _state.update {
                        it.copy(phase = SolarScanPhase.RECORDING, currentLeg = index + 1,
                            currentDirection = next.scanDirection())
                    }
                    captures += capture.capture(next, trial.motion, null,
                        onMoving = {},
                        onSettling = { _state.update { it.copy(phase = SolarScanPhase.SETTLING) } },
                        onChecking = { _state.update { it.copy(phase = SolarScanPhase.ANALYZING) } }
                    )
                    _state.update { it.copy(completedLegs = captures.size) }
                    next = next.reverseSolar()
                }
            }
            val taskId = repository.newTaskId()
            val staging = repository.createStaging(taskId)
            try {
                _state.update { it.copy(phase = SolarScanPhase.RECONSTRUCTING) }
                reconstructionSettingsSession.markUsed(settings)
                val legs = MediaUsageRegistry.acquire(captures.map { it.file }, waitForExclusive = false).use {
                    captures.mapIndexed { index, sweep ->
                        val direction = sweep.motion.direction.scanDirection()
                        val legSettings = settings.copy(flipVertical = settings.flipVertical xor direction.flipVertical)
                        val output = File(staging, "products/leg-${index + 1}").apply { mkdirs() }
                        val identity = SolarSourceIdentity.of(sweep.file)
                        val memoryTrace = ReconstructionMemoryTrace(::readSystemMemoryHeadroom)
                        val native = SolarScanNative.reconstruct(sweep.file, output, legSettings,
                            memoryTrace.nativeHeadroomBytes(), useChineseWarnings(), object : SolarNativeObserver by observer {
                                override fun onProgress(value: Float) {
                                    memoryTrace.sampleFromProgress()
                                    _state.update { it.copy(progress = (index + value) / count) }
                                }
                            })
                        validateProducts(output, settings.products)
                        SolarScanLegRecord(index + 1, direction, identity, sweep.coverage.frames, 0,
                            native.toDomain(sweep.file, legSettings, memoryTrace.summary()),
                            SolarSourceDispositionState.KEPT, analysis = sweep.analysis, reconstructionSettings = legSettings)
                    }
                }
                File(staging, "coverage.txt").writeText(captures.joinToString("\n") { sweep ->
                    val motion = sweep.motion
                    val coverage = sweep.coverage
                    "${sweep.file.name} ${motion.direction} ${motion.motionMs} ms " +
                        "start=${motion.start} end=${motion.end} " +
                        "diskFrames=[${coverage.firstDiskFrame},${coverage.endDiskFrame}) " +
                        "diskCrossingMs=${sweep.timing.diskCrossingMs} fps=${sweep.timing.framesPerSecond} " +
                        "left=${coverage.leftMargin} px right=${coverage.rightMargin} px"
                }, Charsets.UTF_8)
                val eastToWest = captures.first { it.motion.direction == MountDirection.WEST }
                val config = baseConfiguration.copy(
                    endpointA = eastToWest.motion.start.endpoint(eastToWest.startedAt),
                    endpointB = eastToWest.motion.end.endpoint(eastToWest.startedAt.plusMillis(eastToWest.motion.motionMs)),
                    scanDurationMs = eastToWest.motion.motionMs,
                    diskCrossingMs = eastToWest.timing.diskCrossingMs,
                    frameRate = eastToWest.timing.framesPerSecond,
                    exposureUs = key.exposureUs.roundToInt().toLong().coerceAtLeast(1),
                    roi = SolarRoi(key.roi.x, key.roi.y, key.width, key.height),
                    pixelBytes = key.format.bytesPerPixel,
                    legCount = count,
                    continuous = false,
                    sourcePolicy = SolarSourcePolicy.KEEP,
                    products = settings.products
                )
                val app = getApplication<Application>()
                if (observer.isCancelled()) throw CancellationException("Solar scan cancelled.")
                _state.update { it.copy(phase = SolarScanPhase.COMMITTING) }
                val record = repository.commit(staging, taskId, SolarScanTaskRecord(
                    taskId, createdAt.toString(), Instant.now().toString(),
                    app.getString(R.string.solar_hardware_auto), config, legs.first().analysis, settings,
                    legs, SolarSourcePolicy.KEEP, legs.flatMap { it.reconstruction?.warnings.orEmpty() },
                    staging
                ))
                _state.update { it.copy(lastResultDirectory = record.resultDirectory) }
            } finally {
                staging.deleteRecursively()
            }
            getApplication<Application>().getString(R.string.solar_scan_message_saved)
        }
    }

    private fun MountDirection.scanDirection(): SolarScanDirection =
        if (this == MountDirection.WEST) SolarScanDirection.A_TO_B else SolarScanDirection.B_TO_A

    private fun inspectSweepSpectrum(file: File, observer: SolarNativeObserver): SolarSpectrumAnalysis =
        SolarScanNative.analyze(file, observer).toDomain().also {
            check(it.confidence > 0.0) { "Could not confirm the spectral line in the SER recording." }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchHardware(count: Int, run: suspend (SolarNativeObserver) -> String) {
        if (_state.value.busy || operation?.isCompleted == false || repository == null) return
        recoveryTaskId = null
        _state.update { it.copy(busy = true, hardwareActive = true, message = null, progress = 0f,
            phase = SolarScanPhase.RECORDING, currentLeg = 1, totalLegs = count, completedLegs = 0,
            currentDirection = null, lastResultDirectory = null) }
        operation = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val job = checkNotNull(coroutineContext[Job])
            val app = getApplication<Application>()
            try {
                startupMaintenance.join()
                val message = run(operationObserver(job) { })
                _state.update { it.copy(phase = SolarScanPhase.COMPLETED, message = message, progress = 1f) }
            } catch (_: CancellationException) {
                _state.update { it.copy(phase = SolarScanPhase.CANCELLED,
                    message = app.getString(R.string.solar_hardware_cancelled)) }
            } catch (error: Exception) {
                FileLogger.e("SolarScan", "Hardware scan failed", error)
                _state.update { it.copy(phase = if (job.isActive) SolarScanPhase.FAILED else SolarScanPhase.CANCELLED,
                    message = app.getString(R.string.solar_hardware_failed, error.message.orEmpty())) }
            } finally {
                try {
                    runCatching { refreshInternal() }
                } finally {
                    trialFinish = null
                    _state.update { it.copy(busy = false, hardwareActive = false, trialMoving = false) }
                }
            }
        }
    }

    private val startupMaintenance = viewModelScope.launch(Dispatchers.IO) {
        val repository = repository ?: return@launch
        runCatching {
            repository.cleanupIncompleteSources()
            repository.recoverStaging()
            repository.cleanOrphanedRecoveryPermits(
                storageMounted = {
                    Environment.getExternalStorageState(checkNotNull(capturesDir)) == Environment.MEDIA_MOUNTED
                },
                activeTaskId = { recoveryTaskId }
            )
            repository.reconcilePendingDispositions()
            refreshInternal()
        }.onFailure { error ->
            FileLogger.e("SolarScan", "Startup maintenance failed", error)
            _state.update {
                it.copy(
                    message = getApplication<Application>().getString(
                        R.string.solar_scan_message_maintenance_failed,
                        error.message.orEmpty()
                    )
                )
            }
        }
    }

    fun refresh() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) { refreshInternal() }
    }

    fun selectSource(file: File) {
        if (_state.value.busy) return
        val requested = file.absoluteFile
        val generation = previewGeneration.incrementAndGet()
        previewOperation?.cancel()
        _state.update {
            it.copy(
                selectedFile = requested,
                preview = null,
                sourceWidth = 0,
                sourceHeight = 0,
                analysis = null,
                message = null
            )
        }
        previewOperation = viewModelScope.launch(Dispatchers.IO) {
            val previewJob = checkNotNull(coroutineContext[Job])
            try {
                val native = SolarScanNative.preview(
                    requested,
                    operationObserver(previewJob) { Unit }
                )
                val preview = previewBitmap(native)
                _state.update { current ->
                    if (isCurrentSolarPreview(
                            current.selectedFile,
                            requested,
                            previewJob.isActive,
                            generation,
                            previewGeneration.get()
                        )
                    ) {
                        current.copy(
                            preview = preview,
                            sourceWidth = native.width,
                            sourceHeight = native.height,
                            message = null
                        )
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                // A newer selection owns the preview state.
            } catch (error: Throwable) {
                _state.update { current ->
                    if (isCurrentSolarPreview(
                            current.selectedFile,
                            requested,
                            previewJob.isActive,
                            generation,
                            previewGeneration.get()
                        )
                    ) {
                        current.copy(
                            message = getApplication<Application>().getString(
                                R.string.solar_scan_message_source_open_failed,
                                error.message.orEmpty()
                            )
                        )
                    } else {
                        current
                    }
                }
            } finally {
                if (previewOperation === previewJob) previewOperation = null
            }
        }
    }

    fun analyzeSelected() {
        val file = _state.value.selectedFile ?: return
        if (_state.value.busy || operation?.isCompleted == false) return
        recoveryTaskId = null
        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                phase = SolarScanPhase.ANALYZING,
                currentLeg = 0,
                totalLegs = 0,
                currentDirection = null,
                message = null
            )
        }
        operation = viewModelScope.launch(Dispatchers.IO) {
            val operationJob = checkNotNull(coroutineContext[Job])
            try {
                val analysis = SolarScanNative.analyze(
                    file,
                    operationObserver(operationJob) { value ->
                        _state.update { it.copy(progress = value) }
                    }
                ).toDomain()
                _state.update {
                    it.copy(
                        analysis = analysis,
                        busy = false,
                        progress = 1f,
                        phase = SolarScanPhase.IDLE,
                        message = null
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = SolarScanPhase.CANCELLED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_analysis_cancelled
                        )
                    )
                }
            } catch (error: Throwable) {
                if (!operationJob.isActive) {
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = 0f,
                            phase = SolarScanPhase.CANCELLED,
                            message = getApplication<Application>().getString(
                                R.string.solar_scan_message_analysis_cancelled
                            )
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = SolarScanPhase.FAILED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_analysis_failed,
                            error.message.orEmpty()
                        )
                    )
                }
            }
        }
    }

    fun reconstructSelected() {
        val current = _state.value
        val file = current.selectedFile ?: return
        val analysis = current.analysis ?: run {
            _state.update {
                it.copy(
                    message = getApplication<Application>().getString(
                        R.string.solar_scan_message_analysis_required
                    )
                )
            }
            return
        }
        if (current.busy || operation?.isCompleted == false) return
        val repository = repository ?: run {
            _state.update {
                it.copy(
                    message = getApplication<Application>().getString(
                        R.string.solar_scan_message_storage_unavailable
                    )
                )
            }
            return
        }
        recoveryTaskId = null
        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                phase = SolarScanPhase.RECONSTRUCTING,
                currentLeg = 0,
                totalLegs = 0,
                currentDirection = null,
                message = null
            )
        }
        operation = viewModelScope.launch(Dispatchers.IO) {
            val operationJob = checkNotNull(coroutineContext[Job])
            val taskId = repository.newTaskId()
            var staging: File? = null
            try {
                startupMaintenance.join()
                val identity = SolarSourceIdentity.of(file)
                reconstructionSettingsSession.markUsed(current.reconstructionSettings)
                staging = repository.createStaging(taskId)
                val output = File(staging, "products").apply { mkdirs() }
                val memoryTrace = ReconstructionMemoryTrace(::readSystemMemoryHeadroom)
                val native = SolarScanNative.reconstruct(
                    source = file,
                    outputDirectory = output,
                    settings = current.reconstructionSettings,
                    memoryHeadroomBytes = memoryTrace.nativeHeadroomBytes(),
                    chinese = useChineseWarnings(),
                    observer = operationObserver(operationJob) { value ->
                        memoryTrace.sampleFromProgress()
                        _state.update { it.copy(progress = value) }
                    }
                )
                val summary = native.toDomain(
                    file,
                    current.reconstructionSettings,
                    memoryTrace.summary()
                )
                validateProducts(output, current.reconstructionSettings.products)
                ensureActive(operationJob, "Solar reconstruction cancelled.")
                val record = SolarScanTaskRecord(
                    id = taskId,
                    createdAtUtc = Instant.now().toString(),
                    completedAtUtc = Instant.now().toString(),
                    title = file.nameWithoutExtension,
                    configuration = current.configuration.copy(roi = analysis.suggestedRoi),
                    analysis = analysis,
                    reconstructionSettings = current.reconstructionSettings,
                    legs = listOf(
                        SolarScanLegRecord(
                            number = 1,
                            direction = SolarScanDirection.UNKNOWN,
                            source = identity,
                            recordingFrames = summary.sourceFrames,
                            droppedFrames = 0,
                            reconstruction = summary,
                            sourceDisposition = SolarSourceDispositionState.KEPT,
                            analysis = analysis,
                            reconstructionSettings = current.reconstructionSettings
                        )
                    ),
                    sourcePolicy = SolarSourcePolicy.KEEP,
                    warnings = summary.warnings,
                    resultDirectory = staging
                )
                ensureActive(operationJob, "Solar reconstruction cancelled.")
                val committed = repository.commit(staging, taskId, record)
                staging = null
                refreshInternal()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 1f,
                        phase = SolarScanPhase.COMPLETED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_saved
                        ),
                        lastResultDirectory = committed.resultDirectory
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = SolarScanPhase.CANCELLED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_reconstruction_cancelled
                        )
                    )
                }
            } catch (error: Throwable) {
                if (!operationJob.isActive) {
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = 0f,
                            phase = SolarScanPhase.CANCELLED,
                            message = getApplication<Application>().getString(
                                R.string.solar_scan_message_reconstruction_cancelled
                            )
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = SolarScanPhase.FAILED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_reconstruction_failed,
                            error.message.orEmpty()
                        )
                    )
                }
            } finally {
                staging?.deleteRecursively()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runSimulation() {
        val current = _state.value
        if (current.busy || operation?.isCompleted == false) return
        val repository = repository ?: run {
            _state.update {
                it.copy(
                    message = getApplication<Application>().getString(
                        R.string.solar_scan_message_storage_unavailable
                    )
                )
            }
            return
        }
        val configuration = current.configuration.copy(
            roi = com.indigo.mobileobservatory.solar.SolarRoi(
                width = current.configuration.roi.width.coerceIn(32, 512),
                height = current.configuration.roi.height.coerceIn(12, 96)
            ),
            pixelBytes = 2,
            legCount = current.configuration.legCount.coerceAtLeast(2)
        )
        val report = preflight(configuration)
        saveConfiguration(configuration)
        _state.update { it.copy(configuration = configuration, preflight = report) }
        if (!report.canStart) {
            _state.update {
                it.copy(
                    message = getApplication<Application>().getString(
                        R.string.solar_scan_message_preflight
                    )
                )
            }
            return
        }
        val taskId = repository.newTaskId()
        val permitTaskId = taskId.takeIf { configuration.sourcePolicy != SolarSourcePolicy.KEEP }
        recoveryTaskId = permitTaskId
        if (permitTaskId != null) {
            try {
                repository.authorizeDispositionRecovery(permitTaskId)
            } catch (failure: Exception) {
                recoveryTaskId = null
                _state.update {
                    it.copy(
                        phase = SolarScanPhase.FAILED,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_simulation_failed,
                            failure.message.orEmpty()
                        )
                    )
                }
                return
            }
        }
        simulationStopRequested.set(false)
        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                phase = SolarScanPhase.PREFLIGHT,
                currentLeg = 0,
                totalLegs = configuration.legCountToRun(),
                currentDirection = null,
                completedLegs = 0,
                message = null
            )
        }
        // Even an immediate STOP must reach the cancellation handler and clear busy.
        operation = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val operationJob = checkNotNull(coroutineContext[Job])
            var staging: File? = null
            val createdSources = mutableListOf<File>()
            val sealedSources = mutableSetOf<File>()
            var publishedResult: File? = null
            var finalRecord: SolarScanTaskRecord? = null
            var recoveryClosed = false
            val stopRequested = { simulationStopRequested.get() }
            val checkDispositionActive = {
                ensureActive(operationJob, "Solar simulation cancelled.")
                if (simulationStopRequested.get() || _state.value.phase == SolarScanPhase.STOPPING) {
                    throw CancellationException("Solar simulation cancelled.")
                }
            }
            try {
                startupMaintenance.join()
                checkDispositionActive()
                reconstructionSettingsSession.markUsed(current.reconstructionSettings)
                staging = repository.createStaging(taskId)
                val products = File(staging, "products").apply { mkdirs() }
                val plans = configuration.legPlans()
                val legs = mutableListOf<SolarScanLegRecord>()
                var analyzed: SolarSpectrumAnalysis? = null
                for ((index, plan) in plans.withIndex()) {
                    checkDispositionActive()
                    val source = File(
                        File(checkNotNull(recordingsDir), "SER"),
                        "$taskId-${plan.outputName}.ser"
                    )
                    source.parentFile?.mkdirs()
                    val partialSource = File(source.parentFile, ".${source.name}.partial")
                    createdSources += partialSource
                    _state.update {
                        it.copy(
                            phase = SolarScanPhase.RECORDING,
                            currentLeg = plan.number,
                            totalLegs = plans.size,
                            currentDirection = plan.direction
                        )
                    }
                    val simulation = SolarScanSimulator.generate(
                        destination = partialSource,
                        configuration = configuration,
                        direction = plan.direction,
                        seed = 0x534F4C4152L + index,
                        cancelCheck = checkDispositionActive
                    )
                    sealedSources += partialSource
                    val sealedSource = repository.markSimulationSourceComplete(partialSource)
                    val sourceIdentity = publishSealedSimulationSource(sealedSource, source)
                    val analysis = SolarScanNative.analyze(
                        source,
                        operationObserver(operationJob, stopRequested) { value ->
                            _state.update {
                                it.copy(progress = (index + value * 0.2f) / plans.size)
                            }
                        }
                    ).toDomain()
                    if (analyzed == null) analyzed = analysis
                    _state.update { it.copy(phase = SolarScanPhase.RECONSTRUCTING) }
                    val legOutput = File(products, plan.outputName).apply { mkdirs() }
                    val legSettings = current.reconstructionSettings.copy(
                        flipVertical = current.reconstructionSettings.flipVertical xor
                            plan.direction.flipVertical
                    )
                    val memoryTrace = ReconstructionMemoryTrace(::readSystemMemoryHeadroom)
                    val native = SolarScanNative.reconstruct(
                        source = source,
                        outputDirectory = legOutput,
                        settings = legSettings,
                        memoryHeadroomBytes = memoryTrace.nativeHeadroomBytes(),
                        chinese = useChineseWarnings(),
                        observer = operationObserver(operationJob, stopRequested) { value ->
                            memoryTrace.sampleFromProgress()
                            _state.update {
                                it.copy(progress = (index + 0.2f + value * 0.8f) / plans.size)
                            }
                        }
                    )
                    val summary = native.toDomain(
                        source,
                        legSettings,
                        memoryTrace.summary()
                    )
                    validateProducts(legOutput, current.reconstructionSettings.products)
                    legs += SolarScanLegRecord(
                        number = plan.number,
                        direction = plan.direction,
                        source = sourceIdentity,
                        recordingFrames = simulation.frames,
                        droppedFrames = 0,
                        reconstruction = summary,
                        sourceDisposition = SolarSourceDispositionState.KEPT,
                        coverage = simulation.coverage,
                        analysis = analysis,
                        reconstructionSettings = legSettings
                    )
                    _state.update { it.copy(completedLegs = legs.size) }
                }
                ensureActive(operationJob, "Solar simulation cancelled.")
                File(staging, "coverage.txt").writeText(
                    buildString {
                        appendLine("solar-scan simulation coverage")
                        appendLine("task=${taskId}")
                        for (leg in legs) {
                            val coverage = leg.coverage ?: continue
                            appendLine(
                                "leg=${leg.number} direction=${leg.direction.name} " +
                                    "frames=${leg.recordingFrames} " +
                                    "firstContact=${"%.5f".format(coverage.firstContactFraction)} " +
                                    "lastContact=${"%.5f".format(coverage.lastContactFraction)} " +
                                    "startBlankArcsec=${"%.2f".format(coverage.startBlankArcsec)} " +
                                    "endBlankArcsec=${"%.2f".format(coverage.endBlankArcsec)} " +
                                    "stableCoverage=${"%.5f".format(coverage.stableCoverageFraction)} " +
                                    "accelerationOverlap=${coverage.accelerationOverlapsDisk} " +
                                    "decelerationOverlap=${coverage.decelerationOverlapsDisk}"
                            )
                        }
                    },
                    Charsets.UTF_8
                )
                val record = SolarScanTaskRecord(
                    id = taskId,
                    createdAtUtc = Instant.now().toString(),
                    completedAtUtc = Instant.now().toString(),
                    title = "太阳线扫仿真",
                    configuration = configuration,
                    analysis = analyzed,
                    reconstructionSettings = current.reconstructionSettings,
                    legs = legs,
                    sourcePolicy = configuration.sourcePolicy,
                    warnings = buildList {
                        if (configuration.sourcePolicy != SolarSourcePolicy.KEEP) {
                            add(
                                getApplication<Application>().getString(
                                    R.string.solar_scan_warning_source_disposed
                                )
                            )
                        }
                    },
                    resultDirectory = staging
                )
                checkDispositionActive()
                _state.update { it.copy(phase = SolarScanPhase.COMMITTING) }
                val committed = repository.commit(staging, taskId, record)
                staging = null
                publishedResult = committed.resultDirectory
                val committedRecord = committed.record
                finalRecord = repository.disposeCommittedSources(
                    committedRecord,
                    dispositionWarnings = ::sourceDispositionWarnings,
                    onRecoveryClosed = { recoveryClosed = true },
                    commitDisposition = { mutate ->
                        synchronized(sourceDispositionCommit) {
                            checkDispositionActive()
                        }
                        // Admission above is the commit point. Only admitted mutations may finish after STOP.
                        mutate()
                    },
                    checkActive = checkDispositionActive
                )
                refreshInternal()

                while (true) {
                    val before = _state.value
                    checkDispositionActive()
                    val completed = before.copy(
                        busy = false,
                        progress = 1f,
                        phase = SolarScanPhase.COMPLETED,
                        currentLeg = plans.size,
                        completedLegs = plans.size,
                        message = getApplication<Application>().getString(
                            R.string.solar_scan_message_simulation_complete
                        ),
                        lastResultDirectory = committed.resultDirectory
                    )
                    if (_state.compareAndSet(before, completed)) break
                }
            } catch (cancelled: CancellationException) {
                val saveFailure = finalRecord?.let { record ->
                    runCatching {
                        repository.keepPendingSourcesAfterStop(
                            record,
                            onRecoveryClosed = { recoveryClosed = true }
                        )
                    }.exceptionOrNull()
                }
                if (saveFailure != null) {
                    FileLogger.e("SolarScan", "Could not save source retention after STOP", saveFailure)
                }
                if (publishedResult != null) {
                    runCatching { refreshInternal() }.onFailure { error ->
                        FileLogger.e("SolarScan", "Could not refresh cancelled simulation results", error)
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = if (saveFailure == null) SolarScanPhase.CANCELLED else SolarScanPhase.FAILED,
                        lastResultDirectory = publishedResult ?: it.lastResultDirectory,
                        message = if (saveFailure == null) getApplication<Application>().getString(
                            R.string.solar_scan_message_simulation_cancelled
                        ) else getApplication<Application>().getString(
                            if (recoveryClosed) R.string.solar_scan_message_stop_save_failed
                            else R.string.solar_scan_message_stop_retention_unconfirmed,
                            saveFailure.message.orEmpty()
                        )
                    )
                }
            } catch (error: Throwable) {
                val failedRecoveryRevocation = if (publishedResult == null && permitTaskId != null) {
                    runCatching { repository.revokeRecoveryPermit(permitTaskId) }.exceptionOrNull()
                } else null
                if (failedRecoveryRevocation != null) {
                    FileLogger.e("SolarScan", "Could not revoke recovery for unpublished results", failedRecoveryRevocation)
                }
                if (publishedResult != null || sealedSources.isNotEmpty()) {
                    runCatching { refreshInternal() }.onFailure { refreshFailure ->
                        FileLogger.e("SolarScan", "Could not refresh failed simulation results", refreshFailure)
                    }
                }
                if ((!operationJob.isActive || simulationStopRequested.get()) && publishedResult == null &&
                    failedRecoveryRevocation == null
                ) {
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = 0f,
                            phase = SolarScanPhase.CANCELLED,
                            message = getApplication<Application>().getString(
                                R.string.solar_scan_message_simulation_cancelled
                            )
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        phase = SolarScanPhase.FAILED,
                        lastResultDirectory = publishedResult ?: it.lastResultDirectory,
                        message = getApplication<Application>().getString(
                            when {
                                operationJob.isActive && !simulationStopRequested.get() ->
                                    R.string.solar_scan_message_simulation_failed
                                recoveryClosed -> R.string.solar_scan_message_stop_save_failed
                                else -> R.string.solar_scan_message_stop_retention_unconfirmed
                            },
                            error.message.orEmpty()
                        )
                    )
                }
            } finally {
                if (publishedResult == null && permitTaskId != null) {
                    runCatching { repository.revokeRecoveryPermit(permitTaskId) }.onFailure {
                        FileLogger.e("SolarScan", "Could not remove unused source recovery permit", it)
                    }
                }
                staging?.deleteRecursively()
                incompleteSimulationSourcesToDelete(createdSources, sealedSources)
                    .forEach(File::delete)
            }
        }
    }

    fun cancel() {
        val running = operation ?: return
        if (!_state.value.busy || running.isCompleted) return
        synchronized(sourceDispositionCommit) {
            simulationStopRequested.set(true)
        }
        _state.update {
            if (it.busy && running.isActive) it.copy(phase = SolarScanPhase.STOPPING) else it
        }
        // Revoke on the caller thread: the worker can be blocked in native code or file IO.
        recoveryTaskId?.let { taskId ->
            val repository = checkNotNull(repository)
            runCatching { repository.revokeRecoveryPermit(taskId) }.onFailure { failure ->
                FileLogger.e("SolarScan", "Could not revoke source recovery at STOP", failure)
                val message = getApplication<Application>().getString(
                    R.string.solar_scan_message_stop_retention_unconfirmed,
                    failure.message.orEmpty()
                )
                _state.update {
                    if (it.phase == SolarScanPhase.STOPPING) it.copy(message = message) else it
                }
            }
        }
        running.cancel(CancellationException("User cancelled solar scan"))
    }

    internal fun cancelHardware() {
        if (_state.value.hardwareActive) cancel()
    }

    private fun sourceDispositionWarnings(legs: List<SolarScanLegRecord>): List<String> = buildList {
        val application = getApplication<Application>()
        if (legs.any { it.sourceDisposition == SolarSourceDispositionState.FAILED }) {
            add(application.getString(R.string.solar_scan_warning_source_disposition_failed))
        }
        if (legs.any { leg ->
                leg.coverage?.let { it.accelerationOverlapsDisk || it.decelerationOverlapsDisk } == true
            }
        ) {
            add(application.getString(R.string.solar_scan_warning_coverage))
        }
    }

    fun updateConfiguration(transform: (SolarScanConfiguration) -> SolarScanConfiguration) {
        _state.update { current ->
            val next = transform(current.configuration)
            saveConfiguration(next)
            current.copy(
                configuration = next,
                preflight = preflight(next)
            )
        }
    }

    fun updateReconstruction(transform: (SolarReconstructionSettings) -> SolarReconstructionSettings) {
        _state.update {
            val next = reconstructionSettingsSession.edit(transform)
            val configuration = it.configuration.copy(products = next.products)
            saveConfiguration(configuration)
            it.copy(
                reconstructionSettings = next,
                configuration = configuration,
                preflight = preflight(configuration)
            )
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun refreshInternal() {
        val files = MediaLibrary.list(
            recordingsDir,
            capturesDir,
            MediaCategory.SER_PSER
        ).filter { it.format == "SER" }
        val runs = repository?.listTasks().orEmpty()
        _state.update { it.copy(files = files, runs = runs) }
    }

    private fun preflight(configuration: SolarScanConfiguration): SolarScanPreflight =
        SolarScanPreflightEvaluator.evaluate(
            configuration = configuration,
            availableBytes = minOf(
                recordingsDir?.usableSpace ?: 0L,
                capturesDir?.usableSpace ?: 0L
            ),
            calibratedForCurrentMount = false,
            supportedPixelBytes = setOf(1, 2)
        )

    private fun defaultConfiguration(): SolarScanConfiguration {
        val now = Instant.now()
        val sun = SequenceEphemeris.sunEquatorial(now)
        val dec = sun.decDeg
        val halfHours = 0.7 / maxOf(0.1, cos(Math.toRadians(dec))) / 15.0
        val centerHours = sun.raDeg / 15.0
        return SolarScanConfiguration(
            endpointA = com.indigo.mobileobservatory.solar.SolarScanEndpoint(
                (centerHours - halfHours).mod(24.0),
                dec
            ),
            endpointB = com.indigo.mobileobservatory.solar.SolarScanEndpoint(
                (centerHours + halfHours).mod(24.0),
                dec
            ),
            roi = com.indigo.mobileobservatory.solar.SolarRoi(width = 320, height = 48)
        )
    }

    private fun saveConfiguration(configuration: SolarScanConfiguration) {
        preferences.edit()
            .putString("endpoint_a", "${configuration.endpointA.raHours},${configuration.endpointA.decDegrees}")
            .putString("endpoint_b", "${configuration.endpointB.raHours},${configuration.endpointB.decDegrees}")
            .putLong("scan_duration_ms", configuration.scanDurationMs)
            .putLong("disk_crossing_ms", configuration.diskCrossingMs)
            .putFloat("disk_diameter_arcsec", configuration.diskDiameterArcsec.toFloat())
            .putFloat("start_blank_arcsec", configuration.startBlankArcsec.toFloat())
            .putFloat("end_blank_arcsec", configuration.endBlankArcsec.toFloat())
            .putFloat("position_uncertainty_arcsec", configuration.positionUncertaintyArcsec.toFloat())
            .putFloat("frame_rate", configuration.frameRate.toFloat())
            .putLong("exposure_us", configuration.exposureUs)
            .putInt("roi_width", configuration.roi.width)
            .putInt("roi_height", configuration.roi.height)
            .putInt("pixel_bytes", configuration.pixelBytes)
            .putInt("leg_count", configuration.legCount)
            .putBoolean("continuous", configuration.continuous)
            .putString("source_policy", configuration.sourcePolicy.name)
            .putStringSet("products", configuration.products.map { it.name }.toSet())
            .apply()
    }

    private fun saveReconstructionSettings(settings: SolarReconstructionSettings) {
        preferences.edit()
            .putFloat("line_shift", settings.lineShiftPixels.toFloat())
            .putBoolean("flip_horizontal", settings.flipHorizontal)
            .putBoolean("flip_vertical", settings.flipVertical)
            .putInt("rotation", settings.rotationDegrees)
            .putInt("frame_stride", settings.frameStride)
            .putString("scan_banding", settings.scanCorrection.bandingAxis.name)
            .putInt("scan_banding_width", settings.scanCorrection.bandingWidthPixels ?: 0)
            .putBoolean("scan_align_rows", settings.scanCorrection.alignScanRows)
            .putBoolean(
                "scan_oscillation",
                settings.scanCorrection.correctPeriodicOscillation
            )
            .putBoolean("flat_synthetic", settings.flat.synthetic)
            .putInt("flat_synthetic_strength", settings.flat.syntheticStrength)
            .putString("flat_physical_path", settings.flat.physicalSourcePath)
            .putStringSet("output_products", settings.products.map { it.name }.toSet())
            .apply()
    }

    private fun loadConfiguration(): SolarScanConfiguration? = runCatching {
        fun endpoint(key: String): com.indigo.mobileobservatory.solar.SolarScanEndpoint {
            val fields = preferences.getString(key, null)?.split(',') ?: error("missing endpoint")
            require(fields.size == 2)
            return com.indigo.mobileobservatory.solar.SolarScanEndpoint(
                fields[0].toDouble(),
                fields[1].toDouble()
            )
        }
        SolarScanConfiguration(
            endpointA = endpoint("endpoint_a"),
            endpointB = endpoint("endpoint_b"),
            scanDurationMs = preferences.getLong("scan_duration_ms", 6_000),
            diskCrossingMs = preferences.getLong("disk_crossing_ms", 4_500),
            diskDiameterArcsec = preferences.getFloat("disk_diameter_arcsec", 1_920f).toDouble(),
            startBlankArcsec = preferences.getFloat("start_blank_arcsec", 60f).toDouble(),
            endBlankArcsec = preferences.getFloat("end_blank_arcsec", 60f).toDouble(),
            positionUncertaintyArcsec = preferences.getFloat(
                "position_uncertainty_arcsec",
                30f
            ).toDouble(),
            frameRate = preferences.getFloat("frame_rate", 200f).toDouble(),
            exposureUs = preferences.getLong("exposure_us", 3_000),
            roi = com.indigo.mobileobservatory.solar.SolarRoi(
                width = preferences.getInt("roi_width", 320),
                height = preferences.getInt("roi_height", 48)
            ),
            pixelBytes = preferences.getInt("pixel_bytes", 2),
            legCount = preferences.getInt("leg_count", 2),
            continuous = preferences.getBoolean("continuous", false),
            sourcePolicy = preferences.getString("source_policy", null)
                ?.let(SolarSourcePolicy::valueOf)
                ?: SolarSourcePolicy.KEEP,
            products = preferences.getStringSet("products", null)
                ?.map(SolarOutputProduct::valueOf)
                ?.toSet()
                ?: setOf(SolarOutputProduct.DISPLAY_PNG, SolarOutputProduct.LINEAR_FITS)
        )
    }.getOrNull()

    private fun loadReconstructionSettings(): SolarReconstructionSettings = runCatching {
        SolarReconstructionSettings(
            lineShiftPixels = preferences.getFloat("line_shift", 0f).toDouble(),
            flipHorizontal = preferences.getBoolean("flip_horizontal", false),
            flipVertical = preferences.getBoolean("flip_vertical", false),
            rotationDegrees = preferences.getInt("rotation", 0),
            frameStride = preferences.getInt("frame_stride", 1),
            scanCorrection = com.indigo.mobileobservatory.solar.SolarScanCorrectionSettings(
                bandingAxis = preferences.getString("scan_banding", null)
                    ?.let {
                        runCatching { com.indigo.mobileobservatory.solar.SolarBandingAxis.valueOf(it) }
                            .getOrNull()
                    }
                    ?: com.indigo.mobileobservatory.solar.SolarBandingAxis.OFF,
                bandingWidthPixels = preferences.getInt("scan_banding_width", 0)
                    .takeIf { it > 0 },
                alignScanRows = preferences.getBoolean("scan_align_rows", false),
                correctPeriodicOscillation = preferences.getBoolean("scan_oscillation", false)
            ),
            flat = com.indigo.mobileobservatory.solar.SolarFlatSettings(
                synthetic = preferences.getBoolean("flat_synthetic", false),
                syntheticStrength = preferences.getInt("flat_synthetic_strength", 100),
                physicalSourcePath = preferences.getString("flat_physical_path", null)
                    ?.takeIf { it.isNotBlank() }
            ),
            products = preferences.getStringSet("output_products", null)
                ?.map(SolarOutputProduct::valueOf)
                ?.toSet()
                ?: setOf(SolarOutputProduct.DISPLAY_PNG, SolarOutputProduct.LINEAR_FITS)
        )
    }.getOrDefault(SolarReconstructionSettings())

    private fun previewBitmap(data: NativeSolarPreview): Bitmap {
        require(data.pixels.size >= data.width * data.height)
        val sourceWidth = data.width
        val sourceHeight = data.height
        val maxWidth = 1_024
        val width = sourceWidth.coerceAtMost(maxWidth)
        val height = (sourceHeight.toDouble() * width / sourceWidth)
            .roundToInt()
            .coerceAtLeast(1)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = (y.toLong() * sourceHeight / height).toInt().coerceAtMost(sourceHeight - 1)
            for (x in 0 until width) {
                val sourceX = (x.toLong() * sourceWidth / width).toInt().coerceAtMost(sourceWidth - 1)
                val value = data.pixels[sourceY * sourceWidth + sourceX].toInt() and 255
                pixels[y * width + x] = -0x1000000 or (value shl 16) or (value shl 8) or value
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun NativeSolarAnalysis.toDomain(): SolarSpectrumAnalysis {
        require(traceRows.size == width)
        return SolarSpectrumAnalysis(
            trace = com.indigo.mobileobservatory.solar.SolarTrace(
                width = width,
                constantRow = traceConstant,
                linear = traceLinear,
                quadratic = traceQuadratic,
                illuminatedFirst = illuminatedFirst,
                illuminatedLast = illuminatedLast
            ),
            confidence = confidence,
            lineDepth = lineDepth,
            suggestedRoi = com.indigo.mobileobservatory.solar.SolarRoi(
                y = roiTop,
                width = width,
                height = roiHeight
            ),
            validColumns = validColumns,
            residualPixels = residualPixels
        )
    }

    private fun com.indigo.mobileobservatory.solar.NativeSolarReconstruction.toDomain(
        source: File,
        settings: SolarReconstructionSettings,
        memory: SolarMemorySampleSummary
    ): SolarReconstructionSummary {
        val paths = mapOf(
            SolarOutputProduct.DISPLAY_PNG to displayPng,
            SolarOutputProduct.LINEAR_PNG to linearPng,
            SolarOutputProduct.LINEAR_FITS to linearFits
        )
        val integrity = settings.products.associateWith { product ->
            val path = checkNotNull(paths[product]) {
                "Required solar result ${product.name} is missing."
            }
            com.indigo.mobileobservatory.solar.SolarProductIntegrity.of(File(path))
        }
        return SolarReconstructionSummary(
            source = source.name,
            displayPng = displayPng,
            linearPng = linearPng,
            linearFits = linearFits,
            width = width,
            height = height,
            frameStride = frameStride,
            sourceFrames = sourceFrames,
            warnings = warnings.toList(),
            corrections = SolarCorrectionReport(
                geometryApplied = geometryCorrectionApplied,
                bandingApplied = when (scanBandingApplied) {
                    1 -> SolarBandingAxis.VERTICAL
                    2 -> SolarBandingAxis.HORIZONTAL
                    3 -> SolarBandingAxis.AUTO
                    else -> SolarBandingAxis.OFF
                },
                bandingWidthPixels = scanBandingWidthPixels.takeIf { it > 0 },
                scanRowsAligned = scanRowsAligned,
                oscillationPeriodPixels = oscillationPeriodPixels.takeIf { oscillationDetected },
                oscillationAmplitudePixels = oscillationAmplitudePixels.takeIf {
                    oscillationDetected
                },
                syntheticFlatStrength = syntheticFlatStrength.takeIf { it > 0 },
                physicalFlatApplied = physicalFlatApplied
            ),
            memory = memory,
            integrity = integrity,
            completedAtUtc = Instant.now().toString()
        )
    }

    private fun readSystemMemoryHeadroom(): Long? {
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
            ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.availMem - info.threshold).coerceAtLeast(0L)
    }

    private fun ensureActive(job: Job, message: String) {
        if (!job.isActive) throw CancellationException(message)
    }

    private class ReconstructionMemoryTrace(
        private val readHeadroom: () -> Long?
    ) {
        private val firstSampledAtUtc = Instant.now().toString()
        private val initialHeadroom = readHeadroom()
        private var lastSampleElapsedMs = SystemClock.elapsedRealtime()
        private var sampleCount = if (initialHeadroom == null) 0 else 1
        private var maxIntervalMs = 0L

        fun nativeHeadroomBytes(): Long =
            initialHeadroom ?: SolarScanNative.UNKNOWN_SYSTEM_MEMORY_HEADROOM

        fun sampleFromProgress() {
            val now = SystemClock.elapsedRealtime()
            val interval = now - lastSampleElapsedMs
            if (interval < 1_000L) return
            lastSampleElapsedMs = now
            if (readHeadroom() == null) return
            sampleCount++
            maxIntervalMs = maxOf(maxIntervalMs, interval)
        }

        fun summary(): SolarMemorySampleSummary = SolarMemorySampleSummary(
            headroomBytes = initialHeadroom,
            firstSampledAtUtc = firstSampledAtUtc,
            sampleCount = sampleCount,
            maxIntervalMs = maxIntervalMs
        )
    }

    private fun operationObserver(
        operationJob: Job,
        stopRequested: () -> Boolean = { false },
        onProgress: (Float) -> Unit
    ): SolarNativeObserver = object : SolarNativeObserver {
        override fun isCancelled(): Boolean = stopRequested() || !operationJob.isActive
        override fun onProgress(value: Float) = onProgress(value)
    }

    private fun useChineseWarnings(): Boolean =
        getApplication<Application>().resources.configuration.locales[0].language
            .startsWith("zh", ignoreCase = true)

    private fun validateProducts(directory: File, products: Set<SolarOutputProduct>) {
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        check(files.isNotEmpty() && files.all { it.length() > 0L }) {
            "Solar result products were not finalized."
        }
        if (SolarOutputProduct.DISPLAY_PNG in products) {
            check(files.any { it.name.endsWith("-display.png") && it.length() > 0L }) {
                "The solar display PNG is missing."
            }
        }
        if (SolarOutputProduct.LINEAR_PNG in products) {
            check(files.any { it.name.endsWith("-linear.png") && it.length() > 0L }) {
                "The solar linear PNG is missing."
            }
        }
        if (SolarOutputProduct.LINEAR_FITS in products) {
            check(files.any { it.extension.equals("fit", true) && it.length() > 0L }) {
                "The solar linear FITS is missing."
            }
        }
    }
}
