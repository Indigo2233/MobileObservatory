package com.indigo.mobileobservatory.sequence

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class SequenceEditorMode { Simple, Advanced }

data class SequenceSkyTarget(
    val name: String,
    val raHours: Double,
    val decDeg: Double,
    val positionAngleDeg: Double = 0.0
)

data class SequenceMosaicPanel(
    val number: Int,
    val row: Int,
    val column: Int,
    val raHours: Double,
    val decDeg: Double,
    val enabled: Boolean = true
)

data class SequenceMosaicPlan(
    val name: String,
    val centerRaHours: Double,
    val centerDecDeg: Double,
    val positionAngleDeg: Double,
    val rows: Int,
    val columns: Int,
    val overlapPercent: Int,
    val traversal: String = "SNAKE",
    val startCorner: String = "TOP_LEFT",
    val panels: List<SequenceMosaicPanel>,
    val rotateWithRotator: Boolean = false
)

data class SessionFrame(
    val name: String,
    val filter: String?,
    val exposureSeconds: Double,
    val hfr: Double?,
    val starCount: Int? = null
)

data class AutofocusRun(
    val atMillis: Long,
    val temperatureC: Double?,
    val filter: String?,
    val position: Int,
    val hfr: Double,
    val curve: List<Pair<Int, Double>>
)

interface SequenceHardware {
    suspend fun awaitExposureReady() = Unit

    suspend fun takeExposure(
        seconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int = 1,
        imageType: String = "LIGHT"
    ): SessionFrame
    suspend fun switchFilter(name: String, fallbackPosition: Int?) = switchFilter(name)
    suspend fun switchFilter(name: String)
    suspend fun cool(targetC: Double, durationMinutes: Double = 0.0)
    suspend fun warm(durationMinutes: Double = 0.0)
    suspend fun slew(raHours: Double, decDeg: Double)
    suspend fun center(raHours: Double, decDeg: Double)
    suspend fun plateSolve(): Pair<Double, Double>
    suspend fun syncMount(raHours: Double, decDeg: Double)
    suspend fun guide(enabled: Boolean, forceCalibration: Boolean = false)
    suspend fun dither(radiusPx: Double)
    suspend fun tracking(mode: Int)
    suspend fun goHome()
    suspend fun cover(open: Boolean)
    suspend fun dewHeater(on: Boolean)
    suspend fun usbLimit(value: Int)
    suspend fun flatLight(on: Boolean)
    suspend fun flatBrightness(value: Int)
    suspend fun moveFocuser(position: Int)
    suspend fun rotateTo(angleDeg: Double)
    fun isRotatorConnected(): Boolean
    suspend fun autofocus(destDir: File): AutofocusRun
    suspend fun waitUntil(epochMillis: Long)
    fun guidingLocked(): Boolean
    fun raHours(): Double?
    fun decDeg(): Double?
    fun focuserPosition(): Int = 0
}

