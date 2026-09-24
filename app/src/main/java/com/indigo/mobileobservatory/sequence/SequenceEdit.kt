package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import com.indigo.mobileobservatory.sequence.catalog.SupportLevel
import com.indigo.mobileobservatory.sequence.catalog.binningMode
import com.indigo.mobileobservatory.sequence.catalog.catalogCollection
import com.indigo.mobileobservatory.sequence.catalog.catalogContainer
import com.indigo.mobileobservatory.sequence.catalog.catalogInstruction
import com.indigo.mobileobservatory.sequence.catalog.slotElementType
import java.util.concurrent.atomic.AtomicLong

/** Matches NINA `SequenceEntityStatus.DISABLED`. Persisted so a template keeps the toggle. */
const val SEQUENCE_STATUS_DISABLED = 5

enum class SequenceSlot { Item, Condition, Trigger }

data class SequenceCatalogEntry(
    val id: String,
    val slot: SequenceSlot,
    val groupZh: String,
    val groupEn: String,
    val titleZh: String,
    val titleEn: String,
    val supportLevel: SupportLevel = SupportLevel.Execute,
    val hiddenByDefault: Boolean = false
)

data class SequenceEditField(
    val path: String,
    val labelZh: String,
    val labelEn: String
)

private val sequenceIds = AtomicLong(System.currentTimeMillis())

fun nextSequenceEditId(): String = "e${sequenceIds.incrementAndGet()}"

fun sequenceCatalog(includeHidden: Boolean = false): List<SequenceCatalogEntry> =
    SequenceCatalog.types
        .filter { it.listed }
        .filter { includeHidden || !it.hiddenByDefault }
        .map { it.toCatalogEntry() }

fun catalogEntry(id: String): SequenceCatalogEntry? =
    SequenceCatalog.spec(id)?.toCatalogEntry()

private fun com.indigo.mobileobservatory.sequence.catalog.SequenceTypeSpec.toCatalogEntry() = SequenceCatalogEntry(
    id = id,
    slot = slot,
    groupZh = groupZh,
    groupEn = groupEn,
    titleZh = titleZh,
    titleEn = titleEn,
    supportLevel = level,
    hiddenByDefault = hiddenByDefault
)

fun emptyAdvancedSequence(name: String): NinaNode {
    val root = catalogContainer("NINA.Sequencer.Container.SequenceRootContainer, NINA.Sequencer", name)
    val start = catalogContainer("NINA.Sequencer.Container.StartAreaContainer, NINA.Sequencer", "开始")
    val targets = catalogContainer("NINA.Sequencer.Container.TargetAreaContainer, NINA.Sequencer", "目标")
    val end = catalogContainer("NINA.Sequencer.Container.EndAreaContainer, NINA.Sequencer", "结束")
    val rootId = checkNotNull(root.id)
    listOf(start, targets, end).forEach { area -> area.fields["Parent"] = NinaValue.Ref(rootId) }
    val items = root.fields.getValue("Items") as NinaValue.Collection
    root.fields["Items"] = items.copy(values = listOf(start, targets, end).map { NinaValue.Obj(it) })
    return root
}

fun sequenceNodeTitle(node: NinaNode, chinese: Boolean): String {
    val catalog = SequenceCatalog.specByClass(node.className)
    val base = if (catalog == null) {
        node.className
    } else if (chinese) {
        catalog.titleZh
    } else {
        catalog.titleEn
    }
    val name = node.textField("Name") ?: node.textField("ComboBoxText")
    return if (name.isNullOrBlank() || name == base) base else "$base · $name"
}

fun sequenceNodeDisabled(node: NinaNode): Boolean = node.intField("Status") == SEQUENCE_STATUS_DISABLED

fun sequenceStructural(node: NinaNode): Boolean = node.className in setOf(
    "SequenceRootContainer",
    "StartAreaContainer",
    "TargetAreaContainer",
    "EndAreaContainer"
)

