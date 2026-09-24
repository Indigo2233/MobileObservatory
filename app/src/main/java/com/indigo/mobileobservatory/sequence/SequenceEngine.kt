package com.indigo.mobileobservatory.sequence

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class InstructionErrorBehavior {
    ContinueOnError,
    SkipInstructionSetOnError,
    AbortOnError,
    SkipToSequenceEndInstructions
}

enum class SequencePhase {
    Idle,
    Running,
    Paused,
    Completed,
    Stopped,
    Failed
}

data class SequenceRunState(
    val phase: SequencePhase,
    val currentClassName: String? = null,
    val message: String? = null,
    val framesDone: Int = 0,
    val currentNodeId: String? = null,
    val nodeStatus: Map<String, String> = emptyMap()
)

interface SequenceDevicePort {
    suspend fun execute(instruction: NinaNode, className: String)
    suspend fun measurePointing(): Pair<Double, Double>? = null
}

class SequenceStopped : CancellationException("sequence stopped")

class SequenceSkipToEnd : CancellationException("skip to sequence end")

class SequenceControl {
    private val paused = AtomicBoolean(false)
    private val skip = AtomicBoolean(false)
    private val holdForSkip = AtomicBoolean(false)
    private val skipToEnd = AtomicBoolean(false)
    private var stopCount = 0
    @Volatile private var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    val isPaused: Boolean get() = paused.get()

    fun pause() {
        synchronized(this) {
            if (stopCount > 0 || skipToEnd.get() || paused.get()) return
            gate = CompletableDeferred()
            paused.set(true)
        }
    }

    fun resume() {
        synchronized(this) {
            if (holdForSkip.get()) return
            if (!paused.compareAndSet(true, false)) return
            gate.complete(Unit)
        }
    }

    fun requestSkip() {
        synchronized(this) {
            skip.set(true)
            holdForSkip.set(false)
            if (paused.compareAndSet(true, false)) gate.complete(Unit)
        }
    }

    fun requestSkipToEnd() {
        synchronized(this) {
            skipToEnd.set(true)
            holdForSkip.set(false)
            if (paused.compareAndSet(true, false)) gate.complete(Unit)
        }
    }

    fun stop() {
        synchronized(this) {
            stopCount += 1
            holdForSkip.set(false)
            if (paused.compareAndSet(true, false)) gate.complete(Unit)
        }
    }

    suspend fun checkpoint() {
        val waiting = synchronized(this) {
            if (stopCount > 0) throw SequenceStopped()
            if (skipToEnd.get()) throw SequenceSkipToEnd()
            if (paused.get()) gate else null
        }
        waiting?.await()
        if (stopCount > 0) throw SequenceStopped()
        if (skipToEnd.get()) throw SequenceSkipToEnd()
    }

    fun consumeSkip(): Boolean = skip.compareAndSet(true, false)

    fun peekSkip(): Boolean = skip.get()

    fun peekSkipToEnd(): Boolean = skipToEnd.get()

    fun clearSkipToEnd() {
        skipToEnd.set(false)
    }

    fun reset() {
        synchronized(this) {
            paused.set(false)
            skip.set(false)
            holdForSkip.set(false)
            skipToEnd.set(false)
            stopCount = 0
            gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        }
    }

    fun holdUntilSkipped() {
        synchronized(this) {
            holdForSkip.set(true)
            if (!paused.get()) {
                gate = CompletableDeferred()
                paused.set(true)
            }
        }
    }
}

class GuideLost : Exception("guide lost")

class DeviceUnavailable(message: String) : Exception(message)

