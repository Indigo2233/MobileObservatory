package com.indigo.mobileobservatory.ui.screens.sequence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.collectionNodes

internal data class DragRow(val parentId: String, val field: String, val index: Int, val bounds: Rect)

internal data class DragList(val parentId: String, val field: String, val count: Int, val bounds: Rect)

internal class SequenceDragState {
    var active by mutableStateOf(false)
    var nodeId by mutableStateOf<String?>(null)
    var field by mutableStateOf("Items")
    var title by mutableStateOf("")
    var point by mutableStateOf(Offset.Zero)
    var hoverParent by mutableStateOf<String?>(null)
    var hoverIndex by mutableStateOf(0)
    var gapPx by mutableStateOf(16f)
    var trashBounds by mutableStateOf(Rect.Zero)
    var overTrash by mutableStateOf(false)
    var catalogId by mutableStateOf<String?>(null)
    private val forbidden = HashSet<String>()
    private val rows = mutableStateMapOf<String, DragRow>()
    private val lists = mutableStateMapOf<String, DragList>()

    fun putRow(parentId: String, field: String, index: Int, bounds: Rect) {
        val key = "$parentId|$field|$index"
        val current = rows[key]
        if (current == null || current.bounds != bounds) rows[key] = DragRow(parentId, field, index, bounds)
    }

    fun putList(parentId: String, field: String, count: Int, bounds: Rect) {
        val key = "$parentId|$field"
        val current = lists[key]
        if (current == null || current.bounds != bounds || current.count != count) {
            lists[key] = DragList(parentId, field, count, bounds)
        }
    }

    fun putTrash(bounds: Rect) {
        if (trashBounds != bounds) trashBounds = bounds
    }

    fun begin(node: NinaNode, field: String, title: String, origin: Offset) {
        active = true
        nodeId = node.id
        catalogId = null
        this.field = field
        this.title = title
        point = origin
        overTrash = false
        forbidden.clear()
        collectIds(node)
        updateHover()
    }

    fun beginCatalog(id: String, field: String, title: String, origin: Offset) {
        active = true
        nodeId = null
        catalogId = id
        this.field = field
        this.title = title
        point = origin
        overTrash = false
        forbidden.clear()
        updateHover()
    }

    fun cancel() {
        active = false
        nodeId = null
        catalogId = null
        hoverParent = null
        overTrash = false
        forbidden.clear()
        rows.clear()
        lists.clear()
    }

    fun updateHover() {
        if (!active) return
        overTrash = trashBounds != Rect.Zero && trashBounds.contains(point)
        if (overTrash) {
            hoverParent = null
            return
        }
        val gap = gapPx
        val rowHits = rows.values.filter { row ->
            row.field == field &&
                row.parentId !in forbidden &&
                growY(row.bounds, gap).contains(point)
        }
        val listHits = lists.values.filter { zone ->
            zone.field == field &&
                zone.parentId !in forbidden &&
                growY(zone.bounds, gap).contains(point)
        }
        val list = listHits.minByOrNull { it.bounds.height }
        val rowsHere = if (list == null) {
            emptyList()
        } else {
            rowHits.filter { it.parentId == list.parentId && it.index < list.count }
        }
        if (list != null && rowsHere.isNotEmpty()) {
            val row = rowsHere.minBy { kotlin.math.abs(it.bounds.center.y - point.y) }
            hoverParent = row.parentId
            hoverIndex = if (point.y < row.bounds.center.y) row.index else row.index + 1
        } else if (list != null) {
            hoverParent = list.parentId
            hoverIndex = list.count
        } else {
            hoverParent = null
        }
    }

    private fun collectIds(node: NinaNode) {
        node.id?.let { forbidden += it }
        listOf("Items", "Conditions", "Triggers").forEach { field ->
            node.collectionNodes(field).forEach { collectIds(it) }
        }
    }
}

private fun growY(bounds: Rect, px: Float) = Rect(bounds.left, bounds.top - px, bounds.right, bounds.bottom + px)

internal val LocalSequenceDrag = staticCompositionLocalOf<SequenceDragState?> { null }
