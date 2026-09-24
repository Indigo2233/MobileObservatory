package com.indigo.mobileobservatory.sequence.catalog

import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.NinaValue
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.nextSequenceEditId

fun catalogInstruction(type: String, fields: LinkedHashMap<String, NinaValue> = linkedMapOf()): NinaNode {
    val merged = LinkedHashMap(fields)
    if (type.contains("SequenceItem") || type.contains("Conditions.") || type.contains("Trigger.")) {
        merged.putIfAbsent("ErrorBehavior", NinaValue.Num(0.0, true))
        merged.putIfAbsent("Attempts", NinaValue.Num(1.0, true))
    }
    return NinaNode(type, nextSequenceEditId(), merged)
}

fun catalogContainer(
    type: String,
    name: String,
    expanded: Boolean = true,
    strategy: String = "NINA.Sequencer.Container.ExecutionStrategy.SequentialStrategy, NINA.Sequencer"
): NinaNode {
    val node = catalogInstruction(type, linkedMapOf(
        "Strategy" to NinaValue.Obj(catalogInstruction(strategy, linkedMapOf())),
        "Name" to NinaValue.Text(name),
        "IsExpanded" to NinaValue.Bool(expanded)
    ))
    node.fields["Conditions"] = catalogCollection("NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer")
    node.fields["Triggers"] = catalogCollection("NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer")
    node.fields["Items"] = catalogCollection("NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer")
    return node
}

fun catalogCollection(elementType: String) = NinaValue.Collection(
    type = "System.Collections.ObjectModel.ObservableCollection`1[[$elementType]], System.ObjectModel",
    id = nextSequenceEditId(),
    values = emptyList()
)

fun attachChildren(parent: NinaNode, field: String, children: List<NinaNode>) {
    val existing = parent.fields[field] as? NinaValue.Collection ?: return
    val parentId = checkNotNull(parent.id)
    children.forEach { it.fields["Parent"] = NinaValue.Ref(parentId) }
    parent.fields[field] = existing.copy(values = existing.values + children.map { NinaValue.Obj(it) })
}

fun triggerRunner(items: List<NinaNode>): NinaNode {
    val runner = catalogContainer(
        "NINA.Sequencer.Container.SequentialContainer, NINA.Sequencer",
        "TriggerRunner"
    )
    if (items.isNotEmpty()) attachChildren(runner, "Items", items)
    return runner
}

fun waitLoopData(offset: Double, comparator: Int? = null): NinaValue.Obj {
    val fields = linkedMapOf<String, NinaValue>("Offset" to NinaValue.Num(offset, true))
    if (comparator != null) fields["Comparator"] = NinaValue.Num(comparator.toDouble(), true)
    return NinaValue.Obj(
        catalogInstruction("NINA.Sequencer.Utility.WaitLoopData, NINA.Sequencer", fields)
    )
}

fun inputCoordinates(): NinaNode = catalogInstruction(
    "NINA.Astrometry.InputCoordinates, NINA.Astrometry",
    linkedMapOf(
        "RAHours" to NinaValue.Num(0.0, true),
        "RAMinutes" to NinaValue.Num(0.0, true),
        "RASeconds" to NinaValue.Num(0.0, true),
        "NegativeDec" to NinaValue.Bool(false),
        "DecDegrees" to NinaValue.Num(0.0, true),
        "DecMinutes" to NinaValue.Num(0.0, true),
        "DecSeconds" to NinaValue.Num(0.0, true)
    )
)

fun timeProvider(): NinaNode = catalogInstruction(
    "NINA.Sequencer.Utility.DateTimeProvider.TimeProvider, NINA.Sequencer",
    linkedMapOf()
)

fun binningMode(): NinaNode = NinaNode(
    "NINA.Core.Model.Equipment.BinningMode, NINA.Core",
    nextSequenceEditId(),
    linkedMapOf(
        "X" to NinaValue.Num(1.0, true),
        "Y" to NinaValue.Num(1.0, true)
    )
)

fun slotElementType(slot: SequenceSlot): String = when (slot) {
    SequenceSlot.Condition -> "NINA.Sequencer.Conditions.ISequenceCondition, NINA.Sequencer"
    SequenceSlot.Trigger -> "NINA.Sequencer.Trigger.ISequenceTrigger, NINA.Sequencer"
    SequenceSlot.Item -> "NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer"
}
