package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.mount.MountMotionStopped

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    Interrupted,
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

internal class SequenceInstructionSkipped : CancellationException("instruction skipped")

class SequenceControl {
    private val paused = AtomicBoolean(false)
    private val mountStops = AtomicLong()
    private val skip = AtomicBoolean(false)
    private var skipOwner: NinaNode? = null
    private var promptOwner: NinaNode? = null
    private val activeInstructions = linkedSetOf<NinaNode>()
    private val holdForSkip = AtomicBoolean(false)
    private val skipToEnd = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    val isPaused: Boolean get() = paused.get()
    val isStopped: Boolean get() = stopped
    val requiresSkip: Boolean get() = holdForSkip.get()
    internal val mountStopRevision: Long get() = mountStops.get()

    internal fun pauseForMountStop() {
        synchronized(this) {
            pause()
            mountStops.incrementAndGet()
        }
    }

    fun pause() {
        synchronized(this) {
            if (stopped || skipToEnd.get() || paused.get()) return
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
            skipOwner = promptOwner ?: activeInstructions.lastOrNull()
            skip.set(true)
            holdForSkip.set(false)
            if (paused.compareAndSet(true, false)) gate.complete(Unit)
        }
    }

    internal fun instructionStarted(node: NinaNode) = synchronized(this) {
        activeInstructions.add(node)
    }

    internal fun instructionFinished(node: NinaNode) = synchronized(this) {
        activeInstructions.remove(node)
        if (skipOwner === node) {
            skipOwner = null
            skip.set(false)
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
            stopped = true
            holdForSkip.set(false)
            if (paused.compareAndSet(true, false)) gate.complete(Unit)
        }
    }

    suspend fun checkpoint() {
        while (true) {
            val waiting = synchronized(this) {
                if (stopped) throw SequenceStopped()
                if (skipToEnd.get()) throw SequenceSkipToEnd()
                if (paused.get()) gate else return
            }
            waiting.await()
        }
    }

    internal fun canDispatch(node: NinaNode): Boolean = synchronized(this) {
        if (stopped) throw SequenceStopped()
        if (skipToEnd.get()) throw SequenceSkipToEnd()
        if (consumeSkip(node)) throw SequenceInstructionSkipped()
        !paused.get()
    }

    fun consumeSkip(node: NinaNode): Boolean = consumeSkip(node) { }

    internal fun consumeSkip(node: NinaNode, onSkip: () -> Unit): Boolean = synchronized(this) {
        val skipped = (skipOwner == null || skipOwner === node) && skip.compareAndSet(true, false)
        if (skipped) onSkip()
        skipped
    }

    internal fun setPromptOwner(node: NinaNode?) = synchronized(this) { promptOwner = node }

    fun peekSkipToEnd(): Boolean = skipToEnd.get()

    fun clearSkipToEnd() {
        skipToEnd.set(false)
    }

    fun reset() {
        synchronized(this) {
            paused.set(false)
            skip.set(false)
            skipOwner = null
            promptOwner = null
            activeInstructions.clear()
            holdForSkip.set(false)
            skipToEnd.set(false)
            stopped = false
            gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        }
    }

