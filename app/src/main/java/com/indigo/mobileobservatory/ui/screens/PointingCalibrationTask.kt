package com.indigo.mobileobservatory.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.pointing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PointingCalibrationCapture(
    val pair: CalibrationPair,
    val handEyeSample: HandEyeSample? = null
)

internal data class PointingCalibrationDraft(
    val first: CalibrationPair? = null,
    val validation: CalibrationPair? = null,
    val fitted: OpticalCalibration? = null,
    val fittedHandEye: HandEyeCalibration? = null,
    val mirrorTraining: List<HandEyeSample> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val alignmentPair: CalibrationPair? = null,
    val revisions: List<PointingRepository.CaptureRevision> = emptyList(),
    val configurationKey: String = "",
    val mirrored: Boolean = false,
    /** Completed captures and total captures, independent of the mount's movement progress. */
    val automaticProgress: Pair<Int, Int>? = null
)

/** Injectable scientific storage boundary; adapters keep acquisition and device ownership outside this task. */
internal interface CalibrationTaskStore {
    fun captureRevision(path: String): PointingRepository.CaptureRevision
    fun isSameInstallation(revision: PointingRepository.CaptureRevision): Boolean
    fun opticalVersion(pair: CalibrationPair): Long
    fun handEyeVersion(observation: OpticalObservation): Long
    fun save(value: OpticalCalibration, pairs: List<CalibrationPair>, handEye: HandEyeCalibration?,
             revisions: List<PointingRepository.CaptureRevision>)
}

private class RepositoryCalibrationTaskStore(private val repository: PointingRepository) : CalibrationTaskStore {
    override fun captureRevision(path: String) = repository.captureRevision(path)
    override fun isSameInstallation(revision: PointingRepository.CaptureRevision) = repository.isSameInstallation(revision)
    override fun opticalVersion(pair: CalibrationPair) = repository.relationships.value.filter {
        it.source == pair.camera.identity && it.target == pair.reference.identity
    }.maxOfOrNull { it.version } ?: 0L
    override fun handEyeVersion(observation: OpticalObservation) = repository.handEyes.value.filter {
        it.source == observation.identity && it.geometry == observation.geometry
    }.maxOfOrNull { it.version } ?: 0L
    override fun save(value: OpticalCalibration, pairs: List<CalibrationPair>, handEye: HandEyeCalibration?,
                      revisions: List<PointingRepository.CaptureRevision>) =
        repository.saveValidated(value, pairs, handEye, expectedRevisions = revisions)
}

/**
 * Phone lenses and auxiliary cameras share a training/holdout/save lifecycle. Real capture
 * adapters remain responsible for simultaneous timing, motion evidence and camera ownership.
 * Closing or failing a draft never replaces an existing validated relationship.
 */
internal class PointingCalibrationTask(private val testScope: CoroutineScope? = null) : ViewModel() {
    private val _draft = MutableStateFlow(PointingCalibrationDraft())
    val draft = _draft.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private val scope get() = testScope ?: viewModelScope

    fun sample(repository: PointingRepository, configurationKey: String, paths: List<String>,
               mirrored: Boolean = false, alignmentOnly: Boolean = false,
               onFailure: (Throwable) -> String = { it.message ?: "Calibration failed" },
               capture: suspend () -> PointingCalibrationCapture) =
        sample(RepositoryCalibrationTaskStore(repository), configurationKey, paths, mirrored, alignmentOnly, onFailure, capture)

    internal fun sample(store: CalibrationTaskStore, configurationKey: String, paths: List<String>,
                        mirrored: Boolean = false, alignmentOnly: Boolean = false,
                        onFailure: (Throwable) -> String = { it.message ?: "Calibration failed" },
                        capture: suspend () -> PointingCalibrationCapture) {
        if (_draft.value.busy) return
        if (!prepare(store, configurationKey, paths, mirrored, clearTraining = alignmentOnly, onFailure = onFailure)) return
        val request = ++generation
        _draft.value = _draft.value.copy(busy = true, error = null, fitted = null,
            fittedHandEye = null, validation = null, saved = false, alignmentPair = null)
        job = scope.launch {
            try {
                val result = capture()
                ensureCurrent(request, store, configurationKey)
                if (alignmentOnly) {
                    // Validate the same timing/motion contract as a final calibration. Never train on a rough adjustment.
                    CalibrationAlignmentOffset.fromPair(result.pair)
                    checkCapturedPaths(result.pair)
                    _draft.value = _draft.value.copy(alignmentPair = result.pair)
                } else accept(result, store, mirrored)
            } catch (timeout: TimeoutCancellationException) {
                failIfCurrent(request, timeout, onFailure)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                failIfCurrent(request, failure, onFailure)
            } finally {
                finishIfCurrent(request)
            }
        }
    }

