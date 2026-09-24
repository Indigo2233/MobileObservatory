package com.indigo.mobileobservatory.sequence

import kotlin.math.abs

data class SimpleExposureRow(
    val filterName: String?,
    val exposureSeconds: Double,
    val gain: Int,
    val offset: Int,
    val count: Int,
    val binX: Int = 1,
    val binY: Int = 1
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
    val ditherEvery: Int? = null,
    val ditherPixels: Double = 3.0,
    val altitudeEndDeg: Double? = null,
    val endStopGuide: Boolean = false,
    val endWarm: Boolean = false,
    val endStopTracking: Boolean = false,
    val endGoHome: Boolean = false,
    val endCloseCover: Boolean = false
)

fun SimpleSequenceDraft.plannedFrames(): Int = rows.sumOf { it.count.coerceAtLeast(0) }

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
    val target = deepSkyTarget(ids, this)
    attachChildren(targets, listOf(target))
    attachChildren(start, startInstructions(ids, this))
    attachChildren(end, endInstructions(ids, this))
    attachChildren(root, listOf(start, targets, end))
    return root
}

private fun deepSkyTarget(ids: NinaIds, draft: SimpleSequenceDraft): NinaNode {
    val target = container(
        ids,
        "NINA.Sequencer.Container.DeepSkyObjectContainer",
        draft.title
    )
    target.fields["Target"] = NinaValue.Obj(inputTarget(ids, draft))
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
    val rows = draft.rows.map { row -> exposureLoop(ids, row) }
    attachChildren(target, rows)
    return target
}

private fun startInstructions(ids: NinaIds, draft: SimpleSequenceDraft): List<NinaNode> = buildList {
    draft.coolToC?.let { add(coolCamera(ids, it)) }
    if (draft.slewBefore) add(slew(ids, draft))
    if (draft.centerBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Platesolving.Center"))
    if (draft.guideBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Guider.StartGuiding"))
    if (draft.autofocusBefore) add(bareInstruction(ids, "NINA.Sequencer.SequenceItem.Autofocus.RunAutofocus"))
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

private fun slew(ids: NinaIds, draft: SimpleSequenceDraft): NinaNode {
    val ra = splitSexagesimal(draft.raHours.coerceIn(0.0, 24.0))
    return instruction(
        ids,
        "NINA.Sequencer.SequenceItem.Telescope.SlewScopeToRaDec",
        linkedMapOf(
            "RAHours" to NinaValue.Num(draft.raHours, integral = draft.raHours % 1.0 == 0.0),
            "RAMinutes" to NinaValue.Num(ra.second.toDouble(), true),
            "DecDegrees" to NinaValue.Num(draft.decDegrees, integral = draft.decDegrees % 1.0 == 0.0)
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

private fun inputTarget(ids: NinaIds, draft: SimpleSequenceDraft): NinaNode {
    val ra = splitSexagesimal(draft.raHours.coerceIn(0.0, 24.0))
    val decAbs = splitSexagesimal(abs(draft.decDegrees))
    val signedDegrees = if (draft.decDegrees < 0) -decAbs.first else decAbs.first
    val coordinates = node(
        ids,
        "NINA.Astrometry.InputCoordinates, NINA.Astrometry",
        linkedMapOf(
            "RAHours" to NinaValue.Num(ra.first.toDouble(), true),
            "RAMinutes" to NinaValue.Num(ra.second.toDouble(), true),
            "RASeconds" to NinaValue.Num(ra.third, integral = ra.third % 1.0 == 0.0),
            "NegativeDec" to NinaValue.Bool(draft.decDegrees < 0),
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
            "TargetName" to NinaValue.Text(draft.title),
            "PositionAngle" to NinaValue.Num(
                draft.positionAngleDeg,
                integral = draft.positionAngleDeg % 1.0 == 0.0
            ),
            "InputCoordinates" to NinaValue.Obj(coordinates)
        )
    )
}

private fun exposureLoop(ids: NinaIds, row: SimpleExposureRow): NinaNode {
    val loop = container(ids, "NINA.Sequencer.Container.SequentialContainer", row.filterName ?: "Exposure")
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
    fields["ImageType"] = NinaValue.Text("LIGHT")
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
    val whole = abs(value).toInt()
    val minutesFull = (abs(value) - whole) * 60.0
    val minutes = minutesFull.toInt()
    val seconds = (minutesFull - minutes) * 60.0
    return Triple(whole, minutes, seconds)
}
