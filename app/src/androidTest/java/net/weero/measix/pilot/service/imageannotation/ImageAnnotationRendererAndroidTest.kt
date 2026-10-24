package net.weero.measix.pilot.service.imageannotation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageAnnotationRendererAndroidTest {
    @Test
    fun emptyPreviewPreservesDimensionsAndAlphaWithoutChangingSource() = runBlocking {
        val source = sourcePng()
        val original = source.copyOf()
        val preview = prepareImageAnnotationPreview(source)
        assertEquals(100, preview.width)
        assertEquals(80, preview.height)
        withBitmap(preview.png) { bitmap ->
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(128, Color.alpha(bitmap.getPixel(5, 5)))
            assertEquals(Color.BLUE, bitmap.getPixel(95, 75))
        }
        assertArrayEquals(original, source)
    }

    @Test
    fun allShapesShareTheSamePreparedPixelCoordinatesAndPreserveUntouchedPixels() = runBlocking {
        val source = sourcePng()
        for (shape in ImageAnnotationShape.entries) {
            val stroke = ImageAnnotationStroke(shape,
                listOf(ImageAnnotationPoint(0.25f, 0.25f), ImageAnnotationPoint(0.75f, 0.75f)),
                Color.RED, 0.05f)
            withBitmap(renderImageAnnotationPng(source, listOf(stroke))) { bitmap ->
                val point = when (shape) {
                    ImageAnnotationShape.FREEHAND, ImageAnnotationShape.ARROW -> 50 to 40
                    ImageAnnotationShape.RECTANGLE, ImageAnnotationShape.ELLIPSE -> 50 to 20
                }
                assertTrue("$shape must render at its image-space location", Color.red(bitmap.getPixel(point.first, point.second)) > 240)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals(128, Color.alpha(bitmap.getPixel(5, 5)))
                assertEquals(Color.BLUE, bitmap.getPixel(95, 75))
            }
        }
    }

    @Test
    fun reversedShapeCornersAndSinglePointGesturesRemainRenderable() = runBlocking {
        val source = sourcePng()
        for (shape in listOf(ImageAnnotationShape.ELLIPSE, ImageAnnotationShape.RECTANGLE)) {
            val stroke = ImageAnnotationStroke(shape,
                listOf(ImageAnnotationPoint(0.75f, 0.75f), ImageAnnotationPoint(0.25f, 0.25f)),
                Color.RED, 0.05f)
            withBitmap(renderImageAnnotationPng(source, listOf(stroke))) { bitmap ->
                assertTrue(Color.red(bitmap.getPixel(50, 20)) > 240)
            }
        }
        val dot = ImageAnnotationStroke(ImageAnnotationShape.FREEHAND, listOf(ImageAnnotationPoint(0.5f, 0.5f)), Color.RED, 0.1f)
        withBitmap(renderImageAnnotationPng(source, listOf(dot))) { bitmap ->
            assertEquals(Color.RED, bitmap.getPixel(50, 40))
        }
    }

    @Test
    fun cancellationRestoresThePreviewCanvasAndGifDoesNotEnterStaticAnnotation() {
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val before = canvas.saveCount
            val failure = CancellationException("annotation closed")
            val observed = assertThrows(CancellationException::class.java) {
                drawImageAnnotations(canvas, 100, 80, emptyList()) { throw failure }
            }
            assertEquals(failure, observed)
            assertEquals(before, canvas.saveCount)
        } finally { bitmap.recycle() }
        val gif = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { prepareImageAnnotationPreview(gif) }
        }
        assertEquals("animated_image_annotation_not_supported", failure.message)
    }

    private fun sourcePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixel(5, 5, Color.argb(128, 10, 20, 30))
            bitmap.setPixel(95, 75, Color.BLUE)
            ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun withBitmap(png: ByteArray, operation: (Bitmap) -> Unit) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try { operation(bitmap) } finally { bitmap.recycle() }
    }
}
