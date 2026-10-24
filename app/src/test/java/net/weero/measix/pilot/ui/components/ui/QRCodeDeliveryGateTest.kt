package net.weero.measix.pilot.ui.components.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeDeliveryGateTest {
    @Test fun closeRevokesAnAlreadyQueuedFrameAndNeverReopens() {
        val previousBinding = QRCodeDeliveryGate()
        assertTrue(previousBinding.queue())
        previousBinding.close()
        previousBinding.close()
        assertFalse(previousBinding.deliver())
        assertFalse(previousBinding.queue())
        val resumedBinding = QRCodeDeliveryGate()
        assertTrue(resumedBinding.queue())
        assertTrue(resumedBinding.deliver())
        assertFalse(previousBinding.deliver())
    }

    @Test fun repeatedFramesCanOnlyDeliverOnce() {
        val gate = QRCodeDeliveryGate()
        assertFalse(gate.deliver())
        assertTrue(gate.queue())
        assertFalse(gate.queue())
        assertTrue(gate.deliver())
        assertFalse(gate.deliver())
        assertFalse(gate.queue())
    }
}
