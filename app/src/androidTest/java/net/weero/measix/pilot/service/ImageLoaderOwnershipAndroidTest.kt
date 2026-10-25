package net.weero.measix.pilot.service

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageLoaderOwnershipAndroidTest {
    @Test fun previewPolicyAlsoAppliesToDefaultFullscreenImageRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = SingletonImageLoader.get(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.recycle() }
        var reads = 0
        val source = ImageSource("preview-${UUID.randomUUID()}", ImageOrigin.LOCAL,
            verifyAccess = {}, readPayload = { reads++; bytes }, memoryCacheEnabled = false)
        // Like the fullscreen painter, the caller does not set a request cache policy.
        val request = ImageRequest.Builder(context).data(source).size(8, 8).build()
        repeat(2) {
            val result = loader.execute(request)
            assertTrue(result is SuccessResult)
            assertNull((result as SuccessResult).memoryCacheKey)
        }
        assertEquals(2, reads)
    }

    @Test fun applicationLoaderAuthorizesBeforeCacheAndAfterReadWithoutAnActivity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = SingletonImageLoader.get(context)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        var allowed = true
        var reads = 0
        val source = ImageSource("singleton-${UUID.randomUUID()}", ImageOrigin.UPLOAD,
            verifyAccess = { check(allowed) { "original_selection_revoked" } },
            readPayload = { reads++; bytes },
        )
        val request = ImageRequest.Builder(context).data(source).size(1, 1).build()
        val success = loader.execute(request)
        assertTrue(success is SuccessResult)
        try {
            assertEquals(1, reads)
            allowed = false
            val rejected = loader.execute(request)
            assertTrue(rejected is ErrorResult)
            assertEquals("original_selection_revoked", (rejected as ErrorResult).throwable.message)
            assertEquals(1, reads)
            val revokedDuringRead = ImageSource("singleton-${UUID.randomUUID()}", ImageOrigin.UPLOAD,
                verifyAccess = { check(allowed) { "revoked_during_read" } },
                readPayload = { allowed = false; bytes },
            )
            allowed = true
            val delivery = loader.execute(ImageRequest.Builder(context).data(revokedDuringRead).size(1, 1).build())
            assertTrue(delivery is ErrorResult)
            assertEquals("revoked_during_read", (delivery as ErrorResult).throwable.message)
        } finally {
            (success as? SuccessResult)?.memoryCacheKey?.let { loader.memoryCache?.remove(it) }
        }
    }
}