fun editableFields(node: NinaNode): List<SequenceEditField> {
    val fields = ArrayList<SequenceEditField>()
    val spec = SequenceCatalog.specByClass(node.className)
    if (spec?.isSet == true || node.fields.containsKey("Name") || node.className.endsWith("Container")) {
        fields += SequenceEditField("Name", "名称", "Name")
    }
    if (node.className == "DeepSkyObjectContainer") {
        fields += SequenceEditField("TargetName", "目标名", "Target name")
    }
    spec?.fields?.forEach { field ->
        fields += SequenceEditField(field.editPath, field.labelZh, field.labelEn)
    }
    if (!sequenceStructural(node) && spec?.slot != SequenceSlot.Condition) {
        fields += SequenceEditField("ErrorBehavior", "失败策略", "On error")
        fields += SequenceEditField("Attempts", "重试", "Attempts")
    }
    return fields.distinctBy { it.path }
}

fun sequenceParamSummary(node: NinaNode, chinese: Boolean): String {
    val spec = SequenceCatalog.specByClass(node.className) ?: return ""
    val parts = ArrayList<String>()
    spec.fields.take(4).forEach { field ->
        val text = sequenceFieldText(node, field.editPath)
        if (text.isBlank()) return@forEach
        val cameraDefault = field.key == "Gain" || field.key == "Offset"
        if (cameraDefault && (text == "-1" || text.isBlank())) {
            parts += if (chinese) "${field.labelZh} 相机" else "${field.labelEn} camera"
            return@forEach
        }
        val unit = field.unit.orEmpty()
        parts += if (unit.isBlank()) text else "$text $unit"
    }
    return parts.joinToString(" · ")
}

fun sequenceExpanded(node: NinaNode): Boolean =
    (node.fields["IsExpanded"] as? NinaValue.Bool)?.value != false

fun setSequenceExpanded(root: NinaNode, id: String, expanded: Boolean): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    node.fields["IsExpanded"] = NinaValue.Bool(expanded)
    return true
}

fun resetSequenceProgress(root: NinaNode, id: String): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    fun reset(current: NinaNode) {
        if (current.intField("Status") != SEQUENCE_STATUS_DISABLED) current.fields.remove("Status")
        if (current.className == "LoopCondition") {
            current.fields["CompletedIterations"] = NinaValue.Num(0.0, true)
        }
        if (current.className == "TakeExposure" || current.className == "TakeSubframeExposure") {
            current.fields["ExposureCount"] = NinaValue.Num(0.0, true)
        }
        listOf("Items", "Conditions", "Triggers").forEach { field ->
            current.collectionNodes(field).forEach { reset(it) }
        }
        val runner = (current.fields["TriggerRunner"] as? NinaValue.Obj)?.node
        if (runner != null) reset(runner)
    }
    reset(node)
    return true
}

fun dsoTargetName(node: NinaNode): String? =
    ((node.fields["Target"] as? NinaValue.Obj)?.node)?.textField("TargetName")

fun dsoRaHours(node: NinaNode): Double? {
    val coordinates = dsoCoordinates(node) ?: return null
    val hours = coordinates.doubleField("RAHours") ?: return null
    val minutes = coordinates.doubleField("RAMinutes") ?: 0.0
    val seconds = coordinates.doubleField("RASeconds") ?: 0.0
    return hours + minutes / 60.0 + seconds / 3600.0
}

fun dsoDecDegrees(node: NinaNode): Double? {
    val coordinates = dsoCoordinates(node) ?: return null
    val degrees = coordinates.doubleField("DecDegrees") ?: return null
    val minutes = coordinates.doubleField("DecMinutes") ?: 0.0
    val seconds = coordinates.doubleField("DecSeconds") ?: 0.0
    val absolute = kotlin.math.abs(degrees) + minutes / 60.0 + seconds / 3600.0
    val negative = (coordinates.fields["NegativeDec"] as? NinaValue.Bool)?.value == true || degrees < 0
    return if (negative) -absolute else absolute
}

fun dsoPositionAngle(node: NinaNode): Double? {
    val target = (node.fields["Target"] as? NinaValue.Obj)?.node ?: return null
    target.doubleField("PositionAngle")?.let { return it }
    return target.doubleField("Rotation")?.let { 360.0 - it }
}

fun applyDsoSkyTarget(
    root: NinaNode,
    id: String,
    name: String,
    raHours: Double,
    decDeg: Double,
    positionAngleDeg: Double
): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    if (node.className != "DeepSkyObjectContainer") return false
    val target = (node.fields["Target"] as? NinaValue.Obj)?.node ?: return false
    val coordinates = dsoCoordinates(node) ?: return false
    writeScalar(target, "TargetName", name)
    node.fields["Name"] = NinaValue.Text(name)
    writeScalar(target, "PositionAngle", positionAngleDeg.toString())
    writeDsoEquatorial(coordinates, raHours, decDeg)
    return true
}

