package com.indigo.mobileobservatory.sequence

import kotlin.math.abs
import kotlin.math.roundToLong

data class SimpleExposureRow(
    val filterName: String?,
    val exposureSeconds: Double,
    val gain: Int,
    val offset: Int,
    val count: Int,
    val binX: Int = 1,
    val binY: Int = 1,
    val enabled: Boolean = true,
    val imageType: String = "LIGHT"
)

data class SimpleSequenceTarget(
    val name: String,
    val raHours: Double,
    val decDegrees: Double,
    val positionAngleDeg: Double = 0.0,
    val enabled: Boolean = true,
    val panelNumber: Int? = null,
    val panelRow: Int? = null,
    val panelColumn: Int? = null,
    val mosaicRows: Int? = null,
    val mosaicColumns: Int? = null,
    val overlapPercent: Int? = null,
    val mosaicPlanName: String? = null,
    val mosaicTraversal: String? = null,
    val mosaicStartCorner: String? = null,
    val mosaicCenterRaHours: Double? = null,
    val mosaicCenterDecDeg: Double? = null
)

data class SimpleSequenceDraft(
    val title: String,
    val raHours: Double,
    val decDegrees: Double,
    val rows: List<SimpleExposureRow>,
    val positionAngleDeg: Double = 0.0,
    val coolToC: Double? = null,
    val slewBefore: Boolean = false,
    val centerBefore: Boolean = false,
    val guideBefore: Boolean = false,
    val autofocusBefore: Boolean = false,
    val rotateBefore: Boolean = false,
    val ditherEvery: Int? = null,
    val ditherPixels: Double = 3.0,
    val altitudeEndDeg: Double? = null,
    val endStopGuide: Boolean = false,
    val endWarm: Boolean = false,
    val endStopTracking: Boolean = false,
    val endGoHome: Boolean = false,
    val endCloseCover: Boolean = false,
    val targets: List<SimpleSequenceTarget> = emptyList()
)

fun SimpleSequenceDraft.effectiveTargets(): List<SimpleSequenceTarget> =
    targets.ifEmpty {
        listOf(SimpleSequenceTarget(title, raHours, decDegrees, positionAngleDeg))
    }

fun SimpleSequenceDraft.plannedFrames(): Int {
    val framesPerTarget = rows.filter { it.enabled }.sumOf { it.count.coerceAtLeast(0) }
    return framesPerTarget * effectiveTargets().count { it.enabled }
}

fun NinaNode.plannedFrames(): Int {
    fun count(node: NinaNode, multiplier: Long): Long {
        if (sequenceNodeDisabled(node)) return 0
        if (node.className == "TakeExposure") return multiplier
        val loopIterations = node.collectionNodes("Conditions")
            .filterNot(::sequenceNodeDisabled)
            .filter { it.className == "LoopCondition" }
            .map {
                (expressionNumber(it, "Iterations") ?: 1.0).toLong()
                    .coerceIn(0, Int.MAX_VALUE.toLong())
            }
            .minOrNull()
            ?: if (node.className == "SmartExposure" || node.className == "TakeManyExposures") {
                (expressionNumber(node, "Iterations") ?: 1.0).toLong()
                    .coerceIn(0, Int.MAX_VALUE.toLong())
            } else {
                1
            }
        val childMultiplier = (multiplier * loopIterations).coerceAtMost(Int.MAX_VALUE.toLong())
        return node.childItems().sumOf { count(it, childMultiplier) }
            .coerceAtMost(Int.MAX_VALUE.toLong())
    }
    return count(this, 1).toInt()
}

private class NinaIds {
    private var next = 1
    fun next(): String = (next++).toString()
}

