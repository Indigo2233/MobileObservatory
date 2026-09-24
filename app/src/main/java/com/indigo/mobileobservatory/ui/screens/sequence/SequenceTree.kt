@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens.sequence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.SequenceEditField
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.addSequenceNode
import com.indigo.mobileobservatory.sequence.catalog.FieldKind
import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import com.indigo.mobileobservatory.sequence.collectionNodes
import com.indigo.mobileobservatory.sequence.deleteSequenceNode
import com.indigo.mobileobservatory.sequence.SequenceSkyTarget
import com.indigo.mobileobservatory.sequence.dsoDecDegrees
import com.indigo.mobileobservatory.sequence.dsoPositionAngle
import com.indigo.mobileobservatory.sequence.dsoRaHours
import com.indigo.mobileobservatory.sequence.dsoTargetName
import com.indigo.mobileobservatory.sequence.duplicateSequenceNode
import com.indigo.mobileobservatory.sequence.editableFields
import com.indigo.mobileobservatory.sequence.formatDecDegrees
import com.indigo.mobileobservatory.sequence.formatRaHours
import com.indigo.mobileobservatory.sequence.moveSequenceNode
import com.indigo.mobileobservatory.sequence.relocateSequenceNode
import com.indigo.mobileobservatory.sequence.resetSequenceProgress
import com.indigo.mobileobservatory.sequence.sequenceExpanded
import com.indigo.mobileobservatory.sequence.sequenceFieldText
import com.indigo.mobileobservatory.sequence.sequenceHidesLoopSections
import com.indigo.mobileobservatory.sequence.sequenceNodeDisabled
import com.indigo.mobileobservatory.sequence.sequenceNodeTitle
import com.indigo.mobileobservatory.sequence.sequenceParamSummary
import com.indigo.mobileobservatory.sequence.setSequenceDisabled
import com.indigo.mobileobservatory.sequence.setSequenceExpanded
import com.indigo.mobileobservatory.sequence.setSequenceField
import com.indigo.mobileobservatory.sequence.targetAltitudeCurve
import com.indigo.mobileobservatory.sequence.tonightWindow
import java.time.Instant

internal data class SequenceTreeActions(
    val chinese: Boolean,
    val enabled: Boolean,
    val selectedId: String?,
    val issues: Map<String, List<String>>,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val chartColor: Color,
    val skyTarget: SequenceSkyTarget? = null,
    val onSelect: (String) -> Unit,
    val onAdd: (String, SequenceSlot) -> Unit,
    val onEdit: ((NinaNode) -> Boolean) -> Unit,
    val onApplySkyTarget: (String) -> Unit = {}
)

private val nestColors = listOf(
    Color(0xFF7E57C2),
    Color(0xFF26A69A),
    Color(0xFF5C6BC0),
    Color(0xFFEF6C00)
)

@Composable
internal fun SequenceArea(
    title: String,
    emptyHint: String,
    area: NinaNode,
    targetArea: Boolean,
    depth: Int,
    actions: SequenceTreeActions
) {
    val areaId = area.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("◆", color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (areaId != null) {
                if (targetArea) {
                    TextButton(
                        onClick = { actions.onEdit { addSequenceNode(it, areaId, "DeepSkyObjectContainer") } },
                        enabled = actions.enabled
                    ) { Text(stringResource(R.string.sequence_add_target)) }
                }
                TextButton(
                    onClick = { actions.onAdd(areaId, SequenceSlot.Item) },
                    enabled = actions.enabled
                ) { Text("＋") }
            }
        }
        NodeList(areaId, "Items", area.collectionNodes("Items"), emptyHint, depth, actions)
        if (areaId != null) SequenceLoopSections(areaId, area, actions)
    }
}

@Composable
internal fun SequenceGlobalTriggers(root: NinaNode, actions: SequenceTreeActions) {
    val rootId = root.id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ ${stringResource(R.string.sequence_global_triggers)}", modifier = Modifier.weight(1f))
            if (rootId != null) {
                TextButton(
                    onClick = { actions.onAdd(rootId, SequenceSlot.Trigger) },
                    enabled = actions.enabled
                ) { Text("＋") }
            }
        }
        NodeList(
            rootId,
            "Triggers",
            root.collectionNodes("Triggers"),
            stringResource(R.string.sequence_empty_triggers),
            0,
            actions
        )
    }
}