val SEQUENCE_TIME_PROVIDER_IDS = listOf(
    "TimeProvider",
    "SunsetProvider",
    "SunriseProvider",
    "CivilDuskProvider",
    "CivilDawnProvider",
    "NauticalDuskProvider",
    "NauticalDawnProvider",
    "DuskProvider",
    "DawnProvider",
    "MeridianProvider"
)

fun sequenceTimeProviderLabel(id: String, chinese: Boolean): String = when (id) {
    "SunsetProvider" -> if (chinese) "日落" else "Sunset"
    "SunriseProvider" -> if (chinese) "日出" else "Sunrise"
    "CivilDuskProvider" -> if (chinese) "民用昏影终" else "Civil dusk"
    "CivilDawnProvider" -> if (chinese) "民用晨光始" else "Civil dawn"
    "NauticalDuskProvider" -> if (chinese) "航海昏影终" else "Nautical dusk"
    "NauticalDawnProvider" -> if (chinese) "航海晨光始" else "Nautical dawn"
    "DuskProvider" -> if (chinese) "天文昏影终" else "Astronomical dusk"
    "DawnProvider" -> if (chinese) "天文晨光始" else "Astronomical dawn"
    "MeridianProvider" -> if (chinese) "目标过中天" else "Meridian"
    else -> if (chinese) "指定时刻" else "Clock time"
}

fun sequenceTimeProviderId(node: NinaNode): String =
    ((node.fields["SelectedProvider"] as? NinaValue.Obj)?.node)?.className ?: "TimeProvider"

fun setSequenceTimeProvider(root: NinaNode, id: String, className: String): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    val type = "NINA.Sequencer.Utility.DateTimeProvider.$className, NINA.Sequencer"
    node.fields["SelectedProvider"] = NinaValue.Obj(catalogInstruction(type, linkedMapOf()))
    return true
}

fun sequenceInherited(node: NinaNode): Boolean =
    (node.fields["Inherited"] as? NinaValue.Bool)?.value == true

fun sequenceBinningText(node: NinaNode): String {
    val bin = (node.fields["Binning"] as? NinaValue.Obj)?.node ?: return "1x1"
    return "${bin.intField("X") ?: 1}x${bin.intField("Y") ?: 1}"
}

fun setSequenceBinning(root: NinaNode, id: String, text: String): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    val parts = text.lowercase().split('x', '×')
    val x = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val y = parts.getOrNull(1)?.toIntOrNull() ?: x
    val existing = (node.fields["Binning"] as? NinaValue.Obj)?.node
    val bin = existing ?: binningMode().also { node.fields["Binning"] = NinaValue.Obj(it) }
    bin.fields["X"] = NinaValue.Num(x.toDouble(), true)
    bin.fields["Y"] = NinaValue.Num(y.toDouble(), true)
    return true
}

fun dsoCoordinateText(node: NinaNode, part: String): String {
    val coordinates = dsoCoordinates(node) ?: return ""
    val value = coordinates.fields[part]
    return when (value) {
        is NinaValue.Num -> if (value.integral) value.value.toLong().toString() else value.value.toString()
        is NinaValue.Bool -> value.value.toString()
        else -> coordinates.doubleField(part)?.toString().orEmpty()
    }
}

fun setDsoCoordinatePart(root: NinaNode, id: String, part: String, text: String): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    if (node.className != "DeepSkyObjectContainer") return false
    val coordinates = dsoCoordinates(node) ?: return false
    writeScalar(coordinates, part, text)
    return true
}

data class SequenceExposureLine(
    val filter: String,
    val seconds: Double,
    val count: Int,
    val imageType: String
)

