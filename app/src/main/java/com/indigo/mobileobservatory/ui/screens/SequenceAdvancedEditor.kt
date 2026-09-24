@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.SequenceRuntime
import com.indigo.mobileobservatory.sequence.SequenceSkyTarget
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.addSequenceNode
import com.indigo.mobileobservatory.sequence.addSequenceNodeAt
import com.indigo.mobileobservatory.sequence.applyDsoSkyTarget
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import com.indigo.mobileobservatory.sequence.catalog.validateSequence
import com.indigo.mobileobservatory.sequence.collectionNodes
import com.indigo.mobileobservatory.sequence.dsoPositionAngle
import com.indigo.mobileobservatory.sequence.dsoTargetName
import com.indigo.mobileobservatory.sequence.findSequenceNode
import com.indigo.mobileobservatory.sequence.setSequenceField
import com.indigo.mobileobservatory.ui.screens.sequence.LocalSequenceDrag
import com.indigo.mobileobservatory.ui.screens.sequence.SequenceAddPanel
import com.indigo.mobileobservatory.ui.screens.sequence.SequenceArea
import com.indigo.mobileobservatory.ui.screens.sequence.SequenceDragState
import com.indigo.mobileobservatory.ui.screens.sequence.SequenceGlobalTriggers
import com.indigo.mobileobservatory.ui.screens.sequence.SequenceTreeActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class AddRequest(val parentId: String, val slot: SequenceSlot)

