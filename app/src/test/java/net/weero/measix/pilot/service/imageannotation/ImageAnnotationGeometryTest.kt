package net.weero.measix.pilot.service.imageannotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageAnnotationGeometryTest {
    @Test
    fun letterboxingAndZoomMapBackToTheSameImageCoordinates() {
        val viewport = ImageAnnotationViewport(400, 200, 300f, 300f)
        assertEquals(0.75f, viewport.scale, 0f)
        assertEquals(75f, viewport.top, 0f)
        assertNull(viewport.toImage(150f, 40f))
        val imagePoint = ImageAnnotationPoint(0.3f, 0.7f)
        val screenPoint = viewport.toViewport(imagePoint)
        val changed = viewport.transformed(2.5f, screenPoint.x, screenPoint.y, 12f, -7f)
        val transformedPoint = changed.toViewport(imagePoint)
        assertEquals(screenPoint.x + 12f, transformedPoint.x, 0.0001f)
        assertEquals(screenPoint.y - 7f, transformedPoint.y, 0.0001f)
        val restored = changed.toImage(transformedPoint.x, transformedPoint.y)!!
        assertEquals(imagePoint.x, restored.x, 0.0001f)
        assertEquals(imagePoint.y, restored.y, 0.0001f)
    }

    @Test
    fun clippedDragSamplesStayInsideImageAndInvalidCoordinatesCannotBecomeStrokes() {
        val viewport = ImageAnnotationViewport(200, 400, 300f, 300f)
        assertNull(viewport.toImage(10f, 20f))
        assertEquals(ImageAnnotationPoint(0f, 0f), viewport.toImage(-10f, -20f, clamp = true))
        assertEquals(ImageAnnotationPoint(1f, 1f), viewport.toImage(400f, 500f, clamp = true))
        assertNull(viewport.toImage(Float.NaN, 20f, clamp = true))
        assertThrows(IllegalArgumentException::class.java) { ImageAnnotationPoint(Float.NaN, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { ImageAnnotationPoint(-0.1f, 0.5f) }
    }

    @Test
    fun zoomLimitUsesTheActualClampedRatioForThePinchAnchor() {
        val viewport = ImageAnnotationViewport(200, 100, 300f, 200f, zoom = 7f, panX = 13f, panY = -8f)
        val point = ImageAnnotationPoint(0.4f, 0.6f)
        val anchor = viewport.toViewport(point)
        val enlarged = viewport.transformed(10f, anchor.x, anchor.y, 0f, 0f)
        assertEquals(8f, enlarged.zoom, 0f)
        assertEquals(anchor.x, enlarged.toViewport(point).x, 0.0001f)
        assertEquals(anchor.y, enlarged.toViewport(point).y, 0.0001f)
        val reduced = enlarged.transformed(0.01f, anchor.x, anchor.y, 0f, 0f)
        assertEquals(1f, reduced.zoom, 0f)
        assertEquals(anchor.x, reduced.toViewport(point).x, 0.0001f)
        assertEquals(anchor.y, reduced.toViewport(point).y, 0.0001f)
    }

    @Test
    fun completedStrokeDoesNotChangeWhenTheActiveGestureAddsMorePoints() {
        val points = mutableListOf(ImageAnnotationPoint(0.2f, 0.3f))
        val stroke = ImageAnnotationStroke(ImageAnnotationShape.FREEHAND, points, 0xffff0000.toInt(), 0.01f)
        points += ImageAnnotationPoint(0.8f, 0.7f)
        assertEquals(1, stroke.points.size)
        assertThrows(IllegalArgumentException::class.java) {
            ImageAnnotationStroke(ImageAnnotationShape.FREEHAND, emptyList(), 0, 0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageAnnotationStroke(ImageAnnotationShape.FREEHAND, points, 0, Float.NaN)
        }
    }
}
