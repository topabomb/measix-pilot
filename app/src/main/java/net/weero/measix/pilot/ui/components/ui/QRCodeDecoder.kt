package net.weero.measix.pilot.ui.components.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** Camera planes may omit padding after the last row and may interleave luminance samples. */
internal class QRCodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true))
    }

    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int, pixelStride: Int = 1): String? {
        require(width > 0 && height > 0 && pixelStride > 0 && rowStride > 0) { "qr_invalid_plane_dimensions" }
        val rowBytes = (width.toLong() - 1) * pixelStride + 1
        val requiredBytes = (height.toLong() - 1) * rowStride + rowBytes
        require(rowBytes <= rowStride && requiredBytes <= luminance.size && width.toLong() * height <= Int.MAX_VALUE) {
            "qr_invalid_plane_buffer"
        }
        val packed = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            packed[y * width + x] = luminance[y * rowStride + x * pixelStride]
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(
                PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false),
            ))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