    fun holdUntilSkipped() {
        synchronized(this) {
            if (stopped || skipToEnd.get() || skip.get()) return
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

private class AltitudeUnavailable(message: String) : Exception(message)

class SequenceStorageFailure(cause: Exception) : Exception(cause.message, cause)

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
    private val clockDeadlines = HashMap<NinaNode, Long>()
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
    private var lastError: String? = null
    private val promptMutex = Mutex()

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
        control.reset()
        return runPrepared(root, settings)
    }

    internal suspend fun runPrepared(root: NinaNode, settings: SequenceSettings): SequenceRunState {
        this.settings = settings
        lastError = null
        currentNodeId = null
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
        clockDeadlines.clear()
        indexParents(root)
        seedDisabled(root)
        runStartedAt = world.nowMillis()
        publish(SequencePhase.Running)
        val areas = root.childItems()
        val end = areas.firstOrNull { it.className == "EndAreaContainer" }
        val beforeEnd = areas.filter { it !== end }
        try {
            try {
                for (area in beforeEnd) {
                    when (runNode(area)) {
                        Outcome.Continue, Outcome.SkipRest -> Unit
                        Outcome.JumpToEnd -> break
                        Outcome.Abort -> {
                            commit(snapshot(SequencePhase.Failed, area.className, lastError))
                            return state
                        }
                    }
                }
            } catch (_: SequenceSkipToEnd) {
                control.clearSkipToEnd()
            }
            if (end != null && runNode(end) == Outcome.Abort) {
                commit(snapshot(SequencePhase.Failed, end.className, lastError))
                return state
            }
            control.checkpoint()
            currentNodeId = null
            commit(snapshot(SequencePhase.Completed, message = lastError))
        } catch (_: SequenceSkipToEnd) {
            currentNodeId = null
            commit(snapshot(SequencePhase.Completed, message = lastError))
        } catch (_: SequenceStopped) {
            currentNodeId = null
            commit(snapshot(SequencePhase.Stopped))
        } catch (missing: AltitudeUnavailable) {
            currentNodeId = null
            commit(snapshot(SequencePhase.Failed, message = missing.message))
        }
        return state
    }

    private suspend fun runNode(node: NinaNode): Outcome {
        if (entityStatus(node) == EntityStatus.DISABLED) return Outcome.Continue
        control.checkpoint()
        if (control.consumeSkip(node)) {
            status[node] = EntityStatus.SKIPPED
            return Outcome.Continue
        }
        if (node.className == "ParkScope" || node.className == "UnparkScope") {
            return pauseOnUnknown(node, "mount cannot park")
        }
        if (node.className == "MessageBox") return pauseForMessage(node)
        if (nodeHasUnsupportedExpression(node)) return pauseOnUnknown(node, "expression not supported")
        if (isContainer(node)) {
            status[node] = EntityStatus.RUNNING
            val outcome = if (node.className == "ParallelContainer") runParallel(node) else runContainer(node)
            if (status[node] == EntityStatus.RUNNING) status[node] = EntityStatus.FINISHED
            return outcome
        }
        if (!isKnownInstruction(node)) return pauseOnUnknown(node)
        return executeInstruction(node)
    }

    private suspend fun prepareContainer(container: NinaNode) {
        val startedAt = world.nowMillis()
        for (condition in container.collectionNodes("Conditions")) {
            if (entityStatus(condition) == EntityStatus.DISABLED) continue
            when {
                !knownCondition(condition.className) -> pauseOnUnknown(condition)
                nodeHasUnsupportedExpression(condition) -> pauseOnUnknown(condition, "expression not supported")
                condition.className == "TimeCondition" -> {
                    val deadline = nextClockTimeMillis(condition, startedAt, world.observerLatitudeDeg(),
                        world.observerLongitudeDeg(), inheritedTarget(condition)?.first)
                    if (deadline == null) pauseOnUnknown(condition, "Time provider unavailable")
                    else clockDeadlines[condition] = deadline
                }
            }
        }
        iterations[container] = 0
        containerStartedAt[container] = startedAt
    }

    private suspend fun runContainer(container: NinaNode): Outcome {
        prepareContainer(container)
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
            kotlinx.coroutines.yield()
        }
        skipCreated(container)
        return Outcome.Continue
    }