    fun runAutomatic(repository: PointingRepository, configurationKey: String, paths: List<String>,
                     mirrored: Boolean = false,
                     onFailure: (Throwable) -> String = { it.message ?: "Calibration failed" },
                     run: suspend (suspend () -> Unit) -> Unit,
                     capture: suspend () -> PointingCalibrationCapture) =
        runAutomatic(RepositoryCalibrationTaskStore(repository), configurationKey, paths, mirrored, onFailure, run, capture)

    internal fun runAutomatic(store: CalibrationTaskStore, configurationKey: String, paths: List<String>,
                              mirrored: Boolean = false,
                              onFailure: (Throwable) -> String = { it.message ?: "Calibration failed" },
                              run: suspend (suspend () -> Unit) -> Unit,
                              capture: suspend () -> PointingCalibrationCapture) {
        if (_draft.value.busy) return
        if (!prepare(store, configurationKey, paths, mirrored, clearTraining = true, onFailure = onFailure)) return
        val request = ++generation
        val required = if (mirrored) 4 else 2
        _draft.value = _draft.value.copy(busy = true, error = null, automaticProgress = 0 to required)
        job = scope.launch {
            try {
                var completed = 0
                run {
                    ensureCurrent(request, store, configurationKey)
                    check(completed < required) { "Automatic calibration produced too many captures" }
                    val result = capture()
                    ensureCurrent(request, store, configurationKey)
                    // Fit failures propagate to the motion owner, which must end all subsequent GOTO.
                    accept(result, store, mirrored)
                    completed++
                    _draft.value = _draft.value.copy(automaticProgress = completed to required)
                }
                ensureCurrent(request, store, configurationKey)
                check(completed == required && _draft.value.fitted != null &&
                    (!mirrored || _draft.value.fittedHandEye != null)) { "Automatic calibration is incomplete" }
            } catch (timeout: TimeoutCancellationException) {
                failIfCurrent(request, timeout, onFailure)
            } catch (cancel: CancellationException) {
                if (generation == request) clearFittedResult()
                throw cancel
            } catch (failure: Throwable) {
                failIfCurrent(request, failure, onFailure)
            } finally {
                finishIfCurrent(request)
            }
        }
    }

    /** A capture can only reuse training from the same selected optics and installation. */
    private fun prepare(store: CalibrationTaskStore, configurationKey: String, paths: List<String>,
                        mirrored: Boolean, clearTraining: Boolean, onFailure: (Throwable) -> String): Boolean {
        require(configurationKey.isNotBlank() && paths.isNotEmpty() && paths.all(String::isNotBlank))
        val previous = _draft.value
        if (!clearTraining && previous.first != null &&
            (previous.configurationKey != configurationKey || previous.mirrored != mirrored ||
                previous.revisions.map { it.path }.toSet() != paths.toSet() ||
                previous.revisions.any { !store.isSameInstallation(it) })) {
            reset()
            _draft.value = _draft.value.copy(error = onFailure(IllegalStateException(CONFIGURATION_CHANGED)))
            return false
        }
        if (clearTraining || previous.first == null) {
            _draft.value = PointingCalibrationDraft(configurationKey = configurationKey,
                revisions = paths.distinct().map(store::captureRevision), mirrored = mirrored)
        }
        return true
    }

    private suspend fun ensureCurrent(request: Long, store: CalibrationTaskStore, configurationKey: String) {
        currentCoroutineContext().ensureActive()
        check(generation == request && _draft.value.configurationKey == configurationKey &&
            _draft.value.revisions.all(store::isSameInstallation)) { CONFIGURATION_CHANGED }
    }

