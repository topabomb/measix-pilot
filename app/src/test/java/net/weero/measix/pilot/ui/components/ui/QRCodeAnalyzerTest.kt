package net.weero.measix.pilot.ui.components.ui

import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class QRCodeAnalyzerTest {
    @Test fun blankFrameClosesProxyAndDoesNotMutateSharedBufferPosition() {
        val buffer = ByteBuffer.wrap(ByteArray(102) { 0x7f }).apply { position(2) }
        val plane = mockk<ImageProxy.PlaneProxy>()
        every { plane.buffer } returns buffer
        every { plane.rowStride } returns 10
        every { plane.pixelStride } returns 1
        val image = mockk<ImageProxy>(relaxed = true)
        every { image.width } returns 10
        every { image.height } returns 10
        every { image.planes } returns arrayOf(plane)
        QRCodeAnalyzer(onDecoded = { error("blank image decoded") }, onFailure = { throw it }).analyze(image)
        assertEquals(2, buffer.position())
        verify(exactly = 1) { image.close() }
    }

    @Test fun planeReadAndFailureCallbackErrorsStillCloseProxy() {
        val image = mockk<ImageProxy>(relaxed = true)
        val original = IllegalStateException("fixture plane failure")
        val callback = IllegalArgumentException("fixture callback failure")
        every { image.planes } throws original
        val actual = assertThrows(IllegalArgumentException::class.java) {
            QRCodeAnalyzer(onDecoded = {}, onFailure = { assertSame(original, it); throw callback }).analyze(image)
        }
        assertSame(callback, actual)
        verify(exactly = 1) { image.close() }
    }
}