fun dsoExposureSummary(target: NinaNode): List<SequenceExposureLine> {
    val lines = ArrayList<SequenceExposureLine>()
    var filter = "—"
    fun walk(node: NinaNode, multiplier: Int) {
        when (node.className) {
            "SwitchFilter" -> filter = node.textField("ComboBoxText") ?: filter
            "TakeExposure", "TakeSubframeExposure" -> {
                val seconds = expressionNumber(node, "ExposureTime") ?: 0.0
                val type = node.textField("ImageType") ?: "LIGHT"
                lines += SequenceExposureLine(filter, seconds, multiplier.coerceAtLeast(1), type)
            }
            else -> {
                val loop = node.collectionNodes("Conditions")
                    .firstOrNull { it.className == "LoopCondition" }
                    ?.let { (expressionNumber(it, "Iterations") ?: 1.0).toInt() }
                    ?: if (node.className == "SmartExposure" || node.className == "TakeManyExposures") {
                        (expressionNumber(node, "Iterations") ?: 1.0).toInt()
                    } else {
                        1
                    }
                node.childItems().forEach { walk(it, multiplier * loop) }
            }
        }
    }
    walk(target, 1)
    return lines
}

fun nextClockTimeMillis(
    node: NinaNode,
    nowMillis: Long,
    latitudeDeg: Double? = null,
    longitudeDeg: Double? = null,
    raHours: Double? = null
): Long? {
    val provider = sequenceTimeProviderId(node)
    val offset = node.intField("MinutesOffset") ?: 0
    if (provider != "TimeProvider") {
        return SequenceEphemeris.nextProviderMillis(
            provider,
            nowMillis,
            offset,
            latitudeDeg,
            longitudeDeg,
            raHours
        )
    }
    val hours = (node.intField("Hours") ?: 0).coerceIn(0, 23)
    val minutes = (node.intField("Minutes") ?: 0).coerceIn(0, 59)
    val seconds = (node.intField("Seconds") ?: 0).coerceIn(0, 59)
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
    var target = now.withHour(hours).withMinute(minutes).withSecond(seconds).withNano(0)
        .plusMinutes(offset.toLong())
    if (!target.toInstant().isAfter(java.time.Instant.ofEpochMilli(nowMillis))) {
        target = target.plusDays(1)
    }
    return target.toInstant().toEpochMilli()
}

fun sequenceFileStem(name: String): String =
    name.trim().ifBlank { "untitled" }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .take(80)

fun sequenceSlotField(slot: SequenceSlot): String = when (slot) {
    SequenceSlot.Trigger -> "Triggers"
    SequenceSlot.Condition -> "Conditions"
    SequenceSlot.Item -> "Items"
}

fun insertSequenceSnippet(root: NinaNode, parentId: String, json: String, field: String? = null): Boolean {
    val incoming = parseNinaSequence(json)
    val parent = findSequenceNode(root, parentId) ?: return false
    val spec = SequenceCatalog.specByClass(incoming.className)
    val slotField = field ?: when {
        spec?.slot == SequenceSlot.Trigger || incoming.className.contains("Trigger") -> "Triggers"
        spec?.slot == SequenceSlot.Condition || incoming.className.endsWith("Condition") -> "Conditions"
        else -> "Items"
    }
    if (parent.className == "SequenceRootContainer" && slotField != "Triggers") return false
    val copy = cloneNode(incoming, HashMap())
    copy.fields["Parent"] = NinaValue.Ref(parentId)
    val slot = when (slotField) {
        "Conditions" -> SequenceSlot.Condition
        "Triggers" -> SequenceSlot.Trigger
        else -> SequenceSlot.Item
    }
    val collection = ensureCollection(parent, slotField, slot)
    parent.fields[slotField] = collection.copy(values = collection.values + NinaValue.Obj(copy))
    return true
}

fun addSequenceNodeAt(
    root: NinaNode,
    parentId: String,
    catalogId: String,
    field: String,
    index: Int
): Boolean {
    if (!addSequenceNode(root, parentId, catalogId)) return false
    val added = findSequenceNode(root, parentId)?.collectionNodes(field)?.lastOrNull() ?: return true
    val id = added.id ?: return true
    relocateSequenceNode(root, id, parentId, field, index)
    return true
}

fun formatRaHours(hours: Double): String {
    val sign = if (hours < 0) "-" else ""
    val abs = kotlin.math.abs(hours)
    val h = abs.toInt()
    val m = ((abs - h) * 60.0).toInt()
    val s = ((abs - h) * 60.0 - m) * 60.0
    return "%s%02dh%02dm%04.1fs".format(sign, h, m, s)
}

fun formatDecDegrees(degrees: Double): String {
    val sign = if (degrees < 0) "-" else "+"
    val abs = kotlin.math.abs(degrees)
    val d = abs.toInt()
    val m = ((abs - d) * 60.0).toInt()
    val s = ((abs - d) * 60.0 - m) * 60.0
    return "%s%02d°%02d′%04.1f″".format(sign, d, m, s)
}

