package net.weero.measix.pilot.data.ai.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ImageEncodingMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.files.LocalArtifactRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRequestImageEncoderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun contentAndSessionIdentifyStaticCacheAndPreserveOriginalHasItsOwnPath() = runBlocking {
        val file = temporary.newFile("input.png").apply { writeBytes(pngHeader()) }
        val calls = AtomicInteger()
        var accessAllowed = true
        val encoder = ModelRequestImageEncoder(
            requireAccess = { check(accessAllowed) { "access_revoked" } },
            staticEncode = { byteArrayOf(calls.incrementAndGet().toByte()) },
        )
        val image = UIMessagePart.Image(file.toURI().toString())
        val original = image.copy(metadata = ImageEncodingMetadata(preserveOriginal = true).toMetadata())
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("test-deployment"), "user")
        val first = RealmAccess.Enterprise(scope, "first")
        val second = RealmAccess.Enterprise(scope, "second")
        lease(file, image.url).use { reads ->
            encoder.open(first, reads).use { request ->
                assertEquals("data:image/webp;base64,AQ==", request.encode(image, true).base64)
                val uncompressed = request.encode(original, false)
                assertEquals("image/png", uncompressed.mimeType)
                assertEquals(java.util.Base64.getEncoder().encodeToString(file.readBytes()), uncompressed.base64)
            }
            encoder.open(first, reads).use { request ->
                assertEquals("AQ==", request.encode(image, false).base64)
            }
            encoder.open(second, reads).use { request ->
                assertEquals("Ag==", request.encode(image, false).base64)
            }
        }

        // A different published payload at the same path and length is distinct after a new read lease.
        val stamp = file.lastModified()
        file.writeBytes(pngHeader(1))
        check(file.setLastModified(stamp))
        lease(file, image.url).use { reads ->
            val request = encoder.open(first, reads)
            assertEquals("Aw==", request.encode(image, false).base64)
            accessAllowed = false
            assertThrows(IllegalStateException::class.java) { runBlocking { request.encode(image, false) } }
            accessAllowed = true
            request.close()
            assertThrows(IllegalStateException::class.java) { runBlocking { request.encode(image, false) } }
        }
        lease(null, image.url).use { reads ->
            encoder.open(first, reads).use { request ->
                assertThrows(IllegalStateException::class.java) { runBlocking { request.encode(image, false) } }
            }
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun invalidationDiscardsLateEncodingAndCancellationDoesNotPublish() = runBlocking {
        val file = temporary.newFile("cancel.png").apply { writeBytes(pngHeader()) }
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val encoder = ModelRequestImageEncoder(staticEncode = {
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                resume.await()
            }
            byteArrayOf(1)
        })
        val image = UIMessagePart.Image(file.toURI().toString())
        lease(file, image.url).use { reads ->
            encoder.open(RealmAccess.Personal, reads).use { request ->
                val first = async { request.encode(image, false) }
                started.await()
                val queued = async { request.encode(image, false) }
                queued.cancelAndJoin()
                encoder.clear()
                resume.complete(Unit)
                assertEquals("AQ==", first.await().base64)
                assertEquals("AQ==", request.encode(image, false).base64)
                assertEquals("AQ==", request.encode(image, false).base64)
            }
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun overBudgetImageDoesNotEvictEntryAndAdmittedImageEvictsLeastRecent() = runBlocking {
        val calls = mutableListOf<String>()
        val encoder = ModelRequestImageEncoder(
            capacityBytes = 2,
            staticEncode = { file ->
                calls += file.name
                ByteArray(if (file.name == "oversize") 3 else 2)
            },
        )
        val first = temporary.newFile("first").apply { writeBytes(pngHeader(1)) }
        val second = temporary.newFile("second").apply { writeBytes(pngHeader(2)) }
        val oversize = temporary.newFile("oversize").apply { writeBytes(pngHeader(3)) }
        suspend fun encode(file: File) {
            val image = UIMessagePart.Image(file.toURI().toString())
            lease(file, image.url).use { reads ->
                encoder.open(RealmAccess.Personal, reads).use { request ->
                    request.encode(image, false)
                }
            }
        }
        for (file in listOf(first, oversize, first, oversize, second, second, first)) encode(file)
        assertEquals(listOf("first", "oversize", "oversize", "second", "first"), calls)
    }

    private fun lease(file: File?, url: String): ArtifactReadLease {
        val ref = LocalArtifactRef(relativePath = "upload/test.png", mimeType = "image/png")
        return ArtifactReadLease(
            files = file?.let { mapOf(ref.relativePath to (ref to it)) }.orEmpty(),
            relativePathForUri = { uri -> ref.relativePath.takeIf { uri == url } },
            retention = ArtifactRetentionLease {},
        )
    }

    private fun pngHeader(marker: Byte = 0): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker, 0, 0, 0)
}
