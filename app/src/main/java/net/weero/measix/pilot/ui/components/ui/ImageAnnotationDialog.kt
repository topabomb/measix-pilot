package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationPoint
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationRaster
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationShape
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationStroke
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationViewport
import net.weero.measix.pilot.service.imageannotation.drawImageAnnotations
import net.weero.measix.pilot.utils.userVisibleDiagnostic

/** Only transient drawing state lives here; the caller owns authorization, encoding and acceptance. */
@Composable
internal fun ImageAnnotationDialog(
    raster: ImageAnnotationRaster,
    onDismissRequest: () -> Unit,
    onSave: suspend (List<ImageAnnotationStroke>) -> Unit,
) = key(raster) {
    val scope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var ready by remember { mutableStateOf(false) }
    var imageAttempt by remember { mutableStateOf(0) }
    var strokes by remember { mutableStateOf(emptyList<ImageAnnotationStroke>()) }
    var redo by remember { mutableStateOf(emptyList<ImageAnnotationStroke>()) }
    var active by remember { mutableStateOf<ImageAnnotationStroke?>(null) }
    var shape by remember { mutableStateOf(ImageAnnotationShape.FREEHAND) }
    var moving by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(0xffff3030.toInt()) }
    var width by remember { mutableStateOf(0.008f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    fun viewport() = ImageAnnotationViewport(raster.width, raster.height,
        size.width.toFloat(), size.height.toFloat(), zoom, panX, panY)
    fun dismiss() { saveJob?.cancel(); onDismissRequest() }
    DisposableEffect(Unit) { onDispose { saveJob?.cancel() } }

    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = ::dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(enabled = !saving && ready && active == null, onClick = {
                        saving = true
                        failure = null
                        val snapshot = strokes.toList()
                        saveJob = scope.launch {
                            try { onSave(snapshot) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                android.util.Log.w("ImageAnnotation", "Unable to save annotation", error)
                                failure = error
                            } finally { saving = false }
                        }
                    }) { Text(stringResource(if (saving) R.string.image_viewer_saving else R.string.image_annotation_done)) }
                }
                Text(stringResource(R.string.image_annotation_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.image_annotation_gesture_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImageAnnotationShape.entries.forEach { tool ->
                        FilterChip(selected = !moving && shape == tool, enabled = !saving, onClick = { shape = tool; moving = false },
                            label = { Text(stringResource(when (tool) {
                                ImageAnnotationShape.FREEHAND -> R.string.image_annotation_line
                                ImageAnnotationShape.ELLIPSE -> R.string.image_annotation_ellipse
                                ImageAnnotationShape.RECTANGLE -> R.string.image_annotation_rectangle
                                ImageAnnotationShape.ARROW -> R.string.image_annotation_arrow
                            })) })
                    }
                    FilterChip(selected = moving, enabled = !saving, onClick = { moving = !moving },
                        label = { Text(stringResource(R.string.image_annotation_move)) })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !saving && strokes.isNotEmpty(), onClick = {
                        redo = redo + strokes.last(); strokes = strokes.dropLast(1)
                    }) { Text(stringResource(R.string.image_annotation_undo)) }
                    TextButton(enabled = !saving && redo.isNotEmpty(), onClick = {
                        strokes = strokes + redo.last(); redo = redo.dropLast(1)
                    }) { Text(stringResource(R.string.image_annotation_redo)) }
                    TextButton(enabled = !saving, onClick = { styleOpen = !styleOpen }) { Text(stringResource(R.string.image_annotation_style)) }
                    TextButton(enabled = !saving, onClick = { zoom = 1f; panX = 0f; panY = 0f }) { Text(stringResource(R.string.image_annotation_fit)) }
                }
                if (styleOpen) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0xffff3030.toInt() to R.string.image_annotation_red, 0xffffd600.toInt() to R.string.image_annotation_yellow,
                            0xff00a86b.toInt() to R.string.image_annotation_green, 0xff007aff.toInt() to R.string.image_annotation_blue,
                            0xff000000.toInt() to R.string.image_annotation_black, 0xffffffff.toInt() to R.string.image_annotation_white).forEach { (value, label) ->
                            FilterChip(selected = color == value, enabled = !saving, onClick = { color = value },
                                label = { Text(stringResource(label)) })
                        }
                    }
                    Text(stringResource(R.string.image_annotation_width), style = MaterialTheme.typography.labelMedium)
                    Slider(value = width, onValueChange = { width = it }, valueRange = 0.002f..0.025f, enabled = !saving)
                }
                failure?.let { DiagnosticDisclosure(it.userVisibleDiagnostic()) }
                if (!ready && failure != null) {
                    TextButton(onClick = { failure = null; imageAttempt++ }) { Text(stringResource(R.string.application_recovery_retry)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it }) {
                    key(imageAttempt) {
                    AsyncImage(model = raster.png, contentDescription = null, contentScale = ContentScale.Fit,
                        onSuccess = { ready = true }, onError = {
                            ready = false
                            android.util.Log.w("ImageAnnotation", "Unable to display prepared image", it.result.throwable)
                            failure = it.result.throwable
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY
                        })
                    }
                    Canvas(Modifier.fillMaxSize().testTag("image-annotation-canvas")
                        .pointerInput(raster, size, shape, moving, color, width, saving, ready) {
                            if (saving || !ready || size.width <= 0 || size.height <= 0) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                var transformed = moving
                                val points = mutableListOf<ImageAnnotationPoint>()
                                if (!moving) viewport().toImage(down.position.x, down.position.y)?.let(points::add)
                                down.consume()
                                fun updateActive() {
                                    active = points.takeIf { it.isNotEmpty() }?.let { ImageAnnotationStroke(shape, it, color, width) }
                                }
                                updateActive()
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.any { it.isConsumed }) { active = null; break }
                                        val count = event.changes.count { it.pressed }
                                        if (count > 1 || transformed) {
                                            transformed = true
                                            active = null
                                            val centroid = event.calculateCentroid(useCurrent = false)
                                            val pan = event.calculatePan()
                                            val factor = event.calculateZoom()
                                            if (centroid.x.isFinite() && centroid.y.isFinite() && factor.isFinite() && factor > 0f) {
                                                val next = viewport().transformed(factor, centroid.x, centroid.y, pan.x, pan.y)
                                                zoom = next.zoom; panX = next.panX; panY = next.panY
                                            }
                                        } else if (points.isNotEmpty()) {
                                            event.changes.firstOrNull { it.id == down.id }?.let { change ->
                                                viewport().toImage(change.position.x, change.position.y, clamp = true)?.let { point ->
                                                    if (shape == ImageAnnotationShape.FREEHAND) points.add(point)
                                                    else { if (points.size > 1) points.removeAt(1); points.add(point) }
                                                }
                                                updateActive()
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) {
                                            active?.let { strokes = strokes + it; redo = emptyList() }
                                            break
                                        }
                                    } while (true)
                                } finally { active = null }
                            }
                        }) {
                        if (size.width > 0 && size.height > 0) {
                            val view = viewport()
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                val checkpoint = native.save()
                                try {
                                    native.translate(view.left, view.top)
                                    native.scale(view.scale, view.scale)
                                    drawImageAnnotations(native, raster.width, raster.height, strokes + listOfNotNull(active))
                                } finally { native.restoreToCount(checkpoint) }
                            }
                        }
                    }
                }
            }
        }
    }
}