@Composable
private fun SequenceLoopSections(parentId: String, node: NinaNode, actions: SequenceTreeActions) {
    if (sequenceHidesLoopSections(node.className)) return
    SectionBlock("⚡ ${stringResource(R.string.sequence_triggers)}", parentId, "Triggers", node.collectionNodes("Triggers"), SequenceSlot.Trigger, actions)
    SectionBlock("☰ ${stringResource(R.string.sequence_conditions)}", parentId, "Conditions", node.collectionNodes("Conditions"), SequenceSlot.Condition, actions)
}

@Composable
private fun SectionBlock(
    title: String,
    parentId: String,
    field: String,
    nodes: List<NinaNode>,
    slot: SequenceSlot,
    actions: SequenceTreeActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onAdd(parentId, slot) }, enabled = actions.enabled) { Text("＋") }
        }
        NodeList(
            parentId,
            field,
            nodes,
            if (slot == SequenceSlot.Trigger) stringResource(R.string.sequence_add_trigger)
            else stringResource(R.string.sequence_add_condition),
            0,
            actions
        )
    }
}

@Composable
private fun NodeList(
    parentId: String?,
    field: String,
    nodes: List<NinaNode>,
    emptyLabel: String?,
    depth: Int,
    actions: SequenceTreeActions
) {
    val drag = LocalSequenceDrag.current
    val armed = drag?.active == true && drag.field == field && drag.hoverParent == parentId && nodes.isEmpty()
    val highlight = if (armed) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
    Column(
        Modifier.fillMaxWidth().dropList(parentId, field, nodes.size).drawBehind { drawRect(highlight) },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (nodes.isEmpty() && !emptyLabel.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) {
                Text(emptyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        nodes.forEachIndexed { index, child ->
            val dragging = drag?.active == true && drag.nodeId == child.id
            Box(
                Modifier.fillMaxWidth().alpha(if (dragging) 0.4f else 1f)
                    .asDropRow(parentId, field, index, index == nodes.lastIndex)
            ) {
                when {
                    child.className == "DeepSkyObjectContainer" -> TargetBlock(child, depth, actions)
                    SequenceCatalog.isSet(child.className) || child.className.endsWith("Container") ->
                        NestedSet(child, depth, actions)
                    else -> InstructionRow(child, field, actions, showFields = true)
                }
            }
        }
    }
}

@Composable
private fun NestedSet(node: NinaNode, depth: Int, actions: SequenceTreeActions) {
    val id = node.id
    val expanded = sequenceExpanded(node)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(nestColors[depth % nestColors.size], size = Size(4.dp.toPx(), size.height))
            }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        InstructionRow(node, "Items", actions, showFields = expanded, showFold = true)
        if (expanded && id != null) {
            SequenceLoopSections(id, node, actions)
            Text("▤ ${stringResource(R.string.sequence_instructions)}", style = MaterialTheme.typography.labelLarge)
            NodeList(id, "Items", node.collectionNodes("Items"), stringResource(R.string.sequence_empty), depth + 1, actions)
            TextButton(
                onClick = { actions.onAdd(id, SequenceSlot.Item) },
                enabled = actions.enabled
            ) { Text(stringResource(R.string.sequence_add_instruction)) }
        }
    }
}