    private suspend fun runParallel(container: NinaNode): Outcome {
        prepareContainer(container)
        while (canContinue(container)) {
            val items = container.childItems().filter { entityStatus(it) != EntityStatus.DISABLED }
            if (items.isEmpty()) break
            try {
                coroutineScope {
                    items.map { item -> async {
                        val outcome = runNode(item)
                        if (outcome != Outcome.Continue) throw ParallelExit(outcome)
                    } }.awaitAll()
                }
            } catch (exit: ParallelExit) {
                if (exit.outcome != Outcome.SkipRest) return exit.outcome
                skipCreated(container)
                return Outcome.Continue
            }
            iterations[container] = (iterations[container] ?: 0) + 1
            container.collectionNodes("Conditions").forEach { condition ->
                if (condition.className == "LoopCondition") {
                    val done = completedIterations.getOrPut(condition) {
                        condition.intField("CompletedIterations") ?: 0
                    }
                    completedIterations[condition] = done + 1
                }
            }
            if (canContinue(container)) {
                container.childItems().forEach { child -> resetTree(child) }
            }
        }
        skipCreated(container)
        return Outcome.Continue
    }

    private fun canContinue(container: NinaNode): Boolean {
        val conditions = container.collectionNodes("Conditions").filter {
            entityStatus(it) != EntityStatus.SKIPPED && entityStatus(it) != EntityStatus.DISABLED
        }
        val loops = conditions.filter { it.className == "LoopCondition" }
        val limits = conditions.filter { it.className != "LoopCondition" && knownCondition(it.className) }
        val loopOk = if (conditions.isEmpty()) {
            (iterations[container] ?: 0) < 1
        } else {
            loops.all { condition ->
                val done = completedIterations.getOrPut(condition) { condition.intField("CompletedIterations") ?: 0 }
                loopAllows(condition, done)
            }
        }
        if (!loopOk) return false
        val limitOk = limits.all { condition ->
            if (condition.className == "TimeCondition") world.nowMillis() < (clockDeadlines[condition] ?: Long.MIN_VALUE)
            else {
                val signals = liveSignals(condition)
                val unavailable = when (condition.className) {
                    "AltitudeCondition", "AboveHorizonCondition" -> if (signals.altitudeDeg == null)
                        "Target altitude unavailable. Set the observing site and target coordinates." else null
                    "SunAltitudeCondition" -> if (signals.sunAltitudeDeg == null)
                        "Solar altitude unavailable. Set the observing site." else null
                    "MoonAltitudeCondition" -> if (signals.moonAltitudeDeg == null)
                        "Lunar altitude unavailable. Set the observing site." else null
                    else -> null
                }
                if (unavailable != null) throw AltitudeUnavailable(unavailable)
                limitAllows(condition, signals, containerStartedAt[container] ?: world.nowMillis())
            }
        }
        val parent = parents[container] ?: return limitOk
        return limitOk && canContinue(parent)
    }

    private suspend fun executeInstruction(node: NinaNode): Outcome {
        control.instructionStarted(node)
        try {
            return runInstructionAttempts(node)
        } finally {
            control.instructionFinished(node)
        }
    }

