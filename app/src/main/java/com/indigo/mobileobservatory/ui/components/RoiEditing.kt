package com.indigo.mobileobservatory.ui.components

import androidx.compose.ui.geometry.Rect
import com.indigo.mobileobservatory.camera.Roi
import kotlin.math.max
import kotlin.math.min

internal data class RoiEditingLimits(
    val sensorWidth: Int,
    val sensorHeight: Int,
    val minWidth: Int,
    val minHeight: Int,
    val widthStep: Int,
    val heightStep: Int,
    val offsetXStep: Int,
    val offsetYStep: Int
)

internal object RoiEditing {
    fun clamp(roi: Roi, limits: RoiEditingLimits): Roi {
        val sensorWidth = limits.sensorWidth.coerceAtLeast(1)
        val sensorHeight = limits.sensorHeight.coerceAtLeast(1)
        val width = roi.width.coerceIn(1, sensorWidth)
        val height = roi.height.coerceIn(1, sensorHeight)
        val x = roi.x.coerceIn(0, sensorWidth - width)
        val y = roi.y.coerceIn(0, sensorHeight - height)
        return Roi(x, y, width, height)
    }

    fun normalize(roi: Roi, limits: RoiEditingLimits): Roi {
        val sensorWidth = limits.sensorWidth.coerceAtLeast(1)
        val sensorHeight = limits.sensorHeight.coerceAtLeast(1)
        if (roi.x == 0 && roi.y == 0 && roi.width == sensorWidth && roi.height == sensorHeight) {
            return Roi(0, 0, sensorWidth, sensorHeight)
        }
        val widthStep = limits.widthStep.coerceAtLeast(1)
        val heightStep = limits.heightStep.coerceAtLeast(1)
        val offsetXStep = limits.offsetXStep.coerceAtLeast(1)
        val offsetYStep = limits.offsetYStep.coerceAtLeast(1)
        val (minWidth, maxWidth) = sizeBounds(sensorWidth, limits.minWidth, widthStep)
        val (minHeight, maxHeight) = sizeBounds(sensorHeight, limits.minHeight, heightStep)

        val width = alignDown(
            roi.width.coerceIn(minWidth, maxWidth),
            widthStep
        ).coerceAtLeast(minWidth)
        val height = alignDown(
            roi.height.coerceIn(minHeight, maxHeight),
            heightStep
        ).coerceAtLeast(minHeight)
        val x = alignDown(roi.x.coerceIn(0, (sensorWidth - width).coerceAtLeast(0)), offsetXStep)
        val y = alignDown(roi.y.coerceIn(0, (sensorHeight - height).coerceAtLeast(0)), offsetYStep)
        return Roi(x, y, width, height)
    }

    fun fullFrame(limits: RoiEditingLimits): Roi =
        Roi(0, 0, limits.sensorWidth.coerceAtLeast(1), limits.sensorHeight.coerceAtLeast(1))

    fun resizeWidth(roi: Roi, requestedWidth: Int, limits: RoiEditingLimits): Roi {
        val (minWidth, maxWidth) = sizeBounds(
            limits.sensorWidth,
            limits.minWidth,
            limits.widthStep
        )
        val width = alignDown(
            requestedWidth.coerceIn(minWidth, maxWidth),
            limits.widthStep.coerceAtLeast(1)
        ).coerceAtLeast(minWidth)
        val centerX = roi.x + roi.width / 2
        val x = alignDown(centerX - width / 2, limits.offsetXStep.coerceAtLeast(1))
        return normalize(roi.copy(x = x, width = width), limits)
    }

    fun resizeHeight(roi: Roi, requestedHeight: Int, limits: RoiEditingLimits): Roi {
        val (minHeight, maxHeight) = sizeBounds(
            limits.sensorHeight,
            limits.minHeight,
            limits.heightStep
        )
        val height = alignDown(
            requestedHeight.coerceIn(minHeight, maxHeight),
            limits.heightStep.coerceAtLeast(1)
        ).coerceAtLeast(minHeight)
        val centerY = roi.y + roi.height / 2
        val y = alignDown(centerY - height / 2, limits.offsetYStep.coerceAtLeast(1))
        return normalize(roi.copy(y = y, height = height), limits)
    }

    fun moveX(roi: Roi, requestedX: Int, limits: RoiEditingLimits): Roi =
        normalize(roi.copy(x = requestedX), limits)

    fun moveY(roi: Roi, requestedY: Int, limits: RoiEditingLimits): Roi =
        normalize(roi.copy(y = requestedY), limits)

    fun previewRect(proposed: Roi, source: Roi, imageWidth: Float, imageHeight: Float): Rect? {
        if (source.width <= 0 || source.height <= 0 || imageWidth <= 0f || imageHeight <= 0f) return null
        val imageBounds = Rect(0f, 0f, imageWidth, imageHeight)
        val scaleX = imageWidth / source.width
        val scaleY = imageHeight / source.height
        val mapped = Rect(
            left = (proposed.x - source.x) * scaleX,
            top = (proposed.y - source.y) * scaleY,
            right = (proposed.x + proposed.width - source.x) * scaleX,
            bottom = (proposed.y + proposed.height - source.y) * scaleY
        )
        val left = max(mapped.left, imageBounds.left)
        val top = max(mapped.top, imageBounds.top)
        val right = min(mapped.right, imageBounds.right)
        val bottom = min(mapped.bottom, imageBounds.bottom)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    /** Returns geometry whose centered stroke lands its inner edge on [inner]. */
    fun outerStrokeRect(inner: Rect, strokeWidth: Float): Rect {
        val half = strokeWidth.coerceAtLeast(0f) / 2f
        return Rect(
            left = inner.left - half,
            top = inner.top - half,
            right = inner.right + half,
            bottom = inner.bottom + half
        )
    }

    /** Returns geometry whose centered stroke stays fully inside [inner]. */
    fun insetStrokeRect(inner: Rect, strokeWidth: Float): Rect {
        val insetX = min(strokeWidth.coerceAtLeast(0f) / 2f, inner.width / 2f)
        val insetY = min(strokeWidth.coerceAtLeast(0f) / 2f, inner.height / 2f)
        return Rect(
            left = inner.left + insetX,
            top = inner.top + insetY,
            right = inner.right - insetX,
            bottom = inner.bottom - insetY
        )
    }

    private fun alignDown(value: Int, step: Int): Int =
        (value / step.coerceAtLeast(1)) * step.coerceAtLeast(1)

    private fun alignUp(value: Int, step: Int): Int {
        val safeStep = step.coerceAtLeast(1)
        val remainder = value % safeStep
        return if (remainder == 0) value else value + safeStep - remainder
    }

    private fun sizeBounds(sensorSize: Int, minimum: Int, step: Int): Pair<Int, Int> {
        val safeStep = step.coerceAtLeast(1)
        val max = alignDown(sensorSize.coerceAtLeast(1), safeStep).coerceAtLeast(1)
        val min = alignUp(minimum.coerceAtLeast(1), safeStep).coerceAtMost(max)
        return min to max
    }
}