    private fun accept(result: PointingCalibrationCapture, store: CalibrationTaskStore, mirrored: Boolean) {
        val pair = result.pair
        pair.validate()
        checkCapturedPaths(pair)
        val value = _draft.value
        val handEye = if (mirrored) requireNotNull(result.handEyeSample) { "Missing synchronous sensor attitude" } else null
        if (handEye != null) {
            handEye.validate()
            require(handEye.observation == pair.camera) { "Sensor attitude belongs to another exposure" }
            require(value.mirrorTraining.none { it.observation.id == handEye.observation.id ||
                it.observation.independentReferenceId == handEye.observation.independentReferenceId }) {
                "Training must use independent exposures"
            }
        }
        val first = value.first
        if (first != null) {
            require(first.camera.identity == pair.camera.identity && first.camera.geometry == pair.camera.geometry &&
                first.reference.identity == pair.reference.identity &&
                first.reference.acquisitionDeviceId == pair.reference.acquisitionDeviceId) { CONFIGURATION_CHANGED }
        }
        if (mirrored && value.mirrorTraining.size < 3) {
            _draft.value = value.copy(first = first ?: pair, mirrorTraining = value.mirrorTraining + requireNotNull(handEye))
        } else if (first == null) {
            _draft.value = value.copy(first = pair)
        } else {
            val fittedHandEye = if (mirrored) HandEyeCalibrationFitter.fit(value.mirrorTraining,
                requireNotNull(handEye), previousVersion = store.handEyeVersion(pair.camera)) else null
            val fitted = OpticalCalibrationFitter.fit(first, pair, previousVersion = store.opticalVersion(pair))
            _draft.value = value.copy(validation = pair, fitted = fitted, fittedHandEye = fittedHandEye)
        }
    }

    private fun checkCapturedPaths(pair: CalibrationPair) {
        val paths = _draft.value.revisions.map { it.path }.toSet()
        require(pair.camera.identity.opticalPathId in paths && pair.reference.identity.opticalPathId in paths) {
            "Missing installation revisions for captured optical paths"
        }
    }

    private fun clearFittedResult() {
        _draft.value = _draft.value.copy(validation = null, fitted = null, fittedHandEye = null, saved = false)
    }

    private fun failIfCurrent(request: Long, failure: Throwable, onFailure: (Throwable) -> String) {
        if (generation != request) return
        clearFittedResult()
        _draft.value = _draft.value.copy(error = onFailure(failure))
    }

    private fun finishIfCurrent(request: Long) {
        if (generation != request) return
        _draft.value = _draft.value.copy(busy = false, automaticProgress = null)
        job = null
    }

    /** Generation changes synchronously, so an uncooperative old capture cannot publish into a new draft. */
    fun cancel() {
        val wasBusy = _draft.value.busy
        generation++
        job?.cancel()
        job = null
        if (wasBusy) clearFittedResult()
        _draft.value = _draft.value.copy(busy = false, automaticProgress = null)
    }

    fun clearValidation() {
        cancel()
        clearFittedResult()
        _draft.value = _draft.value.copy(error = null)
    }

    fun clearTraining() = reset()

    fun reset() {
        cancel()
        _draft.value = PointingCalibrationDraft()
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    fun save(repository: PointingRepository, configurationKey: String, configurationChangedMessage: String): Boolean =
        save(RepositoryCalibrationTaskStore(repository), configurationKey, configurationChangedMessage)

    internal fun save(store: CalibrationTaskStore, configurationKey: String, configurationChangedMessage: String): Boolean {
        val value = _draft.value
        if (value.busy || value.saved || value.fitted == null || value.first == null || value.validation == null) return false
        return try {
            check(value.configurationKey == configurationKey && value.revisions.all(store::isSameInstallation)) {
                CONFIGURATION_CHANGED
            }
            store.save(value.fitted, listOf(value.first, value.validation), value.fittedHandEye, value.revisions)
            _draft.value = value.copy(saved = true, error = null)
            true
        } catch (failure: Throwable) {
            val stale = value.configurationKey != configurationKey || value.revisions.any { !store.isSameInstallation(it) }
            _draft.value = value.copy(error = if (stale) configurationChangedMessage else failure.message,
                fitted = if (stale) null else value.fitted, fittedHandEye = if (stale) null else value.fittedHandEye)
            false
        }
    }

    companion object {
        private const val CONFIGURATION_CHANGED = "Pointing configuration changed; resample"
    }
}
