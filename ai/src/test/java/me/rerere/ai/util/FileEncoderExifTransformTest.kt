package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileEncoderExifTransformTest {

    @Test
    fun `all supported exif orientations should map to expected transform`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_NORMAL))
        assertEquals(ExifTransformType.FLIP_HORIZONTAL, mapExifOrientationToTransform(ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(ExifTransformType.ROTATE_180, mapExifOrientationToTransform(ORIENTATION_ROTATE_180))
        assertEquals(ExifTransformType.FLIP_VERTICAL, mapExifOrientationToTransform(ORIENTATION_FLIP_VERTICAL))
        assertEquals(ExifTransformType.TRANSPOSE, mapExifOrientationToTransform(ORIENTATION_TRANSPOSE))
        assertEquals(ExifTransformType.ROTATE_90, mapExifOrientationToTransform(ORIENTATION_ROTATE_90))
        assertEquals(ExifTransformType.TRANSVERSE, mapExifOrientationToTransform(ORIENTATION_TRANSVERSE))
        assertEquals(ExifTransformType.ROTATE_270, mapExifOrientationToTransform(ORIENTATION_ROTATE_270))
    }

    @Test
    fun `undefined or unknown orientation should map to none`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_UNDEFINED))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(999))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(-1))
    }

    @Test
    fun `decode sampling keeps enough resolution for final resizing within the pixel budget`() {
        for ((width, height, expected) in listOf(
            Triple(1272, 2800, 1),
            Triple(5000, 5000, 2),
            Triple(1200, 20_000, 4),
        )) {
            assertEquals(expected, calculateImageInSampleSize(width, height, 2560, 16_000_000L))
        }
    }

    @Test
    fun `target dimensions preserve aspect ratio without upscaling or collapsing thin images`() {
        for ((source, target) in listOf(
            (4000 to 3000) to (2560 to 1920),
            (1272 to 2800) to (1162 to 2560),
            (2 to 10_002) to (1 to 2560),
            (2561 to 1) to (2560 to 1),
            (40 to 20) to (40 to 20),
        )) assertEquals(target, imageTargetSize(source.first, source.second))
    }

    @Test
    fun `shared signature detection retains HEIC and AVIF support for local file tools`() {
        for ((brand, mime) in listOf("heic" to "image/heic", "avif" to "image/avif")) {
            val header = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray(Charsets.US_ASCII) + ByteArray(12)
            assertEquals(mime, detectImageMimeTypeBySignature(header))
        }
    }

    companion object {
        private const val ORIENTATION_UNDEFINED = 0
        private const val ORIENTATION_NORMAL = 1
        private const val ORIENTATION_FLIP_HORIZONTAL = 2
        private const val ORIENTATION_ROTATE_180 = 3
        private const val ORIENTATION_FLIP_VERTICAL = 4
        private const val ORIENTATION_TRANSPOSE = 5
        private const val ORIENTATION_ROTATE_90 = 6
        private const val ORIENTATION_TRANSVERSE = 7
        private const val ORIENTATION_ROTATE_270 = 8
    }
}
