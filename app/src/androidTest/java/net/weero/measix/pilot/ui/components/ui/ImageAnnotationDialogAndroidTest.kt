package net.weero.measix.pilot.ui.components.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationRaster
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationStroke

@RunWith(AndroidJUnit4::class)
class ImageAnnotationDialogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun undoRedoRestoresTheSameStrokeAndSaveFailureAllowsRetry() {
        val raster = raster()
        var attempts = 0
        var accepted = emptyList<ImageAnnotationStroke>()
        compose.setContent {
            MaterialTheme {
                ImageAnnotationDialog(raster, {}, onSave = {
                    attempts++
                    if (attempts == 1) throw java.io.IOException("annotation save fixture")
                    accepted = it
                })
            }
        }
        awaitReady()
        compose.onNodeWithTag("image-annotation-canvas").performTouchInput {
            swipe(center - Offset(15f, 0f), center + Offset(15f, 0f))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_undo)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_redo)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_done)).performClick()
        compose.waitUntil { attempts == 1 }
        awaitReady()
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_done)).performClick()
        compose.waitUntil { attempts == 2 }
        compose.runOnIdle {
            assertEquals(1, accepted.size)
            assertTrue(accepted.single().points.all { it.x in 0f..1f && it.y in 0f..1f })
        }
    }

    @Test
    fun cancelDuringSaveCancelsTheAwaitingOperation() {
        var show by mutableStateOf(true)
        var saving = false
        var cancelled = false
        val neverCompletes = CompletableDeferred<Unit>()
        val raster = raster()
        compose.setContent {
            MaterialTheme {
                if (show) ImageAnnotationDialog(raster, { show = false }, onSave = {
                    saving = true
                    try { neverCompletes.await() } finally { cancelled = true }
                })
            }
        }
        awaitReady()
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_done)).performClick()
        compose.waitUntil { saving }
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        compose.waitUntil { cancelled }
        compose.runOnIdle { assertTrue(!show) }
    }

    @Test
    fun pinchAndCancelledGestureDoNotCommitPartialStrokes() {
        val raster = raster()
        var saved: List<ImageAnnotationStroke>? = null
        compose.setContent { MaterialTheme { ImageAnnotationDialog(raster, {}, onSave = { saved = it }) } }
        awaitReady()
        compose.onNodeWithTag("image-annotation-canvas").performTouchInput {
            down(0, center - Offset(10f, 0f))
            moveTo(0, center)
            cancel()
        }
        compose.onNodeWithTag("image-annotation-canvas").performTouchInput {
            down(0, center - Offset(10f, 0f))
            down(1, center + Offset(10f, 0f))
            moveTo(0, center - Offset(20f, 0f))
            moveTo(1, center + Offset(20f, 0f))
            up(0)
            up(1)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.image_annotation_done)).performClick()
        compose.waitUntil { saved != null }
        compose.runOnIdle { assertTrue(saved!!.isEmpty()) }
    }

    private fun awaitReady() {
        val label = compose.activity.getString(R.string.image_annotation_done)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun raster(): ImageAnnotationRaster {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            ImageAnnotationRaster(bytes, 100, 100)
        } finally { bitmap.recycle() }
    }
}
