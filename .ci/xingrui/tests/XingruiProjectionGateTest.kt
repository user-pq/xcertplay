package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiProjectionGateTest {
    @Test fun connectedBeforeVideoWaitsThenSwitchesOnce() {
        val gate = XingruiProjectionGate()
        assertNull(gate.connection(true))
        assertEquals(true, gate.video(true))
        assertNull(gate.video(true))
        assertEquals(false, gate.video(false))
        assertNull(gate.close())
    }
    @Test fun videoBeforeConnectionWaitsAndReconnectReasserts() {
        val gate = XingruiProjectionGate()
        assertNull(gate.video(true))
        assertEquals(true, gate.connection(true))
        assertNull(gate.connection(false))
        assertEquals(true, gate.connection(true))
        assertEquals(false, gate.close())
    }
    @Test fun staleFrameAndConnectionAfterCloseCannotEnableOutput() {
        val gate = XingruiProjectionGate()
        assertNull(gate.close())
        assertNull(gate.video(true))
        assertNull(gate.connection(true))
    }
}
