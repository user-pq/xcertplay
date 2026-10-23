package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class XingruiWheelContractTest {
    private lateinit var sdk: XingruiFixtureInput
    private lateinit var clock: XingruiManualScheduler
    private lateinit var session: XingruiWheelSession
    private val assignments = linkedMapOf<XingruiWheelRole, Int>()
    private val sent = mutableListOf<Pair<XingruiWheelRole?, Int>>()
    private val logs = mutableListOf<Pair<String, String>>()
    private var calling = false
    private var sendSucceeds = true
    @Before fun setup() {
        sdk = XingruiFixtureInput(); XingruiFixtureInput.current = sdk; clock = XingruiManualScheduler()
        assignments.clear(); sent.clear(); logs.clear(); calling = false; sendSucceeds = true
        session = XingruiWheelSession(xingruiInputPort(), clock, { assignments.toMap() },
            { role, code -> assignments[role] = code }, { role, code -> sent.add(role to code); sendSucceeds },
            { calling }, { area, message -> logs.add(area to message) })
    }
    private fun learn(done: (Int) -> Unit = {}, cancel: () -> Unit = {}, refused: (XingruiWheelRole) -> Unit = {}): Boolean =
        session.learn(XingruiWheelRole.NEXT, 5000, done, cancel, refused)
    @Test fun learningWithoutCarPlayPersistsCodeAndNextSessionUsesIt() {
        var captured = 0
        assertTrue(learn(done = { captured = it })); assertTrue(400 in sdk.codes)
        val callback = sdk.callback!!
        assertTrue(callback.onKeyPressed(400)); clock.ready()
        assertEquals(400, captured); assertEquals(400, assignments[XingruiWheelRole.NEXT]); assertTrue(sent.isEmpty())
        assertTrue(callback.onKeyReleased(400)); clock.ready(); assertNull(sdk.callback)
        session.playback(true); assertTrue(sdk.callback!!.onKeyPressed(400))
        assertEquals(listOf(XingruiWheelRole.NEXT to 400), sent)
    }
    @Test fun registrationResultIsNotReceiptEvidence() {
        sdk.result = intArrayOf(200087); session.playback(true)
        assertFalse(session.observedCallback)
        assertTrue(sdk.callback!!.onKeyPressed(200087)); assertTrue(session.observedCallback)
    }
    @Test fun oldAndroidMappingIsRequestedAndMatchedAsSdkAction() {
        assignments[XingruiWheelRole.SIRI] = 305
        session.playback(true)
        assertTrue(200087 in sdk.codes); assertFalse(305 in sdk.codes)
        assertTrue(sdk.callback!!.onKeyPressed(200087))
        assertEquals(listOf(XingruiWheelRole.SIRI to 200087), sent)
    }
    @Test fun longPressSendsOnceAndReleaseIsConsumedOnceWithDeduplicatedRawLogs() {
        session.playback(true); val callback = sdk.callback!!
        repeat(20) { assertTrue(callback.onKeyPressed(305)) }
        assertEquals(1, sent.size); assertTrue(callback.onKeyReleased(305)); assertFalse(callback.onKeyReleased(305))
        assertEquals(2, logs.count { it.first == "方控回调" })
    }
    @Test fun unknownReservedAndInCallKeysPassBackToVehicle() {
        session.playback(true); val cb = sdk.callback!!
        assertFalse(cb.onKeyPressed(999)); assertFalse(cb.onKeyReleased(999))
        assertFalse(cb.onKeyPressed(24)); assertFalse(cb.onKeyReleased(24))
        calling = true; assertFalse(cb.onKeyPressed(305)); assertFalse(cb.onKeyReleased(305)); assertTrue(sent.isEmpty())
    }
    @Test fun failedSendDoesNotClaimRelease() {
        sendSucceeds = false; session.playback(true); val cb = sdk.callback!!
        assertFalse(cb.onKeyPressed(305)); assertFalse(cb.onKeyReleased(305))
    }
    @Test fun learningDoesNotStealReservedKeyEvenIfSdkUnexpectedlyDeliversIt() {
        assertTrue(learn()); val cb = sdk.callback!!
        assertFalse(cb.onKeyPressed(24)); assertFalse(cb.onKeyReleased(24)); clock.ready(); assertTrue(assignments.isEmpty())
    }
    @Test fun callbackIsNotReplacedBeforeCapturedKeyRelease() {
        learn(); val cb = sdk.callback!!; val count = sdk.events.size
        cb.onKeyPressed(400); clock.ready(); session.settingsChanged()
        assertSame(cb, sdk.callback); assertEquals(count, sdk.events.size)
        cb.onKeyReleased(400); clock.ready(); assertNull(sdk.callback)
    }
    @Test fun cancellationInvalidatesQueuedCapture() {
        var done = 0
        learn(done = { done++ }); sdk.callback!!.onKeyPressed(400); session.cancelLearning(); clock.ready()
        assertTrue(assignments.isEmpty()); assertEquals(0, done)
    }
    @Test fun previousLearningTimeoutCannotCancelANewerWindow() {
        var cancelled = 0
        learn(); clock.advance(2000); learn(cancel = { cancelled++ })
        clock.advance(3000); assertEquals(0, cancelled); assertNotNull(sdk.callback)
        clock.advance(2000); assertEquals(1, cancelled); assertNull(sdk.callback)
    }
    @Test fun timeoutCancelsAndUnregistersWithoutSaving() {
        var cancelled = 0
        learn(cancel = { cancelled++ }); clock.advance(5000)
        assertEquals(1, cancelled); assertNull(sdk.callback); assertTrue(assignments.isEmpty())
    }
    @Test fun conflictingAssignmentIsRefusedAndOldMappingSurvives() {
        assignments[XingruiWheelRole.PREVIOUS] = 400; var conflict: XingruiWheelRole? = null
        learn(refused = { conflict = it }); sdk.callback!!.onKeyPressed(400); clock.ready()
        assertEquals(XingruiWheelRole.PREVIOUS, conflict); assertNull(assignments[XingruiWheelRole.NEXT])
    }
    @Test fun detachingPlaybackAbandonsCallbackAndLatePressDoesNotSend() {
        session.playback(true); val cb = sdk.callback!!
        session.playback(false); assertNull(sdk.callback); assertFalse(cb.onKeyPressed(305)); assertTrue(sent.isEmpty())
    }
    @Test fun deniedRegistrationDoesNotReportLearningStarted() {
        sdk.fail = true
        assertFalse(learn()); assertFalse(session.observedCallback); assertTrue(assignments.isEmpty())
    }
}
