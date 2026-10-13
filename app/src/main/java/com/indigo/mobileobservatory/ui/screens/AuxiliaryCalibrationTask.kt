package com.indigo.mobileobservatory.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class AuxiliaryCalibrationDraft(val first: CalibrationPair? = null,
    val validation: CalibrationPair? = null, val fitted: OpticalCalibration? = null,
    val busy: Boolean = false, val waitingForCenter: Boolean = false, val saved: Boolean = false,
    val error: String? = null, val revisions: List<PointingRepository.CaptureRevision> = emptyList(),
    val configurationKey: String = "")

/** Actual scientific results survive recreation in memory. Process death never restores a fictional plate. */
internal class AuxiliaryCalibrationTask : ViewModel() {
    private val _draft = MutableStateFlow(AuxiliaryCalibrationDraft())
    val draft = _draft.asStateFlow()
    private var job: Job? = null
    private var centerConfirmation: CompletableDeferred<ObservationStamp>? = null
    private var generation = 0L

    fun sample(vm: CameraViewModel, site: ObserverSite, trackingSky: Boolean,
        star: PhoneCatalogStar?, centeringErrorDeg: Double, mainCamera: Boolean) {
        if (_draft.value.busy) return
        val repository = vm.pointingRepository
        val sourceId = vm.auxiliaryReferenceCameraId()
        val targetId = if (mainCamera) vm.primaryReferenceCameraId() else null
        sourceId?.let { repository.observeConnectedCamera("secondary", it) }
        targetId?.let { repository.observeConnectedCamera("primary", it) }
        val configurationKey = "$sourceId|$targetId|$mainCamera|$trackingSky"
        fun configurationChanged() = vm.getApplication<android.app.Application>().getString(com.indigo.mobileobservatory.R.string.wfp_configuration_changed)
        val previous = _draft.value
        if (previous.first != null && (previous.configurationKey != configurationKey ||
                previous.revisions.any { !repository.isSameInstallation(it) })) {
            reset(); _draft.value = AuxiliaryCalibrationDraft(error = configurationChanged()); return
        }
        val training = _draft.value.first
        val revisions = if (training == null) listOf("primary", "secondary").map(repository::captureRevision) else previous.revisions
        val requestGeneration = ++generation
        _draft.value = _draft.value.copy(busy = true, error = null, fitted = null, validation = null, saved = false,
            revisions = revisions, configurationKey = configurationKey)
        job = viewModelScope.launch {
            try {
                val pair = vm.captureAuxiliaryCalibrationPair(site, trackingSky) { onCaptured ->
                    if (mainCamera) vm.capturePointingReference(onCaptured = onCaptured)
                    else {
                        val chosen = requireNotNull(star)
                        require(centeringErrorDeg.isFinite() && centeringErrorDeg > 0)
                        val confirmation = CompletableDeferred<ObservationStamp>()
                        centerConfirmation = confirmation
                        _draft.value = _draft.value.copy(waitingForCenter = true)
                        val stamp = confirmation.await()
                        centerConfirmation = null
                        _draft.value = _draft.value.copy(waitingForCenter = false)
                        onCaptured(stamp)
                        val id = "centered-${chosen.hip}-${UUID.randomUUID()}"
                        OpticalObservation(id, OpticalIdentity("telescope", "axis", "primary"), stamp,
                            SkyDirection(chosen.raDeg, chosen.decDeg), null, null, centeringErrorDeg,
                            OpticalObservationKind.CENTERED_STAR, independentReferenceId = id)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(generation == requestGeneration && revisions.all(repository::isSameInstallation) &&
                    sourceId == vm.auxiliaryReferenceCameraId() && (!mainCamera || targetId == vm.primaryReferenceCameraId()))
                    { configurationChanged() }
                if (training == null) _draft.value = _draft.value.copy(first = pair)
                else {
                    val version = vm.pointingRepository.relationships.value.filter {
                        it.source == pair.camera.identity && it.target == pair.reference.identity
                    }.maxOfOrNull { it.version } ?: 0
                    val fitted = OpticalCalibrationFitter.fit(training, pair, previousVersion = version)
                    _draft.value = _draft.value.copy(validation = pair, fitted = fitted)
                }
            } catch (timeout: TimeoutCancellationException) { if (generation == requestGeneration) _draft.value = _draft.value.copy(error = vm.auxiliaryCalibrationFailure(timeout)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { if (generation == requestGeneration) _draft.value = _draft.value.copy(error = vm.auxiliaryCalibrationFailure(t)) }
            finally {
                if (generation == requestGeneration) {
                    centerConfirmation?.cancel(); centerConfirmation = null
                    _draft.value = _draft.value.copy(busy = false, waitingForCenter = false)
                }
            }
        }
    }

    fun confirmCentered(stamp: ObservationStamp) { centerConfirmation?.complete(stamp) }
    fun cancel() { job?.cancel(); centerConfirmation?.cancel() }
    fun clearValidation() { _draft.value = _draft.value.copy(fitted = null, validation = null, saved = false, error = null) }
    fun reset() { cancel(); generation++; centerConfirmation = null; _draft.value = AuxiliaryCalibrationDraft() }
    fun save(repository: PointingRepository, configurationChangedMessage: String) {
        val value = _draft.value
        if (value.busy || value.saved) return
        try {
            repository.saveValidated(requireNotNull(value.fitted), listOf(requireNotNull(value.first), requireNotNull(value.validation)),
                expectedRevisions = value.revisions)
            _draft.value = value.copy(saved = true, error = null)
        } catch (t: Throwable) { _draft.value = value.copy(error = if (value.revisions.any { !repository.isSameInstallation(it) })
            configurationChangedMessage else t.message) }
    }
}
