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
    // Car log 2026-10-18 23:00: stream restart, DIM connect, first frame, then a second first frame without a stop.
    @Test fun secondFirstFrameWithoutStopDoesNotSwitchAgain() {
        val gate = XingruiProjectionGate()
        assertNull(gate.video(false))
        assertNull(gate.connection(true))
        assertEquals(true, gate.video(true))
        assertNull(gate.video(true))
    }
    @Test fun streamStopReleasesAndNextFrameReasserts() {
        val gate = XingruiProjectionGate()
        gate.connection(true)
        assertEquals(true, gate.video(true))
        assertEquals(false, gate.video(false))
        assertEquals(true, gate.video(true))
    }
    @Test fun disconnectWhileStoppedDoesNotReassertOnReconnect() {
        val gate = XingruiProjectionGate()
        gate.connection(true); gate.video(true)
        assertNull(gate.connection(false))
        assertNull(gate.video(false))
        assertNull(gate.connection(true))
    }
}