fun sequenceHidesLoopSections(className: String): Boolean =
    className == "ParallelContainer" || className == "ConditionalContainer"

fun sequenceFieldText(node: NinaNode, path: String): String {
    if (node.className == "DeepSkyObjectContainer") {
        when (path) {
            "RAHours" -> return dsoRaHours(node)?.toString().orEmpty()
            "DecDegrees" -> return dsoDecDegrees(node)?.toString().orEmpty()
            "TargetName" -> return dsoTargetName(node).orEmpty()
            "PositionAngle" -> {
                val angle = dsoPositionAngle(node) ?: return ""
                return if (angle % 1.0 == 0.0) angle.toLong().toString() else angle.toString()
            }
            "RAMinutes", "RASeconds", "DecMinutes", "DecSeconds" ->
                return dsoCoordinateText(node, path)
        }
    }
    if (path == "Binning") return sequenceBinningText(node)
    if (path == "SelectedProvider") return sequenceTimeProviderId(node)
    val target = resolveFieldNode(node, path) ?: return ""
    val name = path.substringAfterLast('.')
    expressionDefinition(target, name)?.let { return it }
    return when (val value = target.fields[name]) {
        is NinaValue.Text -> value.value
        is NinaValue.Num -> if (value.integral) value.value.toLong().toString() else value.value.toString()
        is NinaValue.Bool -> value.value.toString()
        else -> ""
    }
}

fun findSequenceNode(root: NinaNode, id: String): NinaNode? {
    if (root.id == id) return root
    for (field in listOf("Items", "Conditions", "Triggers")) {
        for (child in root.collectionNodes(field)) {
            findSequenceNode(child, id)?.let { return it }
        }
    }
    return null
}

fun addSequenceNode(root: NinaNode, parentId: String, catalogId: String): Boolean {
    val parent = findSequenceNode(root, parentId) ?: return false
    val entry = catalogEntry(catalogId) ?: return false
    val field = when (entry.slot) {
        SequenceSlot.Trigger -> "Triggers"
        SequenceSlot.Condition -> "Conditions"
        SequenceSlot.Item -> if (parent.className == "SequenceRootContainer") return false else "Items"
    }
    val child = createCatalogNode(entry)
    child.fields["Parent"] = NinaValue.Ref(parentId)
    val collection = ensureCollection(parent, field, entry.slot)
    parent.fields[field] = collection.copy(values = collection.values + NinaValue.Obj(child))
    return true
}

fun duplicateSequenceNode(root: NinaNode, id: String): Boolean {
    val slot = findSlot(root, id) ?: return false
    val source = (slot.collection.values[slot.index] as? NinaValue.Obj)?.node ?: return false
    if (sequenceStructural(source)) return false
    val copy = cloneNode(source, HashMap())
    copy.fields["Status"] = NinaValue.Num(0.0, true)
    copy.fields["Parent"] = NinaValue.Ref(checkNotNull(slot.owner.id))
    val values = slot.collection.values.toMutableList()
    values.add(slot.index + 1, NinaValue.Obj(copy))
    slot.owner.fields[slot.field] = slot.collection.copy(values = values)
    return true
}

fun deleteSequenceNode(root: NinaNode, id: String): Boolean {
    val slot = findSlot(root, id) ?: return false
    val source = (slot.collection.values[slot.index] as? NinaValue.Obj)?.node ?: return false
    if (sequenceStructural(source)) return false
    val values = slot.collection.values.filterIndexed { index, _ -> index != slot.index }
    slot.owner.fields[slot.field] = slot.collection.copy(values = values)
    return true
}

fun moveSequenceNode(root: NinaNode, id: String, up: Boolean): Boolean {
    val slot = findSlot(root, id) ?: return false
    if (up && slot.index == 0) return false
    if (!up && slot.index >= slot.collection.values.lastIndex) return false
    val ownerId = slot.owner.id ?: return false
    val insertion = if (up) slot.index - 1 else slot.index + 2
    return relocateSequenceNode(root, id, ownerId, slot.field, insertion)
}