    private suspend fun runInstructionAttempts(node: NinaNode): Outcome {
        currentNodeId = node.id
        status[node] = EntityStatus.RUNNING
        val attempts = (node.intField("Attempts") ?: 1).coerceAtLeast(1)
        var attempt = 0
        while (attempt < attempts) {
            val mountStopRevision = control.mountStopRevision
            control.checkpoint()
            if (control.consumeSkip(node)) {
                status[node] = EntityStatus.SKIPPED
                return Outcome.Continue
            }
            currentNodeId = node.id
            publish(SequencePhase.Running, node.className)
            val skippedByUser = AtomicBoolean(false)
            val conditionBroke = AtomicBoolean(false)
            try {
                coroutineScope {
                    val job = async { port.execute(node, node.className) }
                    val watch = launch {
                        while (job.isActive) {
                            if (control.isStopped || control.peekSkipToEnd()) {
                                job.cancel()
                                break
                            }
                            if (control.consumeSkip(node) {
                                skippedByUser.set(true)
                                job.cancel()
                            }) break
                            val parent = parents[node]
                            if (parent != null && !canContinue(parent)) {
                                conditionBroke.set(true)
                                job.cancel()
                                break
                            }
                            delay(500)
                        }
                    }
                    try {
                        job.await()
                    } finally {
                        watch.cancel()
                    }
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
                if (awaitUser(node, unavailable.message)) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.Continue
                }
            } catch (_: GuideLost) {
                if (awaitUser(node, "guide lost")) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.Continue
                }
            } catch (cancelled: CancellationException) {
                if (control.isStopped) throw SequenceStopped()
                if (control.peekSkipToEnd()) throw SequenceSkipToEnd()
                if (skippedByUser.get() || cancelled is SequenceInstructionSkipped) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.Continue
                }
                if (conditionBroke.get()) {
                    status[node] = EntityStatus.SKIPPED
                    return Outcome.SkipRest
                }
                currentCoroutineContext().ensureActive()
                if (cancelled is MountMotionStopped && control.mountStopRevision != mountStopRevision) continue
                throw SequenceStopped()
            } catch (missing: AltitudeUnavailable) {
                throw missing
            } catch (storage: SequenceStorageFailure) {
                throw storage
            } catch (failure: Exception) {
                attempt += 1
                if (attempt == attempts) lastError = "${node.className}: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
        status[node] = EntityStatus.FAILED
        return when (errorBehavior(node)) {
            InstructionErrorBehavior.ContinueOnError -> Outcome.Continue
            InstructionErrorBehavior.SkipInstructionSetOnError -> Outcome.SkipRest
            InstructionErrorBehavior.AbortOnError -> Outcome.Abort
            InstructionErrorBehavior.SkipToSequenceEndInstructions -> Outcome.JumpToEnd
        }
    }

    private suspend fun awaitUser(node: NinaNode, message: String?, skipRequired: Boolean = false): Boolean =
        promptMutex.withLock {
            control.checkpoint()
            currentNodeId = node.id
            control.setPromptOwner(node)
            try {
                if (skipRequired) control.holdUntilSkipped() else control.pause()
                publish(SequencePhase.Paused, node.className, message)
                control.checkpoint()
                control.consumeSkip(node)
            } finally {
                control.setPromptOwner(null)
            }
        }

    private suspend fun pauseOnUnknown(node: NinaNode, message: String = "unsupported"): Outcome {
        awaitUser(node, message, skipRequired = true)
        status[node] = EntityStatus.SKIPPED
        publish(SequencePhase.Running)
        return Outcome.Continue
    }

    private suspend fun pauseForMessage(node: NinaNode): Outcome {
        status[node] = EntityStatus.RUNNING
        val skipped = awaitUser(node, node.textField("Text") ?: "message")
        status[node] = if (skipped) EntityStatus.SKIPPED else EntityStatus.FINISHED
        publish(SequencePhase.Running)
        return Outcome.Continue
    }