class SequenceEngine(
    private val port: SequenceDevicePort,
    val control: SequenceControl = SequenceControl(),
    private val world: SequenceWorld = SequenceWorld.System,
    private val onState: (SequenceRunState) -> Unit = {}
) {
    private var settings = SequenceSettings()
    var state: SequenceRunState = SequenceRunState(SequencePhase.Idle)
        private set

    private val status = HashMap<NinaNode, EntityStatus>()
    private val iterations = HashMap<NinaNode, Int>()
    private val completedIterations = HashMap<NinaNode, Int>()
    private val parents = HashMap<NinaNode, NinaNode>()
    private val triggerMemory = TriggerMemory()
    private val containerStartedAt = HashMap<NinaNode, Long>()
    private var framesDone = 0
    private var exposuresSinceCenter = 0
    private var exposuresSinceAutofocus = 0
    private var lastAutofocusAt = 0L
    private var temperatureAtAutofocus: Double? = null
    private var filterAtAutofocus: String? = null
    private var hfrAtAutofocus: Double? = null
    private var runStartedAt = 0L
    private var currentNodeId: String? = null

    private fun snapshotStatus(): Map<String, String> =
        status.mapNotNull { (node, value) -> node.id?.let { it to value.name } }.toMap()

    private fun snapshot(
        phase: SequencePhase,
        className: String? = null,
        message: String? = null
    ) = SequenceRunState(phase, className, message, framesDone, currentNodeId, snapshotStatus())

    private fun publish(phase: SequencePhase, className: String? = null, message: String? = null) {
        commit(snapshot(phase, className, message))
    }

    private fun commit(next: SequenceRunState) {
        state = next
        onState(next)
    }

    suspend fun run(root: NinaNode, settings: SequenceSettings = SequenceSettings()): SequenceRunState {
        this.settings = settings
        control.reset()
        framesDone = 0
        exposuresSinceCenter = 0
        exposuresSinceAutofocus = 0
        lastAutofocusAt = 0L
        temperatureAtAutofocus = null
        filterAtAutofocus = null
        hfrAtAutofocus = null
        status.clear()
        iterations.clear()
        completedIterations.clear()
        parents.clear()
        triggerMemory.meridianFired.clear()
        triggerMemory.lastFireFrame.clear()
        containerStartedAt.clear()
        indexParents(root)
        seedDisabled(root)
        runStartedAt = world.nowMillis()
        publish(SequencePhase.Running)
        val areas = root.childItems()
        val end = areas.firstOrNull { it.className == "EndAreaContainer" }
        val beforeEnd = areas.filter { it !== end }
        try {
            for (area in beforeEnd) {
                when (val outcome = runNode(area)) {
                    Outcome.Continue, Outcome.SkipRest -> Unit
                    Outcome.JumpToEnd -> break
                    Outcome.Abort -> {
                        commit(snapshot(SequencePhase.Failed, area.className, "AbortOnError"))
                        return state
                    }
                }
            }
            if (end != null && status[end] != EntityStatus.FINISHED) runNode(end)
            currentNodeId = null
            commit(snapshot(SequencePhase.Completed))
        } catch (_: SequenceSkipToEnd) {
            control.clearSkipToEnd()
            if (end != null && status[end] != EntityStatus.FINISHED && status[end] != EntityStatus.RUNNING) {
                try {
                    runNode(end)
                } catch (_: SequenceStopped) {
                } catch (_: SequenceSkipToEnd) {
                }
            }
            currentNodeId = null
            commit(snapshot(SequencePhase.Completed))
        } catch (_: SequenceStopped) {
            currentNodeId = null
            commit(snapshot(SequencePhase.Stopped))
        }
        return state
    }

    private suspend fun runNode(node: NinaNode): Outcome {
        if (entityStatus(node) == EntityStatus.DISABLED) return Outcome.Continue
        control.checkpoint()
        if (control.consumeSkip()) {
            status[node] = EntityStatus.SKIPPED
            return Outcome.Continue
        }
        if (node.className == "ParkScope" || node.className == "UnparkScope") {
            return pauseOnUnknown(node, "mount cannot park")
        }
        if (node.className == "MessageBox") {
            return pauseOnUnknown(node, node.textField("Text") ?: "message")
        }
        if (isContainer(node)) {
            status[node] = EntityStatus.RUNNING
            val outcome = if (node.className == "ParallelContainer") runParallel(node) else runContainer(node)
            if (status[node] == EntityStatus.RUNNING) status[node] = EntityStatus.FINISHED
            return outcome
        }
        if (!isKnownInstruction(node)) return pauseOnUnknown(node)
        if (nodeHasUnsupportedExpression(node)) {
            return pauseOnUnknown(node, "expression not supported")
        }
        return executeInstruction(node)
    }

    private suspend fun runContainer(container: NinaNode): Outcome {
        for (condition in container.collectionNodes("Conditions")) {
            if (entityStatus(condition) == EntityStatus.DISABLED) continue
            if (!knownCondition(condition.className)) pauseOnUnknown(condition)
        }
        iterations[container] = 0
        containerStartedAt[container] = world.nowMillis()
        var previous: NinaNode? = null
        while (true) {
            var ran = false
            while (true) {
                val next = container.childItems().firstOrNull { entityStatus(it) == EntityStatus.CREATED } ?: break
                if (!canContinue(container)) break
                ran = true
                fireTriggers(previous, next, TriggerMoment.Before)
                when (val outcome = runNode(next)) {
                    Outcome.Continue -> Unit
                    Outcome.SkipRest -> {
                        skipCreated(container)
                        return Outcome.Continue
                    }
                    Outcome.JumpToEnd, Outcome.Abort -> return outcome
                }
                previous = next
                val upcoming = container.childItems().firstOrNull { entityStatus(it) == EntityStatus.CREATED }
                fireTriggers(next, upcoming, TriggerMoment.After)
            }
            if (!ran) break
            iterations[container] = (iterations[container] ?: 0) + 1
            container.collectionNodes("Conditions").forEach { condition ->
                if (condition.className == "LoopCondition") {
                    val done = completedIterations.getOrPut(condition) {
                        condition.intField("CompletedIterations") ?: 0
                    }
                    completedIterations[condition] = done + 1
                }
            }
            if (!canContinue(container)) break
            container.childItems().forEach { child -> resetTree(child) }
        }
        skipCreated(container)
        return Outcome.Continue
    }

    private suspend fun runParallel(container: NinaNode): Outcome {
        val items = container.childItems().filter { entityStatus(it) != EntityStatus.DISABLED }
        if (items.isEmpty()) return Outcome.Continue
        val outcomes = coroutineScope {
            items.map { item -> async { runNode(item) } }.awaitAll()
        }
        return when {
            outcomes.contains(Outcome.Abort) -> Outcome.Abort
            outcomes.contains(Outcome.JumpToEnd) -> Outcome.JumpToEnd
            else -> Outcome.Continue
        }
    }

    private fun canContinue(container: NinaNode): Boolean {
        val conditions = container.collectionNodes("Conditions").filter {
            entityStatus(it) != EntityStatus.SKIPPED && entityStatus(it) != EntityStatus.DISABLED
        }
        val loops = conditions.filter { it.className == "LoopCondition" }
        val limits = conditions.filter { it.className != "LoopCondition" && knownCondition(it.className) }
        val loopOk = if (loops.isEmpty()) {
            (iterations[container] ?: 0) < 1
        } else {
            loops.all { condition ->
                val done = completedIterations.getOrPut(condition) { condition.intField("CompletedIterations") ?: 0 }
                loopAllows(condition, done)
            }
        }
        val limitOk = limits.all { condition ->
            limitAllows(condition, liveSignals(), containerStartedAt[container] ?: world.nowMillis())
        }
        val self = loopOk && limitOk
        val parent = parents[container] ?: return self
        return self && canContinue(parent)
    }

    private suspend fun executeInstruction(node: NinaNode): Outcome {
        currentNodeId = node.id
        status[node] = EntityStatus.RUNNING
        publish(SequencePhase.Running, node.className)
        val attempts = (node.intField("Attempts") ?: 1).coerceAtLeast(1)
        var lastFailure = false
        var attempt = 0
        while (attempt < attempts) {
            control.checkpoint()
            if (control.consumeSkip()) {
                status[node] = EntityStatus.SKIPPED
                return Outcome.Continue
            }
            val skippedByUser = AtomicBoolean(false)
            val conditionBroke = AtomicBoolean(false)
            try {
                coroutineScope {
                    val job = launch { port.execute(node, node.className) }
                    val watch = launch {
                        while (job.isActive) {
                            if (control.peekSkipToEnd()) {
                                job.cancel()
                                break
                            }
                            if (control.peekSkip()) {
                                control.consumeSkip()
                                skippedByUser.set(true)
                                job.cancel()
                                break
                            }
                            val parent = parents[node]
                            if (parent != null && !canContinue(parent)) {
                                conditionBroke.set(true)
                                job.cancel()
                                break
                            }
                            delay(500)
                        }
                    }
                    job.join()
                    watch.cancel()
                }
                status[node] = EntityStatus.FINISHED
                if (node.className == "TakeExposure") {
                    framesDone += 1
                    exposuresSinceCenter += 1
                    exposuresSinceAutofocus += 1
                }
                noteInstruction(node.className)
                publish(SequencePhase.Running, node.className)
                return Outcome.Continue
            } catch (stopped: SequenceStopped) {
                throw stopped
            } catch (skipEnd: SequenceSkipToEnd) {
                throw skipEnd
            } catch (unavailable: DeviceUnavailable) {
                return pauseOnUnknown(node, unavailable.message ?: "device unavailable")
            } catch (_: GuideLost) {
                publish(SequencePhase.Paused, node.className, "guide lost")
                control.pause()
                control.checkpoint()
                publish(SequencePhase.Running, node.className)
            } catch (_: CancellationException) {
                if (control.peekSkipToEnd()) throw SequenceSkipToEnd()
                if (skippedByUser.get()) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.Continue
                }
                if (conditionBroke.get()) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.SkipRest
                }
                throw SequenceStopped()
            } catch (_: Exception) {
                lastFailure = true
                attempt += 1
            }
        }
        if (!lastFailure) return Outcome.Continue
        status[node] = EntityStatus.FAILED
        return when (errorBehavior(node)) {
            InstructionErrorBehavior.ContinueOnError -> Outcome.Continue
            InstructionErrorBehavior.SkipInstructionSetOnError -> Outcome.SkipRest
            InstructionErrorBehavior.AbortOnError -> Outcome.Abort
            InstructionErrorBehavior.SkipToSequenceEndInstructions -> Outcome.JumpToEnd
        }
    }

    private suspend fun pauseOnUnknown(node: NinaNode, message: String = "unsupported"): Outcome {
        currentNodeId = node.id
        commit(snapshot(SequencePhase.Paused, node.className, message))
        control.holdUntilSkipped()
        control.checkpoint()
        control.consumeSkip()
        status[node] = EntityStatus.SKIPPED
        publish(SequencePhase.Running)
        return Outcome.Continue
    }

    private fun liveSignals(): SequenceSignals {
        val now = world.nowMillis()
        return SequenceSignals(
            nowMillis = now,
            altitudeDeg = world.altitudeDeg(),
            altitudeRising = world.altitudeRising(),
            minutesToMeridian = world.minutesToMeridian(),
            temperatureC = world.temperatureC(),
            lastHfr = world.lastHfr(),
            filterName = world.filterName(),
            sunAltitudeDeg = world.sunAltitudeDeg(),
            moonAltitudeDeg = world.moonAltitudeDeg(),
            moonIlluminationPct = world.moonIlluminationPct(),
            framesDone = framesDone,
            exposuresSinceCenter = exposuresSinceCenter,
            minutesSinceAutofocus = (now - (if (lastAutofocusAt == 0L) runStartedAt else lastAutofocusAt)) / 60_000.0,
            temperatureAtAutofocus = temperatureAtAutofocus,
            filterAtAutofocus = filterAtAutofocus,
            hfrAtAutofocus = hfrAtAutofocus
        )
    }

    private fun noteInstruction(className: String) {
        when (className) {
            "Center", "CenterAndRotate" -> exposuresSinceCenter = 0
            "RunAutofocus" -> {
                exposuresSinceAutofocus = 0
                lastAutofocusAt = world.nowMillis()
                temperatureAtAutofocus = world.temperatureC()
                filterAtAutofocus = world.filterName()
                hfrAtAutofocus = world.lastHfr()
            }
        }
    }

    private suspend fun fireTriggers(previous: NinaNode?, next: NinaNode?, moment: TriggerMoment) {
        val anchor = if (moment == TriggerMoment.After) previous else next
        if (anchor == null) return
        val triggers = mutableListOf<NinaNode>()
        var current: NinaNode? = parents[anchor] ?: if (isContainer(anchor)) anchor else null
        while (current != null) {
            triggers += current.collectionNodes("Triggers").filter {
                entityStatus(it) != EntityStatus.DISABLED
            }
            current = parents[current]
        }
        val due = matchingTriggers(triggers, previous, next, moment, liveSignals(), triggerMemory, settings)
        for (trigger in due) {
            markTriggerFired(trigger, liveSignals(), triggerMemory)
            try {
                if (trigger.className == "CenterAfterDriftTrigger" && !driftExceeded(trigger)) continue
                val runnerItems = triggerRunnerItems(trigger)
                val items = if (runnerItems.isNotEmpty()) {
                    runnerItems
                } else {
                    insertedInstructions(trigger, settings).map { NinaNode(type = it, id = null) }
                }
                for (item in items) {
                    val outcome = if (item.id != null) runNode(item) else executeInstruction(item)
                    when (outcome) {
                        Outcome.Continue -> Unit
                        Outcome.JumpToEnd, Outcome.Abort, Outcome.SkipRest -> return
                    }
                    if (trigger.className == "MeridianFlipTrigger" && item.className == "StopGuiding") {
                        waitMeridianWindow()
                    }
                }
                if (trigger.className == "MeridianFlipTrigger" && settings.settleTimeSeconds > 0) {
                    delay(settings.settleTimeSeconds * 1000L)
                }
            } catch (stopped: SequenceStopped) {
                throw stopped
            } catch (skipEnd: SequenceSkipToEnd) {
                throw skipEnd
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun waitMeridianWindow() {
        while (true) {
            control.checkpoint()
            val minutes = world.minutesToMeridian() ?: return
            if (-minutes >= settings.minutesAfterMeridian) return
            delay(1_000)
        }
    }

    private suspend fun driftExceeded(trigger: NinaNode): Boolean {
        val limit = expressionNumber(trigger, "DistanceArcMinutes") ?: 10.0
        val solved = port.measurePointing() ?: return true
        val target = inheritedTarget(trigger) ?: return true
        return equatorialSeparationArcmin(solved.first, solved.second, target.first, target.second) >= limit
    }

    private fun inheritedTarget(node: NinaNode): Pair<Double, Double>? {
        var current: NinaNode? = node
        while (current != null) {
            if (current.className == "DeepSkyObjectContainer") {
                val ra = dsoRaHours(current) ?: return null
                val dec = dsoDecDegrees(current) ?: return null
                return ra to dec
            }
            current = parents[current]
        }
        return null
    }

    private fun skipCreated(container: NinaNode) {
        container.childItems().forEach { child ->
            if (entityStatus(child) == EntityStatus.CREATED) status[child] = EntityStatus.SKIPPED
        }
    }

    private fun resetTree(node: NinaNode) {
        if (entityStatus(node) != EntityStatus.DISABLED) status[node] = EntityStatus.CREATED
        iterations.remove(node)
        if (isContainer(node)) {
            node.collectionNodes("Conditions").forEach { completedIterations.remove(it) }
            node.childItems().forEach { resetTree(it) }
        }
    }

    private fun entityStatus(node: NinaNode): EntityStatus = status.getOrPut(node) { EntityStatus.CREATED }

    private fun errorBehavior(node: NinaNode): InstructionErrorBehavior {
        val ordinal = node.intField("ErrorBehavior") ?: return InstructionErrorBehavior.ContinueOnError
        return InstructionErrorBehavior.entries.getOrElse(ordinal) { InstructionErrorBehavior.ContinueOnError }
    }

    private fun seedDisabled(root: NinaNode) {
        fun walk(node: NinaNode) {
            if (node.intField("Status") == SEQUENCE_STATUS_DISABLED) {
                status[node] = EntityStatus.DISABLED
            }
            node.fields.values.forEach { value -> walkValue(value, ::walk) }
        }
        walk(root)
    }

    private fun indexParents(root: NinaNode) {
        val byId = HashMap<String, NinaNode>()
        fun walk(node: NinaNode) {
            if (node.id != null) byId[node.id] = node
            node.fields.values.forEach { value -> walkValue(value, ::walk) }
        }
        walk(root)
        fun link(node: NinaNode) {
            val parentId = (node.fields["Parent"] as? NinaValue.Ref)?.id
            if (parentId != null) byId[parentId]?.let { parents[node] = it }
            node.fields.values.forEach { value -> walkValue(value, ::link) }
        }
        link(root)
    }

    private enum class EntityStatus { CREATED, RUNNING, FINISHED, SKIPPED, FAILED, DISABLED }

    private enum class Outcome { Continue, SkipRest, Abort, JumpToEnd }
}

private fun walkValue(value: NinaValue, visit: (NinaNode) -> Unit) {
    when (value) {
        is NinaValue.Obj -> visit(value.node)
        is NinaValue.Collection -> value.values.forEach { walkValue(it, visit) }
        is NinaValue.Array -> value.values.forEach { walkValue(it, visit) }
        else -> Unit
    }
}

private val containers = setOf(
    "SequenceRootContainer",
    "StartAreaContainer",
    "TargetAreaContainer",
    "EndAreaContainer",
    "DeepSkyObjectContainer",
    "SequentialContainer",
    "ParallelContainer",
    "SmartExposure",
    "TakeManyExposures"
)

private val knownInstructions = setOf(
    "TakeExposure",
    "SwitchFilter",
    "CoolCamera",
    "WarmCamera",
    "SlewScopeToRaDec",
    "SlewScopeToAltAz",
    "Center",
    "CenterAndRotate",
    "SolveAndSync",
    "SolveAndRotate",
    "MoveRotatorMechanical",
    "StartGuiding",
    "StopGuiding",
    "Dither",
    "SetTracking",
    "FindHome",
    "OpenCover",
    "CloseCover",
    "MoveFocuserAbsolute",
    "MoveFocuserRelative",
    "RunAutofocus",
    "WaitForTime",
    "WaitForTimeSpan",
    "WaitForAltitude",
    "WaitUntilAboveHorizon",
    "WaitForSunAltitude",
    "WaitForMoonAltitude",
    "Annotation"
)

interface SequenceWorld {
    fun nowMillis(): Long = java.lang.System.currentTimeMillis()
    fun altitudeDeg(): Double? = null
    fun altitudeRising(): Boolean? = null
    fun minutesToMeridian(): Double? = null
    fun temperatureC(): Double? = null
    fun lastHfr(): Double? = null
    fun filterName(): String? = null
    fun sunAltitudeDeg(): Double? = null
    fun moonAltitudeDeg(): Double? = null
    fun moonIlluminationPct(): Double? = null
    fun observerLatitudeDeg(): Double? = null
    fun observerLongitudeDeg(): Double? = null

    companion object {
        val System = object : SequenceWorld {}
    }
}

private fun isContainer(node: NinaNode): Boolean = node.className in containers

private fun isKnownInstruction(node: NinaNode): Boolean = node.className in knownInstructions
