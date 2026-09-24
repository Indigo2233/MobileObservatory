package com.indigo.mobileobservatory.sequence

import java.io.File
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
    suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File): SessionFrame
    suspend fun switchFilter(name: String)
    suspend fun cool(targetC: Double)
    suspend fun warm()
    suspend fun slew(raHours: Double, decDeg: Double)
    suspend fun center(raHours: Double, decDeg: Double)
    suspend fun guide(enabled: Boolean)
    suspend fun dither(radiusPx: Double)
    suspend fun tracking(enabled: Boolean)
    suspend fun goHome()
    suspend fun cover(open: Boolean)
    suspend fun moveFocuser(position: Int)
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
    private var guidingWanted = false

    private val _state = MutableStateFlow(SequenceRunState(SequencePhase.Idle))
    val state: StateFlow<SequenceRunState> = _state.asStateFlow()
    private val _mode = MutableStateFlow(SequenceEditorMode.Simple)
    val mode: StateFlow<SequenceEditorMode> = _mode.asStateFlow()
    private val _draft = MutableStateFlow(
        SimpleSequenceDraft(title = "Target", raHours = 0.0, decDegrees = 0.0, rows = listOf(
            SimpleExposureRow(filterName = null, exposureSeconds = 30.0, gain = 0, offset = 0, count = 1)
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

    private val engine = SequenceEngine(this, world = world) { next ->
        _state.value = next
    }

    val control: SequenceControl get() = engine.control

    fun setMode(mode: SequenceEditorMode) {
        if (mode == SequenceEditorMode.Advanced && _document.value == null) {
            _document.value = emptyAdvancedSequence(_draft.value.title)
            history.clear()
            publishHistory()
            _generation.value = _generation.value + 1
        }
        _mode.value = mode
    }

    fun importSimpleDraft() {
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
        _locked.value = locked
    }

    private fun publishHistory() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    fun updateDraft(draft: SimpleSequenceDraft) {
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
        File(templatesDir, "${sequenceFileStem(name)}.json").writeText(root.toJson())
        _generation.value = _generation.value + 1
    }

    fun saveSetTemplate(id: String): Boolean {
        val node = findSequenceNode(currentDocument(), id) ?: return false
        if (sequenceStructural(node)) return false
        val name = sequenceFileStem(node.textField("Name") ?: node.className)
        val dir = File(templatesDir, "templates")
        dir.mkdirs()
        File(dir, "$name.template.json").writeText(node.toJson())
        _generation.value = _generation.value + 1
        return true
    }

    fun saveTargetSnippet(id: String): Boolean {
        val node = findSequenceNode(currentDocument(), id) ?: return false
        if (node.className != "DeepSkyObjectContainer") return false
        val name = sequenceFileStem(dsoTargetName(node) ?: node.textField("Name") ?: "target")
        val dir = File(templatesDir, "targets")
        dir.mkdirs()
        File(dir, "$name.json").writeText(node.toJson())
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
        val root = parseNinaSequence(File(templatesDir, "$name.json").readText())
        _document.value = root
        _mode.value = SequenceEditorMode.Advanced
        history.clear()
        publishHistory()
        _generation.value = _generation.value + 1
    }

    fun start() {
        if (_state.value.phase == SequencePhase.Running || _state.value.phase == SequencePhase.Paused) return
        val root = currentDocument()
        _document.value = root
        sessionDir = File(sessionsDir, "${root.textField("Name") ?: "sequence"}-${System.currentTimeMillis()}").also {
            it.mkdirs()
        }
        _frames.value = emptyList()
        _autofocus.value = emptyList()
        guidingWanted = false
        _locked.value = true
        scope.launch {
            try {
                val result = engine.run(
                    root,
                    SequenceSettings(ditherPixels = _draft.value.ditherPixels)
                )
                _state.value = result
                writeSession(root)
            } finally {
                _locked.value = false
            }
        }
    }

    fun stop() = control.stop()
    fun skip() = control.requestSkip()
    fun skipToEnd() = control.requestSkipToEnd()

    fun running(): Boolean = _state.value.phase == SequencePhase.Running || _state.value.phase == SequencePhase.Paused

    fun observingTarget(): Pair<Double, Double> =
        targetCoordinates() ?: (_draft.value.raHours to _draft.value.decDegrees)

    fun pause() {
        control.pause()
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
        val target = targetCoordinates()
        when (className) {
            "TakeExposure" -> {
                if (guidingWanted && !hardware.guidingLocked()) throw GuideLost()
                val dir = sessionDir ?: sessionsDir
                val frame = hardware.takeExposure(
                    seconds = expressionNumber(instruction, "ExposureTime") ?: 1.0,
                    gain = (expressionNumber(instruction, "Gain") ?: -1.0).toInt().let { if (it < 0) 0 else it },
                    offset = (expressionNumber(instruction, "Offset") ?: -1.0).toInt().let { if (it < 0) 0 else it },
                    destDir = dir
                )
                _frames.value = _frames.value + frame
                writeSession(currentDocument())
            }
            "SwitchFilter" -> hardware.switchFilter(instruction.textField("ComboBoxText").orEmpty())
            "CoolCamera" -> hardware.cool(expressionNumber(instruction, "Temperature") ?: 0.0)
            "WarmCamera" -> hardware.warm()
            "SlewScopeToRaDec" -> {
                val inherited = (instruction.fields["Inherited"] as? NinaValue.Bool)?.value == true
                val ra = if (inherited) target?.first else instruction.doubleField("RAHours") ?: target?.first
                val dec = if (inherited) target?.second else instruction.doubleField("DecDegrees") ?: target?.second
                hardware.slew(ra ?: hardware.raHours() ?: 0.0, dec ?: hardware.decDeg() ?: 0.0)
            }
            "Center", "CenterAndRotate" -> hardware.center(target?.first ?: 0.0, target?.second ?: 0.0)
            "StartGuiding" -> {
                guidingWanted = true
                hardware.guide(true)
            }
            "StopGuiding" -> {
                guidingWanted = false
                hardware.guide(false)
            }
            "Dither" -> hardware.dither(_draft.value.ditherPixels)
            "SetTracking" -> hardware.tracking((instruction.intField("TrackingMode") ?: 0) != 5)
            "FindHome" -> hardware.goHome()
            "OpenCover" -> hardware.cover(true)
            "CloseCover" -> hardware.cover(false)
            "MoveFocuserAbsolute" -> hardware.moveFocuser(
                (expressionNumber(instruction, "Position") ?: 0.0).toInt()
            )
            "MoveFocuserRelative" -> hardware.moveFocuser(
                hardware.focuserPosition() + (expressionNumber(instruction, "RelativePosition") ?: 0.0).toInt()
            )
            "RunAutofocus" -> {
                val run = hardware.autofocus(sessionDir ?: sessionsDir)
                _autofocus.value = _autofocus.value + run
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
            "WaitForAltitude", "WaitUntilAboveHorizon" -> waitForAltitude(altitudeOffset(instruction))
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
        positionAngleDeg: Double = 0.0
    ) {
        if (_mode.value == SequenceEditorMode.Simple) {
            _draft.value = _draft.value.copy(
                title = name,
                raHours = raHours,
                decDegrees = decDeg,
                positionAngleDeg = positionAngleDeg
            )
            return
        }
        editSequence { root ->
            val area = root.childItems().firstOrNull { it.className == "TargetAreaContainer" }
                ?: return@editSequence false
            appendChild(area, deepSkyNode(name, raHours, decDeg, positionAngleDeg))
            true
        }
    }

    fun applySkyTarget(id: String, sky: SequenceSkyTarget) {
        editSequence {
            applyDsoSkyTarget(it, id, sky.name, sky.raHours, sky.decDeg, sky.positionAngleDeg)
        }
    }

    fun addSmartExposure(filterName: String, seconds: Double, count: Int, ditherEvery: Int) {
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

    private suspend fun waitForAltitude(offset: Double) {
        val deadline = world.nowMillis() + 12 * 60 * 60_000L
        while (world.nowMillis() < deadline) {
            val altitude = world.altitudeDeg()
            if (altitude == null || altitude >= offset) return
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

    private fun targetCoordinates(): Pair<Double, Double>? {
        val root = _document.value ?: return null
        val target = root.childItems().getOrNull(1)?.childItems()?.firstOrNull {
            it.className == "DeepSkyObjectContainer"
        } ?: return null
        val input = ((target.fields["Target"] as? NinaValue.Obj)?.node
            ?.fields?.get("InputCoordinates") as? NinaValue.Obj)?.node ?: return null
        val ra = (input.intField("RAHours") ?: 0) +
            (input.intField("RAMinutes") ?: 0) / 60.0 +
            (input.doubleField("RASeconds") ?: 0.0) / 3600.0
        val sign = if ((input.fields["NegativeDec"] as? NinaValue.Bool)?.value == true) -1.0 else 1.0
        val decAbs = kotlin.math.abs(input.intField("DecDegrees") ?: 0) +
            (input.intField("DecMinutes") ?: 0) / 60.0 +
            (input.doubleField("DecSeconds") ?: 0.0) / 3600.0
        val signedDegrees = input.intField("DecDegrees") ?: 0
        val dec = if (signedDegrees < 0) -decAbs else sign * decAbs
        return ra to dec
    }

    private fun writeSession(root: NinaNode) {
        val dir = sessionDir ?: return
        val frames = JSONArray()
        _frames.value.forEach { frame ->
            frames.put(JSONObject()
                .put("name", frame.name)
                .put("filter", frame.filter ?: JSONObject.NULL)
                .put("exposureSeconds", frame.exposureSeconds)
                .put("hfr", frame.hfr ?: JSONObject.NULL)
                .put("starCount", frame.starCount ?: JSONObject.NULL))
        }
        val focus = JSONArray()
        _autofocus.value.forEach { run ->
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
        File(dir, "session.json").writeText(JSONObject()
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
