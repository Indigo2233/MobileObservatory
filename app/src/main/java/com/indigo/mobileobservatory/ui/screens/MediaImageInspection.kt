package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaDisplayHistogram
import com.indigo.mobileobservatory.media.MediaImageEditing
import com.indigo.mobileobservatory.media.MediaImageRegion
import com.indigo.mobileobservatory.media.MediaViewportRegion
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import kotlinx.coroutines.*
import kotlin.math.ln
import kotlin.math.roundToInt

/** The same transform is applied to both images and the display-clipping overlay. */
internal class MediaInspectionNavigation { var onBack: () -> Boolean = { false } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaImageInspection(
    image: Bitmap,
    before: Bitmap?,
    imageKey: String,
    originalWidth: Int,
    originalHeight: Int,
    nativeKey: Any,
    renderNative: suspend (MediaImageRegion, Boolean) -> Bitmap,
    navigation: MediaInspectionNavigation,
    modifier: Modifier = Modifier
) {
    var zoom by rememberSaveable(imageKey) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(imageKey) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(imageKey) { mutableFloatStateOf(0f) }
    var nativeRequested by rememberSaveable(imageKey) { mutableStateOf(false) }
    var compare by rememberSaveable(imageKey) { mutableStateOf(false) }
    var histogramVisible by rememberSaveable(imageKey) { mutableStateOf(false) }
    var clippingVisible by rememberSaveable(imageKey) { mutableStateOf(false) }
    var divider by rememberSaveable(imageKey) { mutableFloatStateOf(.5f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var native by remember(imageKey, nativeKey) { mutableStateOf<Pair<MediaImageRegion, Bitmap>?>(null) }
    val regionCache = remember(imageKey, nativeKey) { LinkedHashMap<Pair<MediaImageRegion, Boolean>, Bitmap>() }
    var nativeBefore by remember(imageKey, nativeKey) { mutableStateOf<Pair<MediaImageRegion, Bitmap>?>(null) }
    var nativeError by remember(imageKey, nativeKey) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var pendingOneToOne by rememberSaveable(imageKey) { mutableStateOf(false) }
    var nativeAttempt by remember { mutableIntStateOf(0) }
    var analysisError by remember { mutableStateOf(false) }
    var histogramError by remember { mutableStateOf(false) }
    val fit = if (viewport.width > 0 && viewport.height > 0) minOf(viewport.width.toFloat() / originalWidth,
        viewport.height.toFloat() / originalHeight) else 1f
    fun reset() { zoom = 1f; panX = 0f; panY = 0f; nativeRequested = false; pendingOneToOne = false }
    SideEffect { navigation.onBack = { if (zoom != 1f || nativeRequested) { reset(); true } else false } }
    DisposableEffect(navigation) { onDispose { navigation.onBack = { false } } }
    BackHandler(zoom != 1f || nativeRequested) { reset() }
    val region = if (nativeRequested) MediaViewportRegion.visible(originalWidth, originalHeight,
        viewport.width, viewport.height, fit * zoom, panX, panY) else null
    LaunchedEffect(nativeRequested, nativeKey, compare, nativeAttempt, region) {
        if (!nativeRequested || region == null) { native = null; nativeBefore = null; regionCache.clear(); return@LaunchedEffect }
        loading = true; nativeError = false
        try {
            delay(100)
            suspend fun load(before: Boolean): Bitmap {
                val key = region to before
                regionCache.remove(key)?.let { regionCache[key] = it; return it }
                val result = renderNative(region, before)
                require(result.width == region.outputWidth && result.height == region.outputHeight)
                regionCache[key] = result
                // Current viewport + a small previous-region cache, capped by bytes and entry count.
                while (regionCache.size > 4 || regionCache.values.sumOf { it.allocationByteCount.toLong() } > 24L * 1024 * 1024) {
                    regionCache.remove(regionCache.keys.first())
                }
                return result
            }
            native = region to load(false)
            nativeBefore = if (compare && before != null) region to load(true) else null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: OutOfMemoryError) { nativeError = true; pendingOneToOne = false
        } catch (_: Exception) { nativeError = true; pendingOneToOne = false
        } finally { loading = false }
    }
    LaunchedEffect(viewport, pendingOneToOne) {
        if (pendingOneToOne && viewport != IntSize.Zero) {
            zoom = 1f / fit; panX = 0f; panY = 0f; pendingOneToOne = false
        }
    }
    val stats by produceState<MediaDisplayHistogram?>(null, image, histogramVisible, clippingVisible) {
        try {
            value = if (histogramVisible || clippingVisible) withContext(Dispatchers.Default) { MediaDisplayHistogram.read(image) } else null
            histogramError = false
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: OutOfMemoryError) { value = null; histogramError = true
        } catch (_: Exception) { value = null; histogramError = true }
    }
    val clipping by produceState<Bitmap?>(null, image, clippingVisible) {
        try {
            value = if (clippingVisible) withContext(Dispatchers.Default) {
                MediaImageEditing.checkMemory(image.width.toLong() * image.height * 4L)
                val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                try {
                    val row = IntArray(image.width)
                    for (y in 0 until image.height) {
                        ensureActive(); image.getPixels(row, 0, image.width, 0, y, image.width, 1)
                        for (x in row.indices) {
                            val p = row[x]
                            row[x] = if ((p ushr 16 and 255) == 255 || (p ushr 8 and 255) == 255 || (p and 255) == 255) 0xB0FF3030.toInt() else 0
                        }
                        mask.setPixels(row, 0, image.width, 0, y, image.width, 1)
                    }
                    mask
                } catch (failure: Throwable) { mask.recycle(); throw failure }
            } else null
            analysisError = false
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: OutOfMemoryError) { value = null; analysisError = true
        } catch (_: Exception) { value = null; analysisError = true }
    }
    val colors = ObservatoryTheme.colors
    val redNight = ObservatoryTheme.redNight
    val description = stringResource(R.string.image_desc)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::reset) { Text(stringResource(R.string.media_inspect_fit)) }
            TextButton(onClick = { nativeRequested = true; pendingOneToOne = true; nativeAttempt++ }, enabled = !loading) { Text("1:1") }
            if (before != null) FilterChip(selected = compare, onClick = { compare = !compare }, label = { Text(stringResource(R.string.media_inspect_compare)) })
            FilterChip(selected = histogramVisible, onClick = { histogramVisible = !histogramVisible }, label = { Text(stringResource(R.string.media_inspect_histogram)) })
            FilterChip(selected = clippingVisible, onClick = { clippingVisible = !clippingVisible }, label = { Text(stringResource(R.string.media_inspect_clipping)) })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (nativeError) Text(stringResource(R.string.media_inspect_native_failed), color = colors.onImage, style = MaterialTheme.typography.bodySmall)
        if (analysisError || histogramError) Text(stringResource(R.string.media_edit_memory), color = colors.onImage, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }
                .pointerInput(fit, originalWidth, originalHeight) {
                    detectTransformGestures { centroid, pan, scaleChange, _ ->
                        val old = zoom
                        zoom = (zoom * scaleChange).coerceIn(.5f, maxOf(12f, 4f / fit))
                        val ratio = zoom / old
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val shifted = centroid - center
                        panX = ((panX * size.width - shifted.x) * ratio + shifted.x + pan.x) / size.width
                        panY = ((panY * size.height - shifted.y) * ratio + shifted.y + pan.y) / size.height
                        val maxX = (originalWidth * fit * zoom / size.width - 1f).coerceAtLeast(0f) / 2f
                        val maxY = (originalHeight * fit * zoom / size.height - 1f).coerceAtLeast(0f) / 2f
                        panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
                        if (zoom > 1.25f) nativeRequested = true
                    }
                }.pointerInput(Unit) { detectTapGestures(onDoubleTap = {
                    if (zoom != 1f) reset() else { nativeRequested = true; pendingOneToOne = true }
                }) }) {
                val width = (originalWidth * fit * zoom).roundToInt().coerceAtLeast(1)
                val height = (originalHeight * fit * zoom).roundToInt().coerceAtLeast(1)
                val offset = IntOffset(((size.width - width) / 2f + panX * size.width).roundToInt(),
                    ((size.height - height) / 2f + panY * size.height).roundToInt())
                fun draw(bitmap: Bitmap) = drawImage(bitmap.asImageBitmap(), dstOffset = offset, dstSize = IntSize(width, height))
                fun drawRegion(value: Pair<MediaImageRegion, Bitmap>?) {
                    value ?: return
                    val (part, pixels) = value
                    val scale = fit * zoom
                    drawImage(pixels.asImageBitmap(), dstOffset = IntOffset(
                        (offset.x + part.left * scale).roundToInt(), (offset.y + part.top * scale).roundToInt()),
                        dstSize = IntSize((part.width * scale).roundToInt().coerceAtLeast(1), (part.height * scale).roundToInt().coerceAtLeast(1)))
                }
                draw(image); drawRegion(native)
                if (compare && before != null) clipRect(right = size.width * divider) { draw(before); drawRegion(nativeBefore) }
                clipping?.let { overlay -> clipRect(left = if (compare) size.width * divider else 0f) { draw(overlay) } }
                if (compare && before != null) drawLine(colors.onImage, Offset(size.width * divider, 0f), Offset(size.width * divider, size.height), 2.dp.toPx())
            }
            Text(if (native?.first == region && region != null && (!compare || nativeBefore?.first == region)) "${(fit * zoom * 100).roundToInt()}%" else stringResource(R.string.media_inspect_preview),
                Modifier.align(Alignment.BottomEnd).padding(8.dp), color = colors.onImage, style = MaterialTheme.typography.labelSmall)
        }
        if (compare && before != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.media_inspect_before), color = colors.onImage, style = MaterialTheme.typography.labelSmall)
                Slider(divider, { divider = it }, Modifier.weight(1f), valueRange = .05f.. .95f)
                Text(stringResource(R.string.media_inspect_after), color = colors.onImage, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (histogramVisible || clippingVisible) stats?.let { data ->
            Text(stringResource(R.string.media_inspect_display_samples, if (data.samples == 0) 0f else data.clipped * 100f / data.samples),
                color = colors.onImage, style = MaterialTheme.typography.labelSmall)
            if (histogramVisible) Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val maximum = data.bins.maxOf { channel -> channel.maxOrNull() ?: 0 }.coerceAtLeast(1)
                val channelColors = if (redNight) listOf(colors.onImage, colors.onImage.copy(alpha = .6f), colors.onImage.copy(alpha = .35f)) else listOf(Color.Red, Color.Green, Color.Cyan)
                data.bins.forEachIndexed { channel, bins ->
                    val path = Path()
                    bins.forEachIndexed { x, count ->
                        val px = x * size.width / 255f
                        val py = size.height * (1f - (ln(count + 1.0) / ln(maximum + 1.0)).toFloat())
                        if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, channelColors[channel], style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