fun relocateSequenceNode(root: NinaNode, id: String, targetParentId: String, field: String, index: Int): Boolean {
    if (field != "Items" && field != "Conditions" && field != "Triggers") return false
    val slot = findSlot(root, id) ?: return false
    if (slot.field != field) return false
    val node = (slot.collection.values[slot.index] as? NinaValue.Obj)?.node ?: return false
    if (sequenceStructural(node)) return false
    val targetParent = findSequenceNode(root, targetParentId) ?: return false
    if (targetParent.className == "SequenceRootContainer" && field != "Triggers") return false
    if (subtreeContains(node, targetParentId)) return false
    val sourceValues = slot.collection.values.toMutableList()
    val moved = sourceValues.removeAt(slot.index)
    if (slot.owner.id == targetParentId) {
        var insertAt = index
        if (slot.index < insertAt) insertAt -= 1
        val clamped = insertAt.coerceIn(0, sourceValues.size)
        if (clamped == slot.index) return false
        sourceValues.add(clamped, moved)
        slot.owner.fields[field] = slot.collection.copy(values = sourceValues)
        return true
    }
    slot.owner.fields[field] = slot.collection.copy(values = sourceValues)
    node.fields["Parent"] = NinaValue.Ref(targetParentId)
    val targetSlot = when (field) {
        "Conditions" -> SequenceSlot.Condition
        "Triggers" -> SequenceSlot.Trigger
        else -> SequenceSlot.Item
    }
    val targetCollection = ensureCollection(targetParent, field, targetSlot)
    val targetValues = targetCollection.values.toMutableList()
    targetValues.add(index.coerceIn(0, targetValues.size), moved)
    targetParent.fields[field] = targetCollection.copy(values = targetValues)
    return true
}

private fun subtreeContains(node: NinaNode, id: String): Boolean {
    if (node.id == id) return true
    return listOf("Items", "Conditions", "Triggers").any { field ->
        node.collectionNodes(field).any { subtreeContains(it, id) }
    }
}

fun setSequenceDisabled(root: NinaNode, id: String, disabled: Boolean): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    if (node.className == "SequenceRootContainer") return false
    node.fields["Status"] = NinaValue.Num(if (disabled) SEQUENCE_STATUS_DISABLED.toDouble() else 0.0, true)
    return true
}

fun setSequenceField(root: NinaNode, id: String, path: String, text: String): Boolean {
    val node = findSequenceNode(root, id) ?: return false
    if (node.className == "DeepSkyObjectContainer") {
        if (path == "RAHours") {
            val coordinates = dsoCoordinates(node) ?: return false
            val hours = text.toDoubleOrNull() ?: return false
            writeDsoEquatorial(coordinates, hours, dsoDecDegrees(node) ?: 0.0)
            return true
        }
        if (path == "DecDegrees") {
            val coordinates = dsoCoordinates(node) ?: return false
            val degrees = text.toDoubleOrNull() ?: return false
            writeDsoEquatorial(coordinates, dsoRaHours(node) ?: 0.0, degrees)
            return true
        }
        if (path == "TargetName") {
            val target = (node.fields["Target"] as? NinaValue.Obj)?.node ?: return false
            writeScalar(target, "TargetName", text)
            return true
        }
        if (path == "PositionAngle") {
            val target = (node.fields["Target"] as? NinaValue.Obj)?.node ?: return false
            writeScalar(target, "PositionAngle", text)
            return true
        }
        if (path in setOf("RAMinutes", "RASeconds", "DecMinutes", "DecSeconds")) {
            return setDsoCoordinatePart(root, id, path, text)
        }
    }
    if (path == "Binning") return setSequenceBinning(root, id, text)
    if (path == "SelectedProvider") return setSequenceTimeProvider(root, id, text)
    val target = resolveFieldNode(node, path) ?: return false
    writeScalar(target, path.substringAfterLast('.'), text)
    if (node.className in setOf("SmartExposure", "TakeManyExposures") && path == "Iterations") {
        node.collectionNodes("Conditions").firstOrNull { it.className == "LoopCondition" }?.let { loop ->
            writeScalar(loop, "Iterations", text)
        }
    }
    return true
}

private data class NodeSlot(val owner: NinaNode, val field: String, val index: Int, val collection: NinaValue.Collection)