@Composable
fun SequenceAdvancedEditor(
    runtime: SequenceRuntime,
    enabled: Boolean,
    hardware: SequenceHardwareSnapshot = SequenceHardwareSnapshot(),
    latitudeDeg: Double? = null,
    longitudeDeg: Double? = null,
    chartColor: Color = MaterialTheme.colorScheme.primary,
    skyTarget: SequenceSkyTarget? = null,
    pointingRaHours: Double? = null,
    pointingDecDeg: Double? = null,
    modifier: Modifier = Modifier
) {
    val generation by runtime.generation.collectAsState()
    val root = runtime.document.value
    val chinese = LocalConfiguration.current.locales[0].language.startsWith("zh")
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val drag = remember { SequenceDragState() }
    val density = LocalDensity.current
    val treeScroll = rememberScrollState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<AddRequest?>(null) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val canUndo by runtime.canUndo.collectAsState()
    val canRedo by runtime.canRedo.collectAsState()
    val locked by runtime.locked.collectAsState()
    val runState by runtime.state.collectAsState()
    val editing = enabled && !locked
    if (root == null || generation < 0) return
    drag.gapPx = with(density) { 8.dp.toPx() }
    val issues = remember(generation, hardware) {
        validateSequence(root, hardware)
            .filter { issue ->
                issue.messageEn.contains("Not supported") ||
                    issue.messageEn.contains("Expression") ||
                    issue.messageEn.contains("below") ||
                    issue.messageEn.contains("above")
            }
            .groupBy { it.nodeId.orEmpty() }
            .mapValues { entry ->
                entry.value.map { if (chinese) it.messageZh else it.messageEn }
            }
    }
    val actions = SequenceTreeActions(
        chinese = chinese,
        enabled = editing,
        selectedId = selectedId,
        issues = issues,
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        chartColor = chartColor,
        skyTarget = skyTarget,
        filterNames = hardware.filterNames,
        pointingRaHours = pointingRaHours,
        pointingDecDeg = pointingDecDeg,
        runNodeId = runState.currentNodeId,
        nodeStatus = runState.nodeStatus,
        onSelect = { selectedId = it },
        onAdd = { parent, slot -> adding = AddRequest(parent, slot) },
        onEdit = { runtime.editSequence(it) },
        onApplySkyTarget = { id ->
            if (skyTarget != null) runtime.applySkyTarget(id, skyTarget)
        },
        onApplyPointing = { id ->
            val ra = pointingRaHours
            val dec = pointingDecDeg
            if (ra != null && dec != null) {
                runtime.editSequence { root ->
                    val node = findSequenceNode(root, id) ?: return@editSequence false
                    applyDsoSkyTarget(
                        root,
                        id,
                        dsoTargetName(node) ?: "Target",
                        ra,
                        dec,
                        dsoPositionAngle(node) ?: 0.0
                    )
                }
            }
        },
        onSaveTemplate = { id -> runtime.saveSetTemplate(id) },
        onSaveTarget = { id -> runtime.saveTargetSnippet(id) }
    )
    val areas = root.collectionNodes("Items")
    val start = areas.firstOrNull { it.className == "StartAreaContainer" }
    val targets = areas.firstOrNull { it.className == "TargetAreaContainer" }
    val end = areas.firstOrNull { it.className == "EndAreaContainer" }

    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        while (isActive && drag.active) {
            drag.updateHover()
            val delta = when {
                viewport.height <= 0f -> 0f
                drag.point.y < viewport.top + edge -> -48f
                drag.point.y > viewport.bottom - edge -> 48f
                else -> 0f
            }
            if (delta != 0f) treeScroll.scrollBy(delta)
            delay(16)
        }
    }

    CompositionLocalProvider(LocalSequenceDrag provides drag) {
        Column(
            modifier.fillMaxSize().onGloballyPositioned { editorOrigin = it.positionInRoot() },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditorBar(
                root = root,
                editing = editing,
                enabled = enabled,
                locked = locked,
                canUndo = canUndo,
                canRedo = canRedo,
                runtime = runtime
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(if (landscape) 0.7f else 1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { viewport = it.boundsInRoot() }
                        .verticalScroll(treeScroll)
                ) {
                    Column(Modifier.fillMaxWidth().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SequenceGlobalTriggers(root, actions)
                        if (start != null) {
                            SequenceArea(
                                title = stringResource(R.string.sequence_area_start),
                                emptyHint = stringResource(R.string.sequence_area_start_empty),
                                area = start,
                                targetArea = false,
                                depth = 0,
                                actions = actions
                            )
                        }
                        if (targets != null) {
                            SequenceArea(
                                title = stringResource(R.string.sequence_area_targets),
                                emptyHint = stringResource(R.string.sequence_area_targets_empty),
                                area = targets,
                                targetArea = true,
                                depth = 0,
                                actions = actions
                            )
                        }
                        if (end != null) {
                            SequenceArea(
                                title = stringResource(R.string.sequence_area_end),
                                emptyHint = stringResource(R.string.sequence_area_end_empty),
                                area = end,
                                targetArea = false,
                                depth = 0,
                                actions = actions
                            )
                        }
                    }
                }
                if (landscape) {
                    SequenceAddPanel(
                        slot = adding?.slot ?: SequenceSlot.Item,
                        chinese = chinese,
                        templates = runtime.templateNames(),
                        onPickInstruction = { entry ->
                            val parent = adding?.parentId ?: start?.id
                            if (parent != null && editing) {
                                runtime.editSequence { addSequenceNode(it, parent, entry.id) }
                            }
                        },
                        onPickTemplate = { runtime.load(it) },
                        skyTarget = skyTarget,
                        onAddSkyTarget = {
                            if (skyTarget != null && editing) {
                                runtime.addTarget(
                                    skyTarget.name,
                                    skyTarget.raHours,
                                    skyTarget.decDeg,
                                    skyTarget.positionAngleDeg
                                )
                            }
                        },
                        setTemplates = runtime.setTemplateNames(),
                        savedTargets = runtime.savedTargetNames(),
                        onInsertTemplate = { name ->
                            val parent = adding?.parentId ?: start?.id
                            if (parent != null && editing) runtime.insertSetTemplate(parent, name)
                        },
                        onInsertTarget = { name ->
                            val parent = adding?.parentId ?: targets?.id
                            if (parent != null && editing) runtime.insertSavedTarget(parent, name)
                        },
                        dragEnabled = editing,
                        onDropCatalog = { catalogId, parent, field, index ->
                            if (editing) {
                                runtime.editSequence { addSequenceNodeAt(it, parent, catalogId, field, index) }
                            }
                        },
                        modifier = Modifier.weight(0.3f).fillMaxHeight().padding(start = 8.dp)
                    )
                }
            }
            if (drag.active) {
                TrashBar(drag.overTrash)
                val local = drag.point - editorOrigin
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(local.x.toInt() + 36, local.y.toInt() - 20),
                    properties = PopupProperties(focusable = false, clippingEnabled = false)
                ) {
                    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(drag.title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }

    val request = adding
    if (!landscape && request != null) {
        ModalBottomSheet(onDismissRequest = { adding = null }) {
            SequenceAddPanel(
                slot = request.slot,
                chinese = chinese,
                templates = runtime.templateNames(),
                onPickInstruction = { entry ->
                    if (editing) runtime.editSequence { addSequenceNode(it, request.parentId, entry.id) }
                    adding = null
                },
                onPickTemplate = {
                    runtime.load(it)
                    adding = null
                },
                skyTarget = skyTarget,
                onAddSkyTarget = {
                    if (skyTarget != null && editing) {
                        runtime.addTarget(
                            skyTarget.name,
                            skyTarget.raHours,
                            skyTarget.decDeg,
                            skyTarget.positionAngleDeg
                        )
                    }
                    adding = null
                },
                setTemplates = runtime.setTemplateNames(),
                savedTargets = runtime.savedTargetNames(),
                onInsertTemplate = { name ->
                    if (editing) runtime.insertSetTemplate(request.parentId, name)
                    adding = null
                },
                onInsertTarget = { name ->
                    if (editing) runtime.insertSavedTarget(request.parentId, name)
                    adding = null
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
}

@Composable
private fun EditorBar(
    root: NinaNode,
    editing: Boolean,
    enabled: Boolean,
    locked: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    runtime: SequenceRuntime
) {
    val id = root.id
    var name by remember(id, root.textFieldSafe("Name")) { mutableStateOf(root.textFieldSafe("Name")) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { next ->
                name = next
                if (id != null && editing) runtime.editSequence { setSequenceField(it, id, "Name", next) }
            },
            enabled = editing,
            label = { Text(stringResource(R.string.sequence_target_name)) },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = runtime::undoEdit, enabled = editing && canUndo) {
            Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.sequence_undo))
        }
        IconButton(onClick = runtime::redoEdit, enabled = editing && canRedo) {
            Icon(Icons.Default.Redo, contentDescription = stringResource(R.string.sequence_redo))
        }
        IconButton(onClick = { runtime.setLocked(!locked) }, enabled = enabled) {
            Icon(
                if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = stringResource(if (locked) R.string.sequence_unlock else R.string.sequence_lock)
            )
        }
        TextButton(
            onClick = { runtime.save(name.ifBlank { "sequence" }) },
            enabled = editing
        ) { Text(stringResource(R.string.sequence_save)) }
    }
}

@Composable
private fun TrashBar(hot: Boolean) {
    val color = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant
    val drag = LocalSequenceDrag.current
    Surface(
        color = color,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .onGloballyPositioned { drag?.putTrash(it.boundsInRoot()) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sequence_delete))
            Text(stringResource(R.string.sequence_drop_to_delete), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun NinaNode.textFieldSafe(name: String): String =
    (fields[name] as? com.indigo.mobileobservatory.sequence.NinaValue.Text)?.value.orEmpty()
