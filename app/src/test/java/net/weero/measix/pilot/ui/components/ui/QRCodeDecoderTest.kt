package net.weero.measix.pilot.ui.components.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QRCodeDecoderTest {
    private val content = "measix://fixture/中文-QR"

    private fun frame(width: Int, height: Int, rowStride: Int, pixelStride: Int, rotate: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 240, 240,
            mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        // Real camera buffers need not include the padding after the final row.
        val bytes = ByteArray((height - 1) * rowStride + (width - 1) * pixelStride + 1) { 0xff.toByte() }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            val black = if (rotate) matrix[y, matrix.width - x - 1] else matrix[x, y]
            if (black) bytes[(top + y) * rowStride + (left + x) * pixelStride] = 0
        }
        return bytes
    }

    @Test fun paddedInterleavedAndRotatedFramesRetainDecodedText() {
        val decoder = QRCodeDecoder()
        for (pixelStride in listOf(1, 2)) for (padding in listOf(0, 63)) for (rotate in listOf(false, true)) {
            val rowStride = 400 * pixelStride + padding
            assertEquals(content, decoder.decode(frame(400, 300, rowStride, pixelStride, rotate),
                rowStride, 400, 300, pixelStride))
        }
    }

    @Test fun blankFrameDoesNotPreventNextSuccessfulFrame() {
        val decoder = QRCodeDecoder()
        assertNull(decoder.decode(ByteArray(400 * 300) { 0x7f }, 400, 400, 300))
        assertEquals(content, decoder.decode(frame(400, 300, 400, 1), 400, 400, 300))
    }

    @Test fun truncatedAndOverflowingPlanesFailBeforeAllocatingOrIndexing() {
        val decoder = QRCodeDecoder()
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(2), 400, 400, 300) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(2), Int.MAX_VALUE, Int.MAX_VALUE, 2, 2) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(ByteArray(2), 1, 0, 1) }
    }
}