class SequenceRuntime(
    private val templatesDir: File,
    private val sessionsDir: File,
    private val scope: CoroutineScope,
    private val hardware: SequenceHardware,
    private val world: SequenceWorld = SequenceWorld.System
) : SequenceDevicePort {
    private var sessionDir: File? = null
    private var runRoot: NinaNode? = null
    private val manifestMutex = Mutex()
    private val opticalPathMutex = Mutex()
    private var runSettings = SequenceSettings()

    private val _state = MutableStateFlow(SequenceRunState(SequencePhase.Idle))
    val state: StateFlow<SequenceRunState> = _state.asStateFlow()
    private val _mode = MutableStateFlow(SequenceEditorMode.Simple)
    val mode: StateFlow<SequenceEditorMode> = _mode.asStateFlow()
    private val _draft = MutableStateFlow(
        SimpleSequenceDraft(title = "Target", raHours = 0.0, decDegrees = 0.0, rows = listOf(
            SimpleExposureRow(filterName = null, exposureSeconds = 30.0, gain = -1, offset = -1, count = 1)
        ))
    )
    val draft: StateFlow<SimpleSequenceDraft> = _draft.asStateFlow()
    private val _document = MutableStateFlow<NinaNode?>(null)
    val document: StateFlow<NinaNode?> = _document.asStateFlow()
    private val _frames = MutableStateFlow<List<SessionFrame>>(emptyList())
    val frames: StateFlow<List<SessionFrame>> = _frames.asStateFlow()
    private val _autofocus = MutableStateFlow<List<AutofocusRun>>(emptyList())
    val autofocus: StateFlow<List<AutofocusRun>> = _autofocus.asStateFlow()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()
    private val history = SequenceHistory()
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val control = SequenceControl()
    private val engine = SequenceEngine(this, control, world) { next ->
        // Publish terminal state after the session manifest has been committed.
        if (next.phase == SequencePhase.Running || next.phase == SequencePhase.Paused) {
            _state.value = if (next.phase == SequencePhase.Running && control.isPaused) {
                _state.value.copy(phase = SequencePhase.Paused, framesDone = next.framesDone, nodeStatus = next.nodeStatus)
            } else next
        }
    }
    private var guidingWanted = false

    fun setMode(mode: SequenceEditorMode) {
        if (running()) return
        if (mode == SequenceEditorMode.Advanced && _document.value == null) {
            _document.value = emptyAdvancedSequence(_draft.value.title)
            history.clear()
            publishHistory()
            _generation.value = _generation.value + 1
        }
        _mode.value = mode
    }

    fun importSimpleDraft() {
        if (running()) return
        _document.value = _draft.value.toNinaSequence()
        _mode.value = SequenceEditorMode.Advanced
        history.clear()
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun editSequence(block: (NinaNode) -> Boolean) {
        if (_locked.value) return
        val root = ensureDocument()
        val before = root.toJson(indent = 0)
        if (block(root)) {
            history.record(before)
            publishHistory()
            _generation.value = _generation.value + 1
        }
    }

    fun undoEdit() {
        if (_locked.value) return
        val current = _document.value ?: return
        val previous = history.undo(current.toJson(indent = 0)) ?: return
        _document.value = parseNinaSequence(previous)
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun redoEdit() {
        if (_locked.value) return
        val current = _document.value ?: return
        val next = history.redo(current.toJson(indent = 0)) ?: return
        _document.value = parseNinaSequence(next)
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun setLocked(locked: Boolean) {
        if (running()) return
        _locked.value = locked
    }

    private fun publishHistory() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    fun updateDraft(draft: SimpleSequenceDraft) {
        if (running()) return
        _draft.value = draft
    }

    fun templateNames(): List<String> {
        templatesDir.mkdirs()
        return templatesDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    fun setTemplateNames(): List<String> = listSnippetNames(File(templatesDir, "templates"), ".template.json")

    fun savedTargetNames(): List<String> = listSnippetNames(File(templatesDir, "targets"), ".json")

    fun save(name: String) {
        templatesDir.mkdirs()
        val root = currentDocument()
        File(templatesDir, "${sequenceFileStem(name)}.json").writeSequenceJson(root.toJson())
        _generation.value = _generation.value + 1
    }

    fun exportJson(): String = currentDocument().toJson()

    fun suggestedFileName(): String {
        val root = currentDocument()
        val name = root.textField("Name") ?: _draft.value.title.ifBlank { "sequence" }
        return "${sequenceFileStem(name)}.json"
    }

    fun shareFile(): File {
        val shareDir = File(templatesDir, "shared").also { it.mkdirs() }
        val file = File(shareDir, suggestedFileName())
        file.writeSequenceJson(exportJson())
        return file
    }

    fun importJson(name: String?, json: String) {
        check(!running()) { "Sequence is running" }
        val root = parseNinaSequence(json)
        require(root.className == "SequenceRootContainer") { "Expected a NINA sequence root" }
        templatesDir.mkdirs()
        val stem = sequenceFileStem(
            name
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.', missingDelimiterValue = name)
                ?.takeIf { it.isNotBlank() }
                ?: root.textField("Name")
                ?: "sequence"
        )
        File(templatesDir, "$stem.json").writeSequenceJson(root.toJson())
        _document.value = root
        _mode.value = SequenceEditorMode.Advanced
        history.clear()
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun saveSetTemplate(id: String): Boolean {
        val node = findSequenceNode(currentDocument(), id) ?: return false
        if (sequenceStructural(node)) return false
        val name = sequenceFileStem(node.textField("Name") ?: node.className)
        val dir = File(templatesDir, "templates")
        dir.mkdirs()
        File(dir, "$name.template.json").writeSequenceJson(node.toJson())
        _generation.value = _generation.value + 1
        return true
    }

    fun saveTargetSnippet(id: String): Boolean {
        val node = findSequenceNode(currentDocument(), id) ?: return false
        if (node.className != "DeepSkyObjectContainer") return false
        val name = sequenceFileStem(dsoTargetName(node) ?: node.textField("Name") ?: "target")
        val dir = File(templatesDir, "targets")
        dir.mkdirs()
        File(dir, "$name.json").writeSequenceJson(node.toJson())
        _generation.value = _generation.value + 1
        return true
    }

    fun insertSetTemplate(parentId: String, name: String): Boolean {
        val file = File(File(templatesDir, "templates"), "$name.template.json")
        if (!file.isFile) return false
        var ok = false
        editSequence { root ->
            ok = insertSequenceSnippet(root, parentId, file.readText())
            ok
        }
        return ok
    }

    fun insertSavedTarget(parentId: String, name: String): Boolean {
        val file = File(File(templatesDir, "targets"), "$name.json")
        if (!file.isFile) return false
        var ok = false
        editSequence { root ->
            ok = insertSequenceSnippet(root, parentId, file.readText(), "Items")
            ok
        }
        return ok
    }

    private fun listSnippetNames(dir: File, suffix: String): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(suffix) }
            ?.map { file ->
                if (suffix == ".json") file.nameWithoutExtension else file.name.removeSuffix(suffix)
            }
            ?.sorted()
            ?: emptyList()
    }

    fun load(name: String) {
        if (running()) return
        val root = parseNinaSequence(File(templatesDir, "$name.json").readText())
        _document.value = root
        _mode.value = SequenceEditorMode.Advanced
        history.clear()
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun start(settings: SequenceSettings = SequenceSettings()) {
        if (running()) return
        val root = currentDocument()
        runRoot = root
        sessionDir = null
        val wasLocked = _locked.value
        control.reset()
        _state.value = SequenceRunState(SequencePhase.Running)
        _frames.value = emptyList()
        _autofocus.value = emptyList()
        guidingWanted = false
        runSettings = settings.copy(
            ditherPixels = if (_mode.value == SequenceEditorMode.Simple) {
                _draft.value.ditherPixels.coerceAtLeast(0.0)
            } else {
                settings.ditherPixels.coerceAtLeast(0.0)
            }
        )
        _locked.value = true
        scope.launch {
            try {
                sessionDir = withContext(Dispatchers.IO) {
                    Files.createDirectories(sessionsDir.toPath())
                    val prefix = sequenceFileStem(root.textField("Name") ?: "sequence")
                    Files.createTempDirectory(sessionsDir.toPath(), "$prefix-${System.currentTimeMillis()}-").toFile()
                }
                writeSession(root, SequencePhase.Running)
                val result = engine.runPrepared(root, runSettings).copy(framesDone = _frames.value.size)
                writeSession(root, result.phase, result.message)
                _state.value = result
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(phase = SequencePhase.Stopped)
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(phase = SequencePhase.Failed, framesDone = _frames.value.size, message = failure.message)
            } finally {
                runRoot = null
                _locked.value = wasLocked
            }
        }
    }

    /** Restore the last interrupted run for inspection. Device actions require a new Start. */
    suspend fun restoreInterruptedSession(): Boolean {
        if (_state.value.phase != SequencePhase.Idle || _generation.value != 0 || _document.value != null) return false
        val initialDraft = _draft.value
        val saved = withContext(Dispatchers.IO) {
            val latest = sessionsDir.listFiles()?.asSequence()?.filter { it.isDirectory }
                ?.map { File(it, "session.json") }?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
                ?: return@withContext null
            val json = JSONObject(latest.readText())
            if (json.optString("phase") !in setOf("Running", "Paused")) return@withContext null
            val root = parseNinaSequence(json.getJSONObject("sequence").toString())
            val frames = recoverSessionFrames(checkNotNull(latest.parentFile), json.getJSONArray("frames"))
            val focus = json.getJSONArray("autofocus").objects().map {
                AutofocusRun(it.getLong("atMillis"), it.optDouble("temperatureC").takeIf(Double::isFinite),
                    it.optionalString("filter"), it.getInt("position"), it.getDouble("hfr"),
                    it.getJSONArray("curve").objects().map { p -> p.getInt("position") to p.getDouble("hfr") })
            }
            Triple(root, frames, focus)
        } ?: return false
        if (_state.value.phase != SequencePhase.Idle || _generation.value != 0 || _document.value != null ||
            initialDraft !== _draft.value) return false
        _document.value = saved.first
        _mode.value = SequenceEditorMode.Advanced
        _frames.value = saved.second
        _autofocus.value = saved.third
        _generation.value++
        _state.value = SequenceRunState(SequencePhase.Interrupted, framesDone = saved.second.size)
        return true
    }

    fun stop() = control.stop()
    fun skip() = control.requestSkip()
    fun skipToEnd() = control.requestSkipToEnd()

    fun running(): Boolean = _state.value.phase == SequencePhase.Running || _state.value.phase == SequencePhase.Paused

    fun observingTarget(): Pair<Double, Double> =
        targetCoordinates() ?: (_draft.value.raHours to _draft.value.decDegrees)

    fun plannedFrameCount(): Int? {
        runRoot?.let { return it.plannedFrames() }
        return when (_mode.value) {
            SequenceEditorMode.Simple -> _draft.value.plannedFrames()
            SequenceEditorMode.Advanced -> _document.value?.plannedFrames()
        }
    }

    fun pause() {
        control.pause()
        publishPause()
    }

    internal fun pauseForMountStop() {
        control.pauseForMountStop()
        publishPause()
    }

    private fun publishPause() {
        if (_state.value.phase == SequencePhase.Running) {
            _state.value = _state.value.copy(phase = SequencePhase.Paused)
        }
    }

    fun resume() {
        control.resume()
        if (control.isPaused) return
        if (_state.value.phase == SequencePhase.Paused) {
            _state.value = _state.value.copy(phase = SequencePhase.Running, message = null)
        }
    }

    override suspend fun execute(instruction: NinaNode, className: String) {
        // These instructions share the optical path and must not change it during a frame.
        if (className !in opticalInstructions) {
            awaitDispatch(instruction)
            executeHardware(instruction, className)
            return
        }
        while (true) {
            control.checkpoint()
            opticalPathMutex.withLock {
                if (control.canDispatch(instruction)) {
                    currentCoroutineContext().ensureActive()
                    executeHardware(instruction, className)
                    return
                }
            }
        }
    }

    private suspend fun awaitDispatch(instruction: NinaNode) {
        do {
            control.checkpoint()
        } while (!control.canDispatch(instruction))
        currentCoroutineContext().ensureActive()
    }

    private suspend fun executeHardware(instruction: NinaNode, className: String) {
        val target = instructionCoordinates(instruction)
        when (className) {
            "TakeExposure" -> {
                val bin = (instruction.fields["Binning"] as? NinaValue.Obj)?.node
                val x = bin?.intField("X") ?: 1
                if (x < 1 || (bin?.intField("Y") ?: x) != x) throw DeviceUnavailable("Square binning required")
                val dir = sessionDir ?: sessionsDir
                if (guidingWanted && !hardware.guidingLocked()) throw GuideLost()
                hardware.awaitExposureReady()
                awaitDispatch(instruction)
                if (guidingWanted && !hardware.guidingLocked()) throw GuideLost()
                val frame = hardware.takeExposure(
                    seconds = expressionNumber(instruction, "ExposureTime") ?: 1.0,
                    gain = (expressionNumber(instruction, "Gain") ?: -1.0).toInt(),
                    offset = (expressionNumber(instruction, "Offset") ?: -1.0).toInt(),
                    destDir = dir,
                    binning = x,
                    imageType = instruction.textField("ImageType")?.ifBlank { "LIGHT" } ?: "LIGHT"
                )
                withContext(NonCancellable) {
                    _frames.value = _frames.value + frame
                    runRoot?.let { writeSession(it, SequencePhase.Running) }
                }
            }
            "SwitchFilter" -> {
                val oldFilter = (instruction.fields["Filter"] as? NinaValue.Obj)?.node
                val name = instruction.textField("ComboBoxText")?.takeIf(String::isNotBlank) ?: oldFilter?.textField("_name")
                hardware.switchFilter(name.orEmpty(), oldFilter?.intField("_position"))
            }
            "CoolCamera" -> hardware.cool(
                targetC = expressionNumber(instruction, "Temperature") ?: 0.0,
                durationMinutes = expressionNumber(instruction, "Duration") ?: 0.0
            )
            "WarmCamera" -> hardware.warm(
                durationMinutes = expressionNumber(instruction, "Duration") ?: 0.0
            )
            "SlewScopeToRaDec" -> {
                val coordinates = target ?: throw DeviceUnavailable("target coordinates")
                hardware.slew(coordinates.first, coordinates.second)
            }
            "SlewScopeToAltAz" -> {
                val lat = world.observerLatitudeDeg() ?: throw DeviceUnavailable("site")
                val lon = world.observerLongitudeDeg() ?: throw DeviceUnavailable("site")
                val alt = expressionNumber(instruction, "Alt") ?: instruction.doubleField("Alt") ?: 45.0
                val az = expressionNumber(instruction, "Az") ?: instruction.doubleField("Az") ?: 0.0
                val equatorial = SequenceEphemeris.altAzToEquatorialHours(
                    alt,
                    az,
                    com.indigo.mobileobservatory.astro.ObserverSite(lat, lon),
                    java.time.Instant.ofEpochMilli(world.nowMillis())
                )
                hardware.slew(equatorial.first, equatorial.second)
            }
            "Center" -> {
                val coordinates = target ?: throw DeviceUnavailable("target coordinates")
                hardware.center(coordinates.first, coordinates.second)
            }
            "CenterAndRotate" -> {
                val coordinates = target ?: throw DeviceUnavailable("target coordinates")
                hardware.center(coordinates.first, coordinates.second)
                rotateIfAvailable(instructionPositionAngle(instruction))
            }
            "SolveAndSync" -> {
                val solved = hardware.plateSolve()
                hardware.syncMount(solved.first, solved.second)
            }
            "SolveAndRotate" -> {
                hardware.plateSolve()
                rotateIfAvailable(instructionPositionAngle(instruction))
            }
            "MoveRotatorMechanical" -> rotateIfAvailable(
                expressionNumber(instruction, "MechanicalAngle") ?: 0.0
            )
            "StartGuiding" -> {
                val forceCalibration = (instruction.fields["ForceCalibration"] as? NinaValue.Bool)?.value == true
                hardware.guide(true, forceCalibration)
                guidingWanted = true
            }
            "StopGuiding" -> {
                hardware.guide(false)
                guidingWanted = false
            }
            "Dither" -> hardware.dither(runSettings.ditherPixels)
            "SetTracking" -> hardware.tracking(instruction.intField("TrackingMode") ?: 0)
            "FindHome" -> hardware.goHome()
            "OpenCover" -> hardware.cover(true)
            "CloseCover" -> hardware.cover(false)
            "DewHeater" -> hardware.dewHeater((instruction.fields["OnOff"] as? NinaValue.Bool)?.value == true)
            "SetUSBLimit" -> hardware.usbLimit(instruction.intField("USBLimit") ?: 0)
            "ToggleLight" -> hardware.flatLight((instruction.fields["OnOff"] as? NinaValue.Bool)?.value == true)
            "SetBrightness" -> hardware.flatBrightness(
                (expressionNumber(instruction, "Brightness") ?: 0.0).toInt()
            )
            "MoveFocuserAbsolute" -> hardware.moveFocuser(
                (expressionNumber(instruction, "Position") ?: 0.0).toInt()
            )
            "MoveFocuserRelative" -> hardware.moveFocuser(
                hardware.focuserPosition() + (expressionNumber(instruction, "RelativePosition") ?: 0.0).toInt()
            )
            "RunAutofocus" -> {
                val run = hardware.autofocus(File(sessionDir ?: sessionsDir, "autofocus"))
                _autofocus.value = _autofocus.value + run
                runRoot?.let { writeSession(it, SequencePhase.Running) }
            }
            "WaitForTime" -> {
                val deadline = nextClockTimeMillis(
                    instruction,
                    world.nowMillis(),
                    world.observerLatitudeDeg(),
                    world.observerLongitudeDeg(),
                    target?.first
                ) ?: throw DeviceUnavailable("time provider")
                hardware.waitUntil(deadline)
            }
            "WaitForTimeSpan" -> {
                val seconds = (expressionNumber(instruction, "Time") ?: 60.0).coerceAtLeast(0.0)
                kotlinx.coroutines.delay((seconds * 1000.0).toLong())
            }
            "WaitForAltitude", "WaitUntilAboveHorizon" -> waitForAltitude(altitudeOffset(instruction), target)
            "WaitForSunAltitude" -> waitUntilCompared(
                { world.sunAltitudeDeg() },
                altitudeOffset(instruction),
                altitudeComparator(instruction),
                "sun"
            )
            "WaitForMoonAltitude" -> waitUntilCompared(
                { world.moonAltitudeDeg() },
                altitudeOffset(instruction),
                altitudeComparator(instruction),
                "moon"
            )
            "Annotation" -> Unit
            else -> throw IllegalStateException(className)
        }
    }

    fun addTarget(
        name: String,
        raHours: Double,
        decDeg: Double,
        positionAngleDeg: Double = 0.0,
        destination: SequenceEditorMode = _mode.value
    ) {
        if (running()) return
        if (destination == SequenceEditorMode.Simple) {
            _draft.value = _draft.value.copy(
                title = name,
                raHours = raHours,
                decDegrees = decDeg,
                positionAngleDeg = positionAngleDeg,
                targets = emptyList()
            )
            _mode.value = SequenceEditorMode.Simple
            return
        }
        editSequence { root ->
            val area = root.childItems().firstOrNull { it.className == "TargetAreaContainer" }
                ?: return@editSequence false
            appendChild(area, deepSkyNode(name, raHours, decDeg, positionAngleDeg))
            true
        }
    }

    fun addMosaic(
        plan: SequenceMosaicPlan,
        destination: SequenceEditorMode = _mode.value
    ) {
        val panelTargets = plan.panels.map { panel ->
            SimpleSequenceTarget(
                name = "${plan.name} · P${panel.number}",
                raHours = panel.raHours,
                decDegrees = panel.decDeg,
                positionAngleDeg = plan.positionAngleDeg,
                enabled = panel.enabled,
                panelNumber = panel.number,
                panelRow = panel.row,
                panelColumn = panel.column,
                mosaicRows = plan.rows,
                mosaicColumns = plan.columns,
                overlapPercent = plan.overlapPercent,
                mosaicPlanName = plan.name,
                mosaicTraversal = plan.traversal,
                mosaicStartCorner = plan.startCorner,
                mosaicCenterRaHours = plan.centerRaHours,
                mosaicCenterDecDeg = plan.centerDecDeg
            )
        }
        val mosaicDraft = _draft.value.copy(
            title = plan.name,
            raHours = plan.centerRaHours,
            decDegrees = plan.centerDecDeg,
            positionAngleDeg = plan.positionAngleDeg,
            slewBefore = true,
            centerBefore = true,
            rotateBefore = plan.rotateWithRotator,
            targets = panelTargets
        )
        if (running()) return
        if (destination == SequenceEditorMode.Simple) {
            _draft.value = mosaicDraft
            _mode.value = SequenceEditorMode.Simple
            return
        }
        val generatedTargets = mosaicDraft.toNinaSequence()
            .childItems()
            .first { it.className == "TargetAreaContainer" }
            .childItems()
        editSequence { root ->
            val area = root.childItems().firstOrNull { it.className == "TargetAreaContainer" }
                ?: return@editSequence false
            val areaId = area.id ?: return@editSequence false
            area.childItems()
                .filter { it.textField("MosaicPlanName") == plan.name }
                .mapNotNull { it.id }
                .forEach { deleteSequenceNode(root, it) }
            generatedTargets.fold(false) { changed, target ->
                insertSequenceSnippet(root, areaId, target.toJson(), "Items") || changed
            }
        }
    }

    fun mosaicPlan(): SequenceMosaicPlan? {
        if (_mode.value == SequenceEditorMode.Simple) {
            val draft = _draft.value
            val panels = draft.targets.filter { it.panelNumber != null }
            val first = panels.firstOrNull() ?: return null
            return SequenceMosaicPlan(
                name = first.mosaicPlanName ?: draft.title,
                centerRaHours = first.mosaicCenterRaHours ?: draft.raHours,
                centerDecDeg = first.mosaicCenterDecDeg ?: draft.decDegrees,
                positionAngleDeg = first.positionAngleDeg,
                rows = first.mosaicRows ?: 1,
                columns = first.mosaicColumns ?: panels.size,
                overlapPercent = first.overlapPercent ?: 0,
                traversal = first.mosaicTraversal ?: "SNAKE",
                startCorner = first.mosaicStartCorner ?: "TOP_LEFT",
                panels = panels.map { panel ->
                    SequenceMosaicPanel(
                        number = panel.panelNumber ?: 1,
                        row = panel.panelRow ?: 1,
                        column = panel.panelColumn ?: 1,
                        raHours = panel.raHours,
                        decDeg = panel.decDegrees,
                        enabled = panel.enabled
                    )
                },
                rotateWithRotator = draft.rotateBefore
            )
        }
        val targets = _document.value
            ?.childItems()
            ?.firstOrNull { it.className == "TargetAreaContainer" }
            ?.childItems()
            ?.filter { it.textField("MosaicPlanName") != null }
            .orEmpty()
        val first = targets.firstOrNull() ?: return null
        return SequenceMosaicPlan(
            name = first.textField("MosaicPlanName") ?: dsoTargetName(first).orEmpty(),
            centerRaHours = first.doubleField("MosaicCenterRaHours") ?: dsoRaHours(first) ?: 0.0,
            centerDecDeg = first.doubleField("MosaicCenterDecDeg") ?: dsoDecDegrees(first) ?: 0.0,
            positionAngleDeg = dsoPositionAngle(first) ?: 0.0,
            rows = first.intField("MosaicRows") ?: 1,
            columns = first.intField("MosaicColumns") ?: targets.size,
            overlapPercent = first.intField("MosaicOverlapPercent") ?: 0,
            traversal = first.textField("MosaicTraversal") ?: "SNAKE",
            startCorner = first.textField("MosaicStartCorner") ?: "TOP_LEFT",
            panels = targets.map { target ->
                SequenceMosaicPanel(
                    number = target.intField("MosaicPanelNumber") ?: 1,
                    row = target.intField("MosaicRow") ?: 1,
                    column = target.intField("MosaicColumn") ?: 1,
                    raHours = dsoRaHours(target) ?: 0.0,
                    decDeg = dsoDecDegrees(target) ?: 0.0,
                    enabled = !sequenceNodeDisabled(target)
                )
            },
            rotateWithRotator = targets.any { target ->
                target.childItems().any { it.className == "CenterAndRotate" }
            }
        )
    }

    fun applySkyTarget(id: String, sky: SequenceSkyTarget) {
        editSequence {
            applyDsoSkyTarget(it, id, sky.name, sky.raHours, sky.decDeg, sky.positionAngleDeg)
        }
    }

    fun addSmartExposure(filterName: String, seconds: Double, count: Int, ditherEvery: Int) {
        if (running()) return
        val root = ensureDocument()
        val target = root.childItems().getOrNull(1)?.childItems()?.lastOrNull {
            it.className == "DeepSkyObjectContainer"
        } ?: return
        val loop = sequentialLoop(filterName.ifBlank { "Smart" }, count, listOf(
            filterName.takeIf { it.isNotBlank() }?.let { switchNode(it) },
            exposureNode(seconds)
        ).filterNotNull())
        val trigger = triggerNode("Guider.DitherAfterExposures", linkedMapOf<String, NinaValue>().also { fields ->
            putExpression(fields, "AfterExposures", ditherEvery.toDouble())
        })
        trigger.fields["Parent"] = NinaValue.Ref(checkNotNull(loop.id))
        loop.fields["Triggers"] = singleCollection(
            loop.fields["Triggers"],
            "NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer",
            trigger
        )
        appendChild(target, loop)
        _document.value = root
    }

    fun addTrigger(className: String) {
        if (running()) return
        val root = ensureDocument()
        val target = root.childItems().getOrNull(1)?.childItems()?.lastOrNull {
            it.className == "DeepSkyObjectContainer"
        } ?: root
        val trigger = triggerNode(className, LinkedHashMap<String, NinaValue>().also { fields ->
            putExpression(fields, "AfterExposures", 3.0)
            putExpression(fields, "Amount", 30.0)
        })
        trigger.fields["Parent"] = NinaValue.Ref(target.id ?: return)
        val existing = target.fields["Triggers"] as? NinaValue.Collection
        target.fields["Triggers"] = NinaValue.Collection(
            type = existing?.type.orEmpty(),
            id = existing?.id,
            values = (existing?.values ?: emptyList()) + NinaValue.Obj(trigger)
        )
        _document.value = root
    }

    override suspend fun measurePointing(): Pair<Double, Double>? = try {
        hardware.plateSolve()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun rotateIfAvailable(angleDeg: Double) {
        if (!hardware.isRotatorConnected()) return
        try {
            hardware.rotateTo(angleDeg)
        } catch (error: DeviceUnavailable) {
            if (hardware.isRotatorConnected()) throw error
        }
    }

    private fun instructionPositionAngle(instruction: NinaNode): Double {
        val inherited = (instruction.fields["Inherited"] as? NinaValue.Bool)?.value == true
        if (!inherited) {
            expressionNumber(instruction, "PositionAngle")?.let { return it }
            instruction.doubleField("PositionAngle")?.let { return it }
        }
        val dso = targetContainer(instruction)
        return dsoPositionAngle(dso ?: instruction) ?: expressionNumber(instruction, "PositionAngle") ?: 0.0
    }

    private suspend fun waitUntilCompared(
        sample: () -> Double?,
        offset: Double,
        comparator: Int,
        missing: String
    ) {
        val deadline = world.nowMillis() + 18 * 60 * 60_000L
        while (world.nowMillis() < deadline) {
            val value = sample() ?: throw DeviceUnavailable(missing)
            if (compareOrdered(value, offset, comparator)) return
            kotlinx.coroutines.delay(1_000)
        }
        throw IllegalStateException(missing)
    }

    private suspend fun waitForAltitude(offset: Double, target: Pair<Double, Double>?) {
        val deadline = world.nowMillis() + 12 * 60 * 60_000L
        while (world.nowMillis() < deadline) {
            val altitude = world.altitudeDeg(target)
            if (altitude == null) throw DeviceUnavailable("target altitude")
            if (altitude >= offset) return
            kotlinx.coroutines.delay(1_000)
        }
        throw IllegalStateException("altitude")
    }

    private fun ensureDocument(): NinaNode {
        val current = _document.value ?: emptyAdvancedSequence(_draft.value.title)
        _document.value = current
        _mode.value = SequenceEditorMode.Advanced
        return current
    }

    private fun currentDocument(): NinaNode = when (_mode.value) {
        SequenceEditorMode.Simple -> _draft.value.toNinaSequence()
        SequenceEditorMode.Advanced -> _document.value ?: _draft.value.toNinaSequence()
    }

    private fun instructionCoordinates(node: NinaNode): Pair<Double, Double>? {
        val inherited = (node.fields["Inherited"] as? NinaValue.Bool)?.value == true
        val coordinates = (node.fields["Coordinates"] as? NinaValue.Obj)?.node
        val target = when {
            inherited -> targetCoordinates(node)
            coordinates != null -> coordinateRaHours(coordinates)?.let { ra -> coordinateDecDegrees(coordinates)?.let { ra to it } }
            node.fields.containsKey("RAHours") || node.fields.containsKey("DecDegrees") ->
                node.doubleField("RAHours")?.let { ra -> node.doubleField("DecDegrees")?.let { ra to it } }
            else -> targetCoordinates(node)
        }
        return target?.takeIf { (ra, dec) -> ra in 0.0..24.0 && dec in -90.0..90.0 }
    }

    private fun targetCoordinates(instruction: NinaNode? = null): Pair<Double, Double>? {
        val target = targetContainer(instruction) ?: return null
        return (dsoRaHours(target) ?: return null) to (dsoDecDegrees(target) ?: return null)
    }

    private fun targetContainer(instruction: NinaNode?): NinaNode? {
        if (instruction != null && runRoot != null) engine.targetContainer(instruction)?.let { return it }
        val root = runRoot ?: _document.value ?: return null
        val targets = root.childItems().firstOrNull { it.className == "TargetAreaContainer" }
            ?.childItems()?.filter { it.className == "DeepSkyObjectContainer" && !sequenceNodeDisabled(it) }.orEmpty()
        val id = instruction?.id ?: if (instruction == null) _state.value.currentNodeId else null
        return id?.let { targets.firstOrNull { target -> findSequenceNode(target, it) != null } }
            ?: targets.singleOrNull()
    }

    private suspend fun writeSession(root: NinaNode, phase: SequencePhase, message: String? = null) = manifestMutex.withLock {
        val dir = sessionDir ?: return@withLock
        val savedFrames = _frames.value
        val savedFocus = _autofocus.value
        try {
            withContext(Dispatchers.IO) {
                writeManifest(dir, root, savedFrames, savedFocus, phase, message)
            }
        } catch (error: IOException) {
            throw SequenceStorageFailure(error)
        }
    }

    private fun writeManifest(dir: File, root: NinaNode, savedFrames: List<SessionFrame>,
                              savedFocus: List<AutofocusRun>, phase: SequencePhase, message: String?) {
        val frames = JSONArray()
        savedFrames.forEach { frame ->
            frames.put(JSONObject()
                .put("name", frame.name)
                .put("filter", frame.filter ?: JSONObject.NULL)
                .put("exposureSeconds", frame.exposureSeconds)
                .put("hfr", frame.hfr ?: JSONObject.NULL)
                .put("starCount", frame.starCount ?: JSONObject.NULL))
        }
        val focus = JSONArray()
        savedFocus.forEach { run ->
            val curve = JSONArray()
            run.curve.forEach { (position, hfr) ->
                curve.put(JSONObject().put("position", position).put("hfr", hfr))
            }
            focus.put(JSONObject()
                .put("atMillis", run.atMillis)
                .put("temperatureC", run.temperatureC ?: JSONObject.NULL)
                .put("filter", run.filter ?: JSONObject.NULL)
                .put("position", run.position)
                .put("hfr", run.hfr)
                .put("curve", curve))
        }
        File(dir, "session.json").writeSequenceJson(JSONObject()
            .put("phase", phase.name)
            .put("message", message)
            .put("sequence", JSONObject(root.toJson()))
            .put("frames", frames)
            .put("autofocus", focus)
            .toString(2))
    }

    private fun deepSkyNode(
        name: String,
        raHours: Double,
        decDeg: Double,
        positionAngleDeg: Double = 0.0
    ): NinaNode {
        val ra = splitSexagesimal(raHours.coerceIn(0.0, 24.0))
        val decAbs = splitSexagesimal(kotlin.math.abs(decDeg))
        val signedDegrees = if (decDeg < 0) -decAbs.first else decAbs.first
        val coordinates = fresh(
            "NINA.Astrometry.InputCoordinates, NINA.Astrometry",
            linkedMapOf(
                "RAHours" to NinaValue.Num(ra.first.toDouble(), true),
                "RAMinutes" to NinaValue.Num(ra.second.toDouble(), true),
                "RASeconds" to NinaValue.Num(ra.third, integral = ra.third % 1.0 == 0.0),
                "NegativeDec" to NinaValue.Bool(decDeg < 0),
                "DecDegrees" to NinaValue.Num(signedDegrees.toDouble(), true),
                "DecMinutes" to NinaValue.Num(decAbs.second.toDouble(), true),
                "DecSeconds" to NinaValue.Num(decAbs.third, integral = decAbs.third % 1.0 == 0.0)
            )
        )
        val input = fresh(
            "NINA.Astrometry.InputTarget, NINA.Astrometry",
            linkedMapOf(
                "TargetName" to NinaValue.Text(name),
                "PositionAngle" to NinaValue.Num(
                    positionAngleDeg,
                    integral = positionAngleDeg % 1.0 == 0.0
                ),
                "InputCoordinates" to NinaValue.Obj(coordinates)
            )
        )
        val target = fresh(
            "NINA.Sequencer.Container.DeepSkyObjectContainer, NINA.Sequencer",
            linkedMapOf(
                "Name" to NinaValue.Text(name),
                "IsExpanded" to NinaValue.Bool(true),
                "Target" to NinaValue.Obj(input)
            )
        )
        target.fields["Conditions"] = NinaValue.Collection(observableConditions(), nextId(), emptyList())
        target.fields["Triggers"] = NinaValue.Collection(observableTriggers(), nextId(), emptyList())
        target.fields["Items"] = NinaValue.Collection(observableItems(), nextId(), emptyList())
        return target
    }

    private fun sequentialLoop(name: String, count: Int, steps: List<NinaNode>): NinaNode {
        val loop = fresh("NINA.Sequencer.Container.SequentialContainer, NINA.Sequencer", linkedMapOf(
            "Name" to NinaValue.Text(name),
            "IsExpanded" to NinaValue.Bool(true)
        ))
        val condition = fresh("NINA.Sequencer.Conditions.LoopCondition, NINA.Sequencer", linkedMapOf(
            "CompletedIterations" to NinaValue.Num(0.0, true),
            "Iterations" to NinaValue.Num(count.toDouble(), true),
            "IterationsDefinition" to NinaValue.Text(count.toString())
        ))
        condition.fields["Parent"] = NinaValue.Ref(checkNotNull(loop.id))
        loop.fields["Conditions"] = singleCollection(
            null,
            "NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer",
            condition
        )
        loop.fields["Items"] = NinaValue.Collection(
            type = observableItems(),
            id = nextId(),
            values = steps.map { step ->
                step.fields["Parent"] = NinaValue.Ref(checkNotNull(loop.id))
                NinaValue.Obj(step)
            }
        )
        loop.fields["Triggers"] = NinaValue.Collection(observableTriggers(), nextId(), emptyList())
        return loop
    }

    private fun switchNode(filterName: String): NinaNode = fresh(
        "NINA.Sequencer.SequenceItem.FilterWheel.SwitchFilter, NINA.Sequencer",
        linkedMapOf("ComboBoxText" to NinaValue.Text(filterName))
    )

    private fun exposureNode(seconds: Double): NinaNode {
        val fields = LinkedHashMap<String, NinaValue>()
        putExpression(fields, "ExposureTime", seconds)
        putExpression(fields, "Gain", -1.0)
        putExpression(fields, "Offset", -1.0)
        fields["ImageType"] = NinaValue.Text("LIGHT")
        return fresh("NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA.Sequencer", fields)
    }

    private fun triggerNode(className: String, fields: LinkedHashMap<String, NinaValue>): NinaNode =
        fresh("NINA.Sequencer.Trigger.$className, NINA.Sequencer", fields)

    private fun appendChild(parent: NinaNode, child: NinaNode) {
        val parentId = parent.id ?: return
        child.fields["Parent"] = NinaValue.Ref(parentId)
        val existing = parent.fields["Items"] as? NinaValue.Collection
        parent.fields["Items"] = NinaValue.Collection(
            type = existing?.type ?: observableItems(),
            id = existing?.id,
            values = (existing?.values ?: emptyList()) + NinaValue.Obj(child)
        )
    }

    private fun singleCollection(
        existing: NinaValue?,
        elementType: String,
        node: NinaNode
    ): NinaValue.Collection {
        val previous = existing as? NinaValue.Collection
        return NinaValue.Collection(
            type = previous?.type ?: "System.Collections.ObjectModel.ObservableCollection`1[[$elementType]], System.ObjectModel",
            id = previous?.id ?: nextId(),
            values = (previous?.values ?: emptyList()) + NinaValue.Obj(node)
        )
    }

    private fun fresh(type: String, fields: LinkedHashMap<String, NinaValue>): NinaNode {
        val merged = LinkedHashMap(fields)
        merged.putIfAbsent("ErrorBehavior", NinaValue.Num(0.0, true))
        merged.putIfAbsent("Attempts", NinaValue.Num(1.0, true))
        return NinaNode(type, nextId(), merged)
    }

    private fun nextId(): String = "s${System.nanoTime()}"

    private fun observableItems(): String =
        "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel"

    private fun observableTriggers(): String =
        "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer]], System.ObjectModel"

    private fun observableConditions(): String =
        "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer]], System.ObjectModel"
}

private val opticalInstructions = setOf(
    "TakeExposure", "SwitchFilter", "RunAutofocus", "MoveFocuserAbsolute", "MoveFocuserRelative",
    "SlewScopeToRaDec", "SlewScopeToAltAz", "Center", "CenterAndRotate", "SolveAndSync",
    "SolveAndRotate", "MoveRotatorMechanical", "FindHome", "OpenCover", "CloseCover",
    "ToggleLight", "SetBrightness", "Dither", "SetTracking", "StartGuiding", "StopGuiding"
)

private fun File.writeSequenceJson(json: String) {
    val temporary = Files.createTempFile(requireNotNull(parentFile).toPath(), ".sequence-", ".tmp")
    try {
        temporary.toFile().writeText(json)
        Files.move(temporary, toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun writeSequenceFits(dir: File, stem: String, write: (File) -> Unit): File {
    try {
        Files.createDirectories(dir.toPath())
        val temporary = Files.createTempFile(dir.toPath(), "${sequenceFileStem(stem)}-", ".partial").toFile()
        try {
            write(temporary)
            val output = File(dir, "${temporary.nameWithoutExtension}.fits")
            Files.move(temporary.toPath(), output.toPath())
            return output
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    } catch (failure: IOException) {
        throw SequenceStorageFailure(failure)
    }
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)

private fun recoverSessionFrames(directory: File, manifest: JSONArray): List<SessionFrame> {
    val files = directory.listFiles { file -> file.isFile && file.extension == "fits" }
        ?: throw IOException("Cannot read session directory $directory")
    val byName = files.associateBy { it.name }.toMutableMap()
    val frames = manifest.objects().mapNotNullTo(mutableListOf()) {
        val name = it.getString("name")
        if (byName.remove(name) == null) return@mapNotNullTo null
        SessionFrame(name, it.optionalString("filter"), it.getDouble("exposureSeconds"),
            it.optDouble("hfr").takeIf(Double::isFinite), it.optInt("starCount", -1).takeIf { n -> n >= 0 })
    }
    // A process can exit between renaming the completed FITS and replacing session.json.
    for (file in byName.values.sortedBy { it.lastModified() }) {
        val header = com.indigo.mobileobservatory.astrometry.FitsHeaderReader.read(file)
        val seconds = header["EXPOSURE"]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: continue
        frames += SessionFrame(file.name, header["FILTER"], seconds, hfr = null)
    }
    return frames
}