private fun findSlot(root: NinaNode, id: String): NodeSlot? {
    for (field in listOf("Items", "Conditions", "Triggers")) {
        val collection = root.fields[field] as? NinaValue.Collection ?: continue
        collection.values.forEachIndexed { index, value ->
            val child = (value as? NinaValue.Obj)?.node ?: return@forEachIndexed
            if (child.id == id) return NodeSlot(root, field, index, collection)
            findSlot(child, id)?.let { return it }
        }
    }
    return null
}

private fun createCatalogNode(entry: SequenceCatalogEntry): NinaNode = SequenceCatalog.create(entry.id)

private fun ensureCollection(parent: NinaNode, field: String, slot: SequenceSlot): NinaValue.Collection {
    val existing = parent.fields[field] as? NinaValue.Collection
    if (existing != null) return existing
    return catalogCollection(slotElementType(slot))
}

private fun cloneNode(node: NinaNode, ids: HashMap<String, String>): NinaNode {
    val newId = node.id?.let { old -> ids.getOrPut(old) { nextSequenceEditId() } }
    val fields = LinkedHashMap<String, NinaValue>()
    node.fields.forEach { (key, value) -> fields[key] = cloneValue(value, ids) }
    return NinaNode(node.type, newId, fields)
}

private fun cloneValue(value: NinaValue, ids: HashMap<String, String>): NinaValue = when (value) {
    NinaValue.Null -> NinaValue.Null
    is NinaValue.Bool -> value
    is NinaValue.Num -> value
    is NinaValue.Text -> value
    is NinaValue.Ref -> NinaValue.Ref(ids[value.id] ?: value.id)
    is NinaValue.Obj -> NinaValue.Obj(cloneNode(value.node, ids))
    is NinaValue.Array -> NinaValue.Array(value.values.map { cloneValue(it, ids) })
    is NinaValue.Collection -> NinaValue.Collection(
        type = value.type,
        id = value.id?.let { old -> ids.getOrPut(old) { nextSequenceEditId() } },
        values = value.values.map { cloneValue(it, ids) }
    )
}

private fun dsoCoordinates(node: NinaNode): NinaNode? =
    ((node.fields["Target"] as? NinaValue.Obj)?.node?.fields?.get("InputCoordinates") as? NinaValue.Obj)?.node

private fun writeDsoEquatorial(coordinates: NinaNode, raHours: Double, decDeg: Double) {
    val ra = splitSexagesimal(raHours.coerceIn(0.0, 24.0))
    val decAbs = splitSexagesimal(kotlin.math.abs(decDeg))
    val signedDegrees = if (decDeg < 0) -decAbs.first else decAbs.first
    coordinates.fields["RAHours"] = NinaValue.Num(ra.first.toDouble(), true)
    coordinates.fields["RAMinutes"] = NinaValue.Num(ra.second.toDouble(), true)
    coordinates.fields["RASeconds"] = NinaValue.Num(ra.third, integral = ra.third % 1.0 == 0.0)
    coordinates.fields["NegativeDec"] = NinaValue.Bool(decDeg < 0)
    coordinates.fields["DecDegrees"] = NinaValue.Num(signedDegrees.toDouble(), true)
    coordinates.fields["DecMinutes"] = NinaValue.Num(decAbs.second.toDouble(), true)
    coordinates.fields["DecSeconds"] = NinaValue.Num(decAbs.third, integral = decAbs.third % 1.0 == 0.0)
}

private fun resolveFieldNode(node: NinaNode, path: String): NinaNode? {
    if (!path.contains('.')) return node
    var current: NinaNode? = node
    val parts = path.split('.')
    for (part in parts.dropLast(1)) {
        current = (current?.fields?.get(part) as? NinaValue.Obj)?.node
    }
    return current
}

private fun writeScalar(node: NinaNode, name: String, text: String) {
    val number = text.toDoubleOrNull()
    val hasExpression = node.fields.containsKey("${name}Expression") ||
        node.fields.containsKey("${name}Definition") ||
        name in setOf(
            "ExposureTime", "Gain", "Offset", "Temperature", "Duration",
            "Iterations", "AfterExposures", "Amount", "DistanceArcMinutes",
            "SampleSize", "Position"
        )
    if (hasExpression) {
        putExpression(node.fields, name, text)
        return
    }
    node.fields[name] = if (number != null) {
        NinaValue.Num(number, integral = number % 1.0 == 0.0)
    } else {
        NinaValue.Text(text)
    }
}
