package net.weero.measix.pilot.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ImageEncodingMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.encodeImageBytes
import me.rerere.ai.util.encodeNativeImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImageEncodingTest {
    @Suppress("DEPRECATION")
    @Test
    fun `local snapshots share bounded WebP encoding while external bytes remain unchanged`() = runBlocking {
        val root = Files.createTempDirectory("model-image-").toFile()
        try {
            for ((format, width, height) in listOf(
                Triple(Bitmap.CompressFormat.PNG, 4000, 3000),
                Triple(Bitmap.CompressFormat.JPEG, 40, 20),
                Triple(Bitmap.CompressFormat.WEBP, 40, 20),
            )) {
                val source = bitmapBytes(format, width, height, Color.RED)
                val file = root.resolve("source").apply { writeBytes(source) }
                val image = UIMessagePart.Image("file://${file.toURI().rawPath}")
                val encoded = encodeImageBytes(source)
                assertEquals(encoded, image.encodeNativeImage())
                assertEquals("image/webp", encoded.mimeType)
                val result = decode(encoded.base64)
                try {
                    assertEquals(if (width > 2560) 2560 else width, result.width)
                    assertEquals(if (height > 2560) 1920 else height, result.height)
                } finally { result.recycle() }
                val external = image.copy(metadata = ImageEncodingMetadata(preserveOriginal = true).toMetadata())
                val restored = Json.decodeFromString(
                    UIMessagePart.Image.serializer(), Json.encodeToString(UIMessagePart.Image.serializer(), external),
                )
                assertArrayEquals(source, payload(restored.encodeNativeImage().base64))
                assertArrayEquals(source, file.readBytes())
            }
        } finally { check(root.deleteRecursively()) }
    }

    @Test
    fun `WebP encoding preserves alpha without enlarging a small image`() = runBlocking {
        val source = bitmapBytes(Bitmap.CompressFormat.PNG, 40, 20, Color.argb(64, 255, 0, 0))
        val result = decode(encodeImageBytes(source).base64)
        try {
            assertEquals(40, result.width)
            assertEquals(20, result.height)
            assertEquals(64, Color.alpha(result.getPixel(20, 10)))
        } finally { result.recycle() }
    }

    private fun bitmapBytes(format: Bitmap.CompressFormat, width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use {
                check(bitmap.compress(format, 95, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun payload(url: String): ByteArray = java.util.Base64.getDecoder().decode(url.substringAfter("base64,"))

    private fun decode(url: String): Bitmap = payload(url).let {
        requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size))
    }
}
