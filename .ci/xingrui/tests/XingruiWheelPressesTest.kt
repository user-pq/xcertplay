package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiWheelPressesTest {
    @Test fun heldKeySendsOnceAndOwnsExactlyOneRelease() {
        val keys = XingruiWheelPresses()
        var sent = 0
        assertTrue(keys.down(305) { sent++; true })
        assertTrue(keys.down(305) { sent++; true })
        assertEquals(1, sent)
        assertTrue(keys.up(305))
        assertFalse(keys.up(305))
        assertTrue(keys.down(305) { sent++; true })
        assertEquals(2, sent)
    }
    @Test fun failedSendDoesNotClaimReleaseOrOtherKey() {
        val keys = XingruiWheelPresses()
        assertFalse(keys.down(305) { false })
        assertFalse(keys.up(305))
        assertTrue(keys.down(87) { true })
        assertFalse(keys.up(88))
        assertTrue(keys.up(87))
    }
    @Test fun closingSessionDropsHeldKeys() {
        val keys = XingruiWheelPresses()
        assertTrue(keys.down(305) { true })
        keys.clear()
        assertFalse(keys.up(305))
        assertFalse(keys.down(305) { false })
    }
}
