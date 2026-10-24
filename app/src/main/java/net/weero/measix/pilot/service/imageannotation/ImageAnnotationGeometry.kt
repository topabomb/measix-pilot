package net.weero.measix.pilot.service.imageannotation

import kotlin.math.min

/** Coordinates refer to the EXIF-corrected image, independent of viewport zoom and translation. */
internal data class ImageAnnotationPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
    }
}

internal enum class ImageAnnotationShape { FREEHAND, ELLIPSE, RECTANGLE, ARROW }

/** An immutable gesture snapshot. Width is a fraction of the prepared image's shorter edge. */
internal class ImageAnnotationStroke(
    val shape: ImageAnnotationShape,
    points: List<ImageAnnotationPoint>,
    val colorArgb: Int,
    val widthFraction: Float,
) {
    val points: List<ImageAnnotationPoint> = points.toList()

    init {
        require(points.isNotEmpty())
        require(widthFraction.isFinite() && widthFraction > 0f && widthFraction <= 1f)
    }
}

internal data class ImageAnnotationViewportPoint(val x: Float, val y: Float)

/** Fits the complete prepared image before applying a user zoom around the viewport's centre. */
internal data class ImageAnnotationViewport(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
        require(viewportWidth.isFinite() && viewportWidth > 0f)
        require(viewportHeight.isFinite() && viewportHeight > 0f)
        require(zoom.isFinite() && zoom >= 1f)
        require(panX.isFinite() && panY.isFinite())
    }

    val scale: Float get() = min(viewportWidth / imageWidth, viewportHeight / imageHeight) * zoom
    val left: Float get() = (viewportWidth - imageWidth * scale) / 2f + panX
    val top: Float get() = (viewportHeight - imageHeight * scale) / 2f + panY

    fun toViewport(point: ImageAnnotationPoint) = ImageAnnotationViewportPoint(
        left + point.x * imageWidth * scale,
        top + point.y * imageHeight * scale,
    )

    /** Start gestures only inside the image; clamp only subsequent drag samples at its edges. */
    fun toImage(x: Float, y: Float, clamp: Boolean = false): ImageAnnotationPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        val relativeX = (x - left) / (imageWidth * scale)
        val relativeY = (y - top) / (imageHeight * scale)
        if (!clamp && (relativeX !in 0f..1f || relativeY !in 0f..1f)) return null
        return ImageAnnotationPoint(relativeX.coerceIn(0f, 1f), relativeY.coerceIn(0f, 1f))
    }

    /** Keeps the touched image location under the pinch centroid, then applies gesture translation. */
    fun transformed(
        zoomFactor: Float,
        centroidX: Float,
        centroidY: Float,
        translationX: Float,
        translationY: Float,
        maximumZoom: Float = 8f,
    ): ImageAnnotationViewport {
        require(zoomFactor.isFinite() && zoomFactor > 0f)
        require(centroidX.isFinite() && centroidY.isFinite())
        require(translationX.isFinite() && translationY.isFinite())
        require(maximumZoom.isFinite() && maximumZoom >= 1f)
        val nextZoom = (zoom * zoomFactor).coerceIn(1f, maximumZoom)
        val ratio = nextZoom / zoom
        return copy(
            zoom = nextZoom,
            panX = (panX + viewportWidth / 2f - centroidX) * ratio + centroidX - viewportWidth / 2f + translationX,
            panY = (panY + viewportHeight / 2f - centroidY) * ratio + centroidY - viewportHeight / 2f + translationY,
        )
    }
}