@Composable
private fun TargetBlock(target: NinaNode, depth: Int, actions: SequenceTreeActions) {
    val id = target.id
    val expanded = sequenceExpanded(target)
    val name = dsoTargetName(target) ?: sequenceNodeTitle(target, actions.chinese)
    val ra = dsoRaHours(target)
    val dec = dsoDecDegrees(target)
    val positionAngle = dsoPositionAngle(target)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(nestColors[depth % nestColors.size], size = Size(4.dp.toPx(), size.height))
            }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
            InstructionRow(target, "Items", actions, showFields = false, showFold = true)
            Text(
                buildString {
                    append(name)
                    if (ra != null) append("  |  ${formatRaHours(ra)}")
                    if (dec != null) append("  |  ${formatDecDegrees(dec)}")
                    if (positionAngle != null) append("  |  ${positionAngle}°")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (expanded && id != null) {
                FieldEditor(target, SequenceEditField("TargetName", "目标名", "Target name"), actions)
                FieldEditor(target, SequenceEditField("RAHours", "赤经时", "RA hours"), actions)
                FieldEditor(target, SequenceEditField("DecDegrees", "赤纬", "Dec"), actions)
                FieldEditor(target, SequenceEditField("PositionAngle", "位置角", "Position angle"), actions)
                val sky = actions.skyTarget
                TextButton(
                    onClick = { actions.onApplySkyTarget(id) },
                    enabled = actions.enabled && sky != null
                ) {
                    Text(
                        if (sky == null) {
                            stringResource(R.string.sequence_from_star_map_empty)
                        } else {
                            stringResource(R.string.sequence_from_star_map, sky.name)
                        }
                    )
                }
                val lat = actions.latitudeDeg
                val lon = actions.longitudeDeg
                if (lat != null && lon != null && ra != null && dec != null) {
                    val window = tonightWindow(Instant.now())
                    val curve = targetAltitudeCurve(ra, dec, lat, lon, window.first, window.second).map { it.altitudeDeg }
                    SequenceSparkline(curve, actions.chartColor, Modifier.fillMaxWidth().height(96.dp))
                }
                SequenceLoopSections(id, target, actions)
                Text("▤ ${stringResource(R.string.sequence_instructions)}", style = MaterialTheme.typography.labelLarge)
                NodeList(id, "Items", target.collectionNodes("Items"), stringResource(R.string.sequence_empty), depth + 1, actions)
                Button(onClick = { actions.onAdd(id, SequenceSlot.Item) }, enabled = actions.enabled) {
                    Text(stringResource(R.string.sequence_add_instruction))
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstructionRow(
    node: NinaNode,
    field: String,
    actions: SequenceTreeActions,
    showFields: Boolean,
    showFold: Boolean = false
) {
    val id = node.id
    val disabled = sequenceNodeDisabled(node)
    val title = sequenceNodeTitle(node, actions.chinese)
    val summary = sequenceParamSummary(node, actions.chinese)
    val selected = id != null && id == actions.selectedId
    val nodeIssues = id?.let { actions.issues[it] }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (id != null) DragHandle(node, field, title, actions.enabled, actions.onEdit)
            if (showFold && id != null) {
                IconButton(
                    onClick = { actions.onEdit { setSequenceExpanded(it, id, !sequenceExpanded(node)) } },
                    enabled = actions.enabled
                ) {
                    Icon(
                        if (sequenceExpanded(node)) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            Column(
                Modifier.weight(1f).clickable(enabled = id != null) { if (id != null) actions.onSelect(id) }
            ) {
                Text(
                    title,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (disabled) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (disabled) 0.55f else 1f)
                )
                if (!disabled && summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (nodeIssues.isNotEmpty()) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = nodeIssues.first(),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (selected && id != null) {
            ActionBar(node, id, actions)
            if (showFields && !disabled) {
                editableFields(node).forEach { spec ->
                    if (spec.path != "ErrorBehavior" && spec.path != "Attempts") {
                        FieldEditor(node, spec, actions)
                    }
                }
            }
            nodeIssues.forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionBar(node: NinaNode, id: String, actions: SequenceTreeActions) {
    val disabled = sequenceNodeDisabled(node)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (disabled) {
            Button(onClick = { actions.onEdit { setSequenceDisabled(it, id, false) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_enable))
            }
        } else {
            FieldEditor(node, SequenceEditField("Attempts", "重试", "Attempts"), actions)
            ErrorBehaviorChips(node, id, actions)
            TextButton(onClick = { actions.onEdit { setSequenceDisabled(it, id, true) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_disable))
            }
            TextButton(onClick = { actions.onEdit { resetSequenceProgress(it, id) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_reset_progress))
            }
            TextButton(onClick = { actions.onEdit { duplicateSequenceNode(it, id) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_copy))
            }
            TextButton(onClick = { actions.onEdit { moveSequenceNode(it, id, true) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_move_up))
            }
            TextButton(onClick = { actions.onEdit { moveSequenceNode(it, id, false) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_move_down))
            }
            TextButton(onClick = { actions.onEdit { deleteSequenceNode(it, id) } }, enabled = actions.enabled) {
                Text(stringResource(R.string.sequence_delete))
            }
        }
    }
}

@Composable
private fun ErrorBehaviorChips(node: NinaNode, id: String, actions: SequenceTreeActions) {
    val current = sequenceFieldText(node, "ErrorBehavior").ifBlank { "0" }
    val options = listOf(
        "0" to stringResource(R.string.sequence_error_continue),
        "1" to stringResource(R.string.sequence_error_skip_set),
        "2" to stringResource(R.string.sequence_error_abort),
        "3" to stringResource(R.string.sequence_error_skip_end)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = { if (actions.enabled) actions.onEdit { setSequenceField(it, id, "ErrorBehavior", value) } },
                label = { Text(label) },
                enabled = actions.enabled
            )
        }
    }
}

@Composable
private fun FieldEditor(node: NinaNode, field: SequenceEditField, actions: SequenceTreeActions) {
    val id = node.id ?: return
    val spec = SequenceCatalog.specByClass(node.className)?.fields?.firstOrNull { it.editPath == field.path }
    var text by remember(id, field.path) { mutableStateOf(sequenceFieldText(node, field.path)) }
    val label = (if (actions.chinese) field.labelZh else field.labelEn) + (spec?.unit?.let { " ($it)" } ?: "")
    if (field.path == "ImageType") {
        val options = listOf("LIGHT", "FLAT", "DARK", "BIAS", "SNAPSHOT")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { value ->
                FilterChip(
                    selected = text == value,
                    onClick = {
                        text = value
                        if (actions.enabled) actions.onEdit { setSequenceField(it, id, field.path, value) }
                    },
                    label = { Text(value) },
                    enabled = actions.enabled
                )
            }
        }
        return
    }
    if (spec?.kind == FieldKind.Bool) {
        FilterChip(
            selected = text.equals("true", ignoreCase = true),
            onClick = {
                val next = if (text.equals("true", ignoreCase = true)) "false" else "true"
                text = next
                if (actions.enabled) actions.onEdit { setSequenceField(it, id, field.path, next) }
            },
            label = { Text(label) },
            enabled = actions.enabled
        )
        return
    }
    val unsupported = spec?.kind == FieldKind.Expression && text.isNotBlank() && text.toDoubleOrNull() == null
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            if (actions.enabled) actions.onEdit { setSequenceField(it, id, field.path, next) }
        },
        enabled = actions.enabled && !unsupported,
        label = { Text(label) },
        supportingText = if (unsupported) {
            { Text(stringResource(R.string.sequence_expression_unsupported)) }
        } else {
            null
        },
        isError = unsupported ||
            (spec?.min != null && text.toDoubleOrNull()?.let { it < spec.min } == true) ||
            (spec?.max != null && text.toDoubleOrNull()?.let { it > spec.max } == true),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DragHandle(
    node: NinaNode,
    field: String,
    title: String,
    enabled: Boolean,
    onEdit: ((NinaNode) -> Boolean) -> Unit
) {
    val drag = LocalSequenceDrag.current ?: return
    val coords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Box(
        Modifier.size(48.dp).onGloballyPositioned { coords.value = it }.pointerInput(node.id, field, enabled) {
            if (!enabled) return@pointerInput
            trackDrag(
                onStart = { local ->
                    val layout = coords.value ?: return@trackDrag
                    drag.begin(node, field, title, layout.localToRoot(local))
                },
                onMove = { delta ->
                    drag.point += delta
                    drag.updateHover()
                },
                onEnd = {
                    val id = drag.nodeId
                    val trash = drag.overTrash
                    val parent = drag.hoverParent
                    val targetField = drag.field
                    val index = drag.hoverIndex
                    drag.cancel()
                    if (id != null && trash) {
                        onEdit { deleteSequenceNode(it, id) }
                    } else if (id != null && parent != null) {
                        onEdit { relocateSequenceNode(it, id, parent, targetField, index) }
                    }
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.sequence_drag), tint = MaterialTheme.colorScheme.outline)
    }
}

internal suspend fun PointerInputScope.trackDrag(
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onStart(down.position)
        val pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                onEnd()
                break
            }
            val delta = change.position - change.previousPosition
            if (delta != Offset.Zero) onMove(delta)
            change.consume()
        }
    }
}

@Composable
internal fun Modifier.dropList(parentId: String?, field: String, count: Int): Modifier {
    val drag = LocalSequenceDrag.current ?: return this
    if (parentId == null) return this
    return this.onGloballyPositioned { drag.putList(parentId, field, count, it.boundsInRoot()) }
}

@Composable
internal fun Modifier.asDropRow(parentId: String?, field: String, index: Int, last: Boolean): Modifier {
    val drag = LocalSequenceDrag.current ?: return this
    if (parentId == null) return this
    val color = MaterialTheme.colorScheme.primary
    return this
        .onGloballyPositioned { drag.putRow(parentId, field, index, it.boundsInRoot()) }
        .drawBehind {
            if (!drag.active || drag.field != field || drag.hoverParent != parentId) return@drawBehind
            val stroke = 4.dp.toPx()
            if (drag.hoverIndex == index) drawRect(color, size = Size(size.width, stroke))
            if (last && drag.hoverIndex == index + 1) {
                drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            }
        }
}

@Composable
internal fun SequenceSparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).coerceAtLeast(1)
            val y = size.height - ((value - min) / span).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
