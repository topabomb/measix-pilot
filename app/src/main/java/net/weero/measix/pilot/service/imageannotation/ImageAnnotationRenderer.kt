package net.weero.measix.pilot.service.imageannotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.util.withPreparedStaticImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Shared by the preview's native Canvas and the final raster, both in prepared-image pixel space. */
internal fun drawImageAnnotations(
    canvas: Canvas,
    imageWidth: Int,
    imageHeight: Int,
    strokes: List<ImageAnnotationStroke>,
    checkActive: () -> Unit = {},
) {
    require(imageWidth > 0 && imageHeight > 0)
    val checkpoint = canvas.save()
    try {
        canvas.clipRect(0, 0, imageWidth, imageHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            checkActive()
            paint.color = stroke.colorArgb
            paint.strokeWidth = stroke.widthFraction * min(imageWidth, imageHeight)
            val first = stroke.points.first()
            val last = stroke.points.last()
            val x1 = first.x * imageWidth
            val y1 = first.y * imageHeight
            val x2 = last.x * imageWidth
            val y2 = last.y * imageHeight
            when (stroke.shape) {
                ImageAnnotationShape.FREEHAND -> {
                    if (stroke.points.size == 1) canvas.drawPoint(x1, y1, paint)
                    else {
                        val path = Path().apply {
                            moveTo(x1, y1)
                            stroke.points.forEachIndexed { index, point ->
                                if (index % 128 == 0) checkActive()
                                if (index > 0) lineTo(point.x * imageWidth, point.y * imageHeight)
                            }
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                ImageAnnotationShape.ELLIPSE, ImageAnnotationShape.RECTANGLE -> {
                    if (x1 == x2 || y1 == y2) canvas.drawLine(x1, y1, x2, y2, paint)
                    else if (stroke.shape == ImageAnnotationShape.ELLIPSE) {
                        canvas.drawOval(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
                    } else canvas.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
                }
                ImageAnnotationShape.ARROW -> {
                    canvas.drawLine(x1, y1, x2, y2, paint)
                    val distance = hypot(x2 - x1, y2 - y1)
                    if (distance > 0f) {
                        val length = min(distance * 0.45f, paint.strokeWidth * 4f)
                        val unitX = (x2 - x1) / distance
                        val unitY = (y2 - y1) / distance
                        val baseX = x2 - unitX * length
                        val baseY = y2 - unitY * length
                        canvas.drawLine(x2, y2, baseX - unitY * length * 0.5f, baseY + unitX * length * 0.5f, paint)
                        canvas.drawLine(x2, y2, baseX + unitY * length * 0.5f, baseY - unitX * length * 0.5f, paint)
                    }
                }
            }
        }
        checkActive()
    } finally {
        canvas.restoreToCount(checkpoint)
    }
}

internal data class ImageAnnotationRaster(val png: ByteArray, val width: Int, val height: Int)

internal suspend fun prepareImageAnnotationPreview(sourceBytes: ByteArray): ImageAnnotationRaster =
    renderImageAnnotation(sourceBytes, emptyList())

/** No lossy intermediate and no retained borrowed bitmap; request encoding remains FileEncoder's job. */
internal suspend fun renderImageAnnotationPng(sourceBytes: ByteArray, strokes: List<ImageAnnotationStroke>): ByteArray =
    renderImageAnnotation(sourceBytes, strokes).png

private suspend fun renderImageAnnotation(sourceBytes: ByteArray, strokes: List<ImageAnnotationStroke>): ImageAnnotationRaster {
    val context = currentCoroutineContext()
    val snapshot = strokes.toList()
    return withPreparedStaticImage(sourceBytes) { source ->
        context.ensureActive()
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawBitmap(source, 0f, 0f, null)
            drawImageAnnotations(canvas, source.width, source.height, snapshot, context::ensureActive)
            ByteArrayOutputStream().use { bytes ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "image_annotation_encode_failed" }
                context.ensureActive()
                ImageAnnotationRaster(bytes.toByteArray(), source.width, source.height)
            }
        } finally {
            output.recycle()
        }
    }
}