    private fun liveSignals(node: NinaNode): SequenceSignals {
        val now = world.nowMillis()
        val target = when (node.className) {
            "AltitudeCondition", "AboveHorizonCondition" -> altitudeConditionTarget(node)
            else -> inheritedTarget(node)
        }
        return SequenceSignals(
            nowMillis = now,
            altitudeDeg = world.altitudeDeg(target),
            altitudeRising = world.altitudeRising(target),
            minutesToMeridian = world.minutesToMeridian(target),
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
                entityStatus(it) != EntityStatus.DISABLED && entityStatus(it) != EntityStatus.SKIPPED
            }
            current = parents[current]
        }
        for (trigger in triggers) {
            if (!knownTrigger(trigger.className)) pauseOnUnknown(trigger)
            else if (nodeHasUnsupportedExpression(trigger)) pauseOnUnknown(trigger, "expression not supported")
        }
        val enabled = triggers.filter { entityStatus(it) != EntityStatus.SKIPPED }
        val due = matchingTriggers(enabled, previous, next, moment, liveSignals(anchor), triggerMemory, settings)
        for (trigger in due) {
            markTriggerFired(trigger, liveSignals(anchor), triggerMemory)
            try {
                if (trigger.className == "CenterAfterDriftTrigger" && !driftExceeded(trigger)) continue
                val runnerItems = triggerRunnerItems(trigger)
                val items = if (runnerItems.isNotEmpty()) {
                    runnerItems
                } else {
                    insertedInstructions(trigger, settings).map { NinaNode(type = it, id = null) }
                }
                for (item in items) {
                    if (item.id == null) parents[anchor]?.let { parents[item] = it }
                    val outcome = if (item.id != null) runNode(item) else executeInstruction(item)
                    when (outcome) {
                        Outcome.Continue -> Unit
                        Outcome.JumpToEnd, Outcome.Abort, Outcome.SkipRest -> return
                    }
                    if (trigger.className == "MeridianFlipTrigger" && item.className == "StopGuiding") {
                        waitMeridianWindow(anchor)
                    }
                }
                if (trigger.className == "MeridianFlipTrigger" && settings.settleTimeSeconds > 0) {
                    repeat(settings.settleTimeSeconds) {
                        control.checkpoint()
                        delay(1_000)
                    }
                }
            } catch (stopped: SequenceStopped) {
                throw stopped
            } catch (skipEnd: SequenceSkipToEnd) {
                throw skipEnd
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: AltitudeUnavailable) {
                throw missing
            } catch (storage: SequenceStorageFailure) {
                throw storage
            } catch (failure: Exception) {
                lastError = "${trigger.className}: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    private suspend fun waitMeridianWindow(anchor: NinaNode) {
        while (true) {
            control.checkpoint()
            val minutes = world.minutesToMeridian(inheritedTarget(anchor)) ?: throw DeviceUnavailable("site")
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
        val target = targetContainer(node) ?: return null
        return (dsoRaHours(target) ?: return null) to (dsoDecDegrees(target) ?: return null)
    }

    private fun altitudeConditionTarget(node: NinaNode): Pair<Double, Double>? {
        val hasDsoParent = (node.fields["HasDsoParent"] as? NinaValue.Bool)?.value
        if (hasDsoParent == true) return inheritedTarget(node)
        val data = (node.fields["Data"] as? NinaValue.Obj)?.node
        val coordinates = (data?.fields?.get("Coordinates") as? NinaValue.Obj)?.node
            ?: (node.fields["Coordinates"] as? NinaValue.Obj)?.node
        if (coordinates == null) return if (hasDsoParent == false) null else inheritedTarget(node)
        val ra = coordinateRaHours(coordinates) ?: return null
        val dec = coordinateDecDegrees(coordinates) ?: return null
        return (ra to dec).takeIf { ra in 0.0..24.0 && dec in -90.0..90.0 }
    }

    internal fun targetContainer(node: NinaNode): NinaNode? {
        var current: NinaNode? = node
        while (current != null) {
            if (current.className == "DeepSkyObjectContainer") return current
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
        // Execution follows containment. Imported Parent references can be missing or stale.
        fun link(node: NinaNode) {
            val children = node.childItems() + node.collectionNodes("Conditions") + node.collectionNodes("Triggers")
            for (child in children) {
                parents[child] = node
                link(child)
            }
            (node.fields["TriggerRunner"] as? NinaValue.Obj)?.node?.let { runner ->
                parents[runner] = node
                link(runner)
            }
        }
        link(root)
    }

    private enum class EntityStatus { CREATED, RUNNING, FINISHED, SKIPPED, FAILED, DISABLED }

    private class ParallelExit(val outcome: Outcome) : Exception()

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
    "DewHeater",
    "SetUSBLimit",
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
    "ToggleLight",
    "SetBrightness",
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
    fun altitudeDeg(target: Pair<Double, Double>?): Double? = altitudeDeg()
    fun altitudeRising(): Boolean? = null
    fun altitudeRising(target: Pair<Double, Double>?): Boolean? = altitudeRising()
    fun minutesToMeridian(): Double? = null
    fun minutesToMeridian(target: Pair<Double, Double>?): Double? = minutesToMeridian()
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