fun SimpleSequenceDraft.toNinaSequence(): NinaNode {
    val ids = NinaIds()
    val root = container(
        ids,
        "NINA.Sequencer.Container.SequenceRootContainer",
        title
    )
    val start = container(ids, "NINA.Sequencer.Container.StartAreaContainer", "Start")
    val targets = container(ids, "NINA.Sequencer.Container.TargetAreaContainer", "Targets")
    val end = container(ids, "NINA.Sequencer.Container.EndAreaContainer", "End")
    val plannedTargets = effectiveTargets()
    val multipleTargets = this.targets.isNotEmpty()
    attachChildren(
        targets,
        plannedTargets.map { target -> deepSkyTarget(ids, this, target, multipleTargets) }
    )
    attachChildren(start, startInstructions(ids, this))
    attachChildren(end, endInstructions(ids, this))
    attachChildren(root, listOf(start, targets, end))
    return root
}

private fun deepSkyTarget(
    ids: NinaIds,
    draft: SimpleSequenceDraft,
    sequenceTarget: SimpleSequenceTarget,
    targetScopedSetup: Boolean
): NinaNode {
    val target = container(
        ids,
        "NINA.Sequencer.Container.DeepSkyObjectContainer",
        sequenceTarget.name
    )
    target.fields["Target"] = NinaValue.Obj(inputTarget(ids, sequenceTarget))
    if (!sequenceTarget.enabled) {
        target.fields["Status"] = NinaValue.Num(SEQUENCE_STATUS_DISABLED.toDouble(), true)
    }
    sequenceTarget.panelNumber?.let { target.fields["MosaicPanelNumber"] = NinaValue.Num(it.toDouble(), true) }
    sequenceTarget.panelRow?.let { target.fields["MosaicRow"] = NinaValue.Num(it.toDouble(), true) }
    sequenceTarget.panelColumn?.let { target.fields["MosaicColumn"] = NinaValue.Num(it.toDouble(), true) }
    sequenceTarget.mosaicRows?.let { target.fields["MosaicRows"] = NinaValue.Num(it.toDouble(), true) }
    sequenceTarget.mosaicColumns?.let { target.fields["MosaicColumns"] = NinaValue.Num(it.toDouble(), true) }
    sequenceTarget.overlapPercent?.let {
        target.fields["MosaicOverlapPercent"] = NinaValue.Num(it.toDouble(), true)
    }
    sequenceTarget.mosaicPlanName?.let { target.fields["MosaicPlanName"] = NinaValue.Text(it) }
    sequenceTarget.mosaicTraversal?.let { target.fields["MosaicTraversal"] = NinaValue.Text(it) }
    sequenceTarget.mosaicStartCorner?.let { target.fields["MosaicStartCorner"] = NinaValue.Text(it) }
    sequenceTarget.mosaicCenterRaHours?.let {
        target.fields["MosaicCenterRaHours"] = NinaValue.Num(it, it % 1.0 == 0.0)
    }
    sequenceTarget.mosaicCenterDecDeg?.let {
        target.fields["MosaicCenterDecDeg"] = NinaValue.Num(it, it % 1.0 == 0.0)
    }
    target.fields["ExposureInfoListExpanded"] = NinaValue.Bool(false)
    target.fields["ExposureInfoList"] = emptyCollection(
        ids,
        "NINA.Sequencer.Utility.ExposureInfo, NINA.Sequencer"
    )
    draft.altitudeEndDeg?.let { offset ->
        val condition = altitudeCondition(ids, offset)
        condition.fields["Parent"] = NinaValue.Ref(checkNotNull(target.id))
        target.fields["Conditions"] = itemCollection(
            ids,
            "NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer",
            listOf(condition)
        )
    }
    draft.ditherEvery?.let { every ->
        val trigger = ditherTrigger(ids, every)
        trigger.fields["Parent"] = NinaValue.Ref(checkNotNull(target.id))
        target.fields["Triggers"] = itemCollection(
            ids,
            "NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer",
            listOf(trigger)
        )
    }
    val steps = buildList {
        if (targetScopedSetup) {
            if (draft.guideBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.StopGuiding"))
            if (draft.slewBefore) add(slew(ids, sequenceTarget))
            if (draft.centerBefore) {
                add(
                    if (draft.rotateBefore) {
                        instruction(
                            ids,
                            "NINA.Sequencer.SequenceItem.Platesolving.CenterAndRotate",
                            linkedMapOf(
                                "PositionAngle" to NinaValue.Num(
                                    sequenceTarget.positionAngleDeg,
                                    sequenceTarget.positionAngleDeg % 1.0 == 0.0
                                )
                            )
                        )
                    } else {
                        bareInstruction(ids, "NINA.Sequencer.SequenceItem.Platesolving.Center")
                    }
                )
            }
            if (draft.autofocusBefore) {
                add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Autofocus.RunAutofocus"))
            }
            if (draft.guideBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.StartGuiding"))
        }
        addAll(draft.rows.map { row -> exposureLoop(ids, row) })
    }
    attachChildren(target, steps)
    return target
}

private fun startInstructions(ids: NinaIds, draft: SimpleSequenceDraft): List<NinaNode> = buildList {
    draft.coolToC?.let { add(coolCamera(ids, it)) }
    if (draft.targets.isEmpty()) {
        if (draft.slewBefore) add(slew(ids, draft.asTarget()))
        if (draft.centerBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Platesolving.Center"))
        if (draft.guideBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.StartGuiding"))
        if (draft.autofocusBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Autofocus.RunAutofocus"))
    }
}

private fun endInstructions(ids: NinaIds, draft: SimpleSequenceDraft): List<NinaNode> = buildList {
    if (draft.endStopGuide) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.StopGuiding"))
    if (draft.endWarm) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Camera.WarmCamera"))
    if (draft.endStopTracking) {
        add(instruction(ids, "NINA.Sequencer.SequenceItem.Telescope.SetTracking", linkedMapOf(
            "TrackingMode" to NinaValue.Num(5.0, true)
        )))
    }
    if (draft.endGoHome) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Telescope.FindHome"))
    if (draft.endCloseCover) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.FlatDevice.CloseCover"))
}

private fun coolCamera(ids: NinaIds, celsius: Double): NinaNode {
    val fields = LinkedHashMap<String, NinaValue>()
    putExpression(fields, "Temperature", celsius, ids.next())
    putExpression(fields, "Duration", 0.0, ids.next())
    return instruction(ids, "NINA.Sequencer.SequenceItem.Camera.CoolCamera", fields)
}

private fun slew(ids: NinaIds, target: SimpleSequenceTarget): NinaNode {
    val ra = splitSexagesimal(target.raHours.coerceIn(0.0, 24.0))
    return instruction(
        ids,
        "NINA.Sequencer.SequenceItem.Telescope.SlewScopeToRaDec",
        linkedMapOf(
            "RAHours" to NinaValue.Num(target.raHours, integral = target.raHours % 1.0 == 0.0),
            "RAMinutes" to NinaValue.Num(ra.second.toDouble(), true),
            "DecDegrees" to NinaValue.Num(
                target.decDegrees,
                integral = target.decDegrees % 1.0 == 0.0
            )
        )
    )
}

private fun altitudeCondition(ids: NinaIds, offset: Double): NinaNode {
    val data = node(
        ids,
        "NINA.Sequencer.Utility.WaitLoopData, NINA.Sequencer",
        linkedMapOf("Offset" to NinaValue.Num(offset, integral = offset % 1.0 == 0.0))
    )
    return instruction(
        ids,
        "NINA.Sequencer.Conditions.AltitudeCondition",
        linkedMapOf("Data" to NinaValue.Obj(data))
    )
}

private fun ditherTrigger(ids: NinaIds, every: Int): NinaNode {
    val fields = LinkedHashMap<String, NinaValue>()
    putExpression(fields, "AfterExposures", every.toDouble(), ids.next())
    val dither = bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.Dither")
    val runner = container(ids, "NINA.Sequencer.Container.SequentialContainer", "TriggerRunner")
    attachChildren(runner, listOf(dither))
    fields["TriggerRunner"] = NinaValue.Obj(runner)
    return instruction(ids, "NINA.Sequencer.Trigger.Guider.DitherAfterExposures", fields)
}

private fun bareInstruction(ids: NinaIds, type: String): NinaNode = instruction(ids, type, linkedMapOf())

private fun instruction(
    ids: NinaIds,
    type: String,
    fields: LinkedHashMap<String, NinaValue>
): NinaNode {
    val merged = LinkedHashMap(fields)
    merged.putIfAbsent("ErrorBehavior", NinaValue.Num(0.0, true))
    merged.putIfAbsent("Attempts", NinaValue.Num(1.0, true))
    val qualified = if (',' in type) type else "$type, NINA.Sequencer"
    return node(ids, qualified, merged)
}

private fun inputTarget(ids: NinaIds, target: SimpleSequenceTarget): NinaNode {
    val ra = splitSexagesimal(target.raHours.coerceIn(0.0, 24.0))
    val decAbs = splitSexagesimal(abs(target.decDegrees))
    val signedDegrees = if (target.decDegrees < 0) -decAbs.first else decAbs.first
    val coordinates = node(
        ids,
        "NINA.Astrometry.InputCoordinates, NINA.Astrometry",
        linkedMapOf(
            "RAHours" to NinaValue.Num(ra.first.toDouble(), true),
            "RAMinutes" to NinaValue.Num(ra.second.toDouble(), true),
            "RASeconds" to NinaValue.Num(ra.third, integral = ra.third % 1.0 == 0.0),
            "NegativeDec" to NinaValue.Bool(target.decDegrees < 0),
            "DecDegrees" to NinaValue.Num(signedDegrees.toDouble(), true),
            "DecMinutes" to NinaValue.Num(decAbs.second.toDouble(), true),
            "DecSeconds" to NinaValue.Num(decAbs.third, integral = decAbs.third % 1.0 == 0.0)
        )
    )
    return node(
        ids,
        "NINA.Astrometry.InputTarget, NINA.Astrometry",
        linkedMapOf(
            "Expanded" to NinaValue.Bool(true),
            "TargetName" to NinaValue.Text(target.name),
            "PositionAngle" to NinaValue.Num(
                target.positionAngleDeg,
                integral = target.positionAngleDeg % 1.0 == 0.0
            ),
            "InputCoordinates" to NinaValue.Obj(coordinates)
        )
    )
}

private fun SimpleSequenceDraft.asTarget(): SimpleSequenceTarget =
    SimpleSequenceTarget(title, raHours, decDegrees, positionAngleDeg)

private fun exposureLoop(ids: NinaIds, row: SimpleExposureRow): NinaNode {
    val loop = container(ids, "NINA.Sequencer.Container.SequentialContainer", row.filterName ?: "Exposure")
    if (!row.enabled) loop.fields["Status"] = NinaValue.Num(SEQUENCE_STATUS_DISABLED.toDouble(), true)
    val condition = node(
        ids,
        "NINA.Sequencer.Conditions.LoopCondition, NINA.Sequencer",
        LinkedHashMap<String, NinaValue>().also { fields ->
            fields["CompletedIterations"] = NinaValue.Num(0.0, true)
            putExpression(fields, "Iterations", row.count.toDouble(), ids.next())
            fields["ErrorBehavior"] = NinaValue.Num(0.0, true)
            fields["Attempts"] = NinaValue.Num(1.0, true)
        }
    )
    condition.fields["Parent"] = NinaValue.Ref(checkNotNull(loop.id))
    loop.fields["Conditions"] = itemCollection(ids, "NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer", listOf(condition))
    val steps = buildList {
        if (!row.filterName.isNullOrBlank()) add(switchFilter(ids, row.filterName))
        add(takeExposure(ids, row))
    }
    attachChildren(loop, steps)
    return loop
}

private fun switchFilter(ids: NinaIds, filterName: String): NinaNode = node(
    ids,
    "NINA.Sequencer.SequenceItem.FilterWheel.SwitchFilter, NINA.Sequencer",
    linkedMapOf(
        "ComboBoxText" to NinaValue.Text(filterName),
        "ErrorBehavior" to NinaValue.Num(0.0, true),
        "Attempts" to NinaValue.Num(1.0, true)
    )
)

private fun takeExposure(ids: NinaIds, row: SimpleExposureRow): NinaNode {
    val seconds = row.exposureSeconds
    val fields = LinkedHashMap<String, NinaValue>()
    putExpression(fields, "ExposureTime", seconds, ids.next())
    putExpression(fields, "Gain", row.gain.toDouble(), ids.next())
    putExpression(fields, "Offset", row.offset.toDouble(), ids.next())
    fields["Binning"] = NinaValue.Obj(
        node(
            ids,
            "NINA.Core.Model.Equipment.BinningMode, NINA.Core",
            linkedMapOf(
                "X" to NinaValue.Num(row.binX.toDouble(), true),
                "Y" to NinaValue.Num(row.binY.toDouble(), true)
            )
        )
    )
    fields["ImageType"] = NinaValue.Text(row.imageType.ifBlank { "LIGHT" }.uppercase())
    fields["ExposureCount"] = NinaValue.Num(0.0, true)
    fields["ErrorBehavior"] = NinaValue.Num(0.0, true)
    fields["Attempts"] = NinaValue.Num(1.0, true)
    return node(ids, "NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA.Sequencer", fields)
}

private fun container(ids: NinaIds, className: String, name: String): NinaNode {
    val container = node(
        ids,
        "$className, NINA.Sequencer",
        linkedMapOf(
            "Strategy" to NinaValue.Obj(
                node(
                    ids,
                    "NINA.Sequencer.Container.ExecutionStrategy.SequentialStrategy, NINA.Sequencer",
                    linkedMapOf()
                )
            ),
            "Name" to NinaValue.Text(name),
            "IsExpanded" to NinaValue.Bool(true),
            "Conditions" to emptyCollection(ids, "NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer"),
            "Triggers" to emptyCollection(ids, "NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer")
        )
    )
    container.fields["Items"] = emptyCollection(ids, "NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer")
    return container
}

private fun attachChildren(parent: NinaNode, children: List<NinaNode>) {
    val parentId = checkNotNull(parent.id)
    children.forEach { child -> child.fields["Parent"] = NinaValue.Ref(parentId) }
    parent.fields["Items"] = itemCollection(
        ids = null,
        elementType = "NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer",
        items = children,
        collectionId = (parent.fields["Items"] as? NinaValue.Collection)?.id
    )
}

private fun itemCollection(
    ids: NinaIds?,
    elementType: String,
    items: List<NinaNode>,
    collectionId: String? = ids?.next()
): NinaValue.Collection = NinaValue.Collection(
    type = observableCollectionType(elementType),
    id = collectionId,
    values = items.map { NinaValue.Obj(it) }
)

private fun emptyCollection(ids: NinaIds, elementType: String): NinaValue.Collection =
    itemCollection(ids, elementType, emptyList())

private fun node(ids: NinaIds, type: String, fields: LinkedHashMap<String, NinaValue>): NinaNode =
    NinaNode(type = type, id = ids.next(), fields = fields)

private fun observableCollectionType(elementType: String): String =
    "System.Collections.ObjectModel.ObservableCollection`1[[$elementType]], System.ObjectModel"

internal fun splitSexagesimal(value: Double): Triple<Int, Int, Double> {
    val totalTenths = (abs(value) * 36_000.0).roundToLong()
    val whole = (totalTenths / 36_000L).toInt()
    val remainder = totalTenths % 36_000L
    val minutes = (remainder / 600L).toInt()
    val seconds = (remainder % 600L) / 10.0
    return Triple(whole, minutes, seconds)
}
