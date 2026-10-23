package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiKeyRouterTest {
    @Test fun defaultCodesMapToExpectedActions() {
        assertEquals(XingruiKeyAction.NEXT, XingruiKeyRouter.defaultAction(200087))
        assertEquals(XingruiKeyAction.PREVIOUS, XingruiKeyRouter.defaultAction(200088))
        assertEquals(XingruiKeyAction.NEXT, XingruiKeyRouter.defaultAction(305))
        assertEquals(XingruiKeyAction.PREVIOUS, XingruiKeyRouter.defaultAction(304))
        assertEquals(XingruiKeyAction.NEXT, XingruiKeyRouter.defaultAction(87))
        assertEquals(XingruiKeyAction.PREVIOUS, XingruiKeyRouter.defaultAction(88))
        assertEquals(XingruiKeyAction.PLAY_PAUSE, XingruiKeyRouter.defaultAction(85))
        assertEquals(XingruiKeyAction.PLAY, XingruiKeyRouter.defaultAction(126))
        assertEquals(XingruiKeyAction.PAUSE, XingruiKeyRouter.defaultAction(127))
    }
    @Test fun vehicleKeysAndUnknownCodesHaveNoAction() {
        XingruiKeyRouter.RESERVED.forEach { assertNull("code $it", XingruiKeyRouter.defaultAction(it)) }
        assertNull(XingruiKeyRouter.defaultAction(999))
    }
    @Test fun everyDefaultCodeHasAnAction() {
        XingruiKeyRouter.DEFAULT_CODES.forEach { assertNotNull("code $it", XingruiKeyRouter.defaultAction(it)) }
    }
    @Test fun normalRequestIsDefaultsPlusLearnedSortedAndDistinct() {
        val codes = XingruiKeyRouter.candidates(learned = listOf(400, 87), discovered = listOf(500), learning = false)
        assertArrayEquals(intArrayOf(400, 200087, 200088), codes)
    }
    @Test fun learningAddsDiscoveredSdkConstantsAndVoiceKey() {
        val codes = XingruiKeyRouter.candidates(learned = emptyList(), discovered = listOf(500, 85), learning = true)
        assertTrue(500 in codes)
        assertTrue(XingruiKeyRouter.KEYCODE_VOICE_ASSIST in codes)
        assertEquals(codes.size, codes.toSet().size)
    }
    @Test fun volumeHomePowerAndCallKeysAreNeverRequested() {
        val codes = XingruiKeyRouter.candidates(
            learned = listOf(24, 25, 26, 3, 301), discovered = listOf(4, 5, 6, 164, 302, 0), learning = true)
        XingruiKeyRouter.RESERVED.forEach { assertFalse("code $it", it in codes) }
    }
    @Test fun sdkConstantNameFilterKeepsMediaAndVoiceDropsVolume() {
        assertTrue(XingruiKeyRouter.isSdkKeyName("KEYCODE_MEDIA_NEXT"))
        assertTrue(XingruiKeyRouter.isSdkKeyName("keycode_voice_assist"))
        assertTrue(XingruiKeyRouter.isSdkKeyName("VR_START"))
        assertFalse(XingruiKeyRouter.isSdkKeyName("KEYCODE_VOLUME_UP"))
        assertFalse(XingruiKeyRouter.isSdkKeyName("KEYCODE_HOME"))
    }
    // Car capture 2026-10-18/23 (FS11A1): raw wheel keys 304/305/302 reach apps as SDK ids 200088/200087/200025.
    @Test fun requestsSdkActionIdsNotRawKeycodes() {
        val requested = XingruiKeyRouter.candidates(emptyList(), emptyList(), learning = false).toSet()
        assertEquals(setOf(200087, 200088), requested)
        listOf(304, 305, 301, 302, 87, 88, 200024, 200025).forEach { assertFalse("code $it", it in requested) }
    }
    @Test fun volumeSdkIdsStayWithTheVehicleEvenWhenLearned() {
        val codes = XingruiKeyRouter.candidates(listOf(200024, 200025), listOf(200025), learning = true)
        assertFalse(200024 in codes || 200025 in codes)
    }
    @Test fun sdkIdsMapToTheSameActionsAsTheirRawKeycodes() {
        assertEquals(XingruiKeyRouter.defaultAction(304), XingruiKeyRouter.defaultAction(200088))
        assertEquals(XingruiKeyRouter.defaultAction(305), XingruiKeyRouter.defaultAction(200087))
    }
}
