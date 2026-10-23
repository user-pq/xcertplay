package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiExitCoordinatorTest {
    private val clock = XingruiManualScheduler()
    private val results = mutableListOf<XingruiExitResult>()
    private val display = mutableListOf<(Boolean) -> Unit>()
    private val recovery = mutableListOf<(Boolean) -> Unit>()
    private fun gate(count: Int = 1) = XingruiExitCoordinator(clock,
        List(count) { { done -> display.add(done); Unit } }, { recovery.add(it) }, { results.add(it) })

    @Test fun teardownWaitsForEveryDisplayAndNativeRecovery() {
        gate(2).start(); assertTrue(results.isEmpty())
        display[0](true); recovery[0](true); clock.ready(); assertTrue(results.isEmpty())
        display[1](true); clock.ready()
        assertEquals(listOf(XingruiExitResult(true, true, false)), results)
    }
    @Test fun duplicateAndLateCallbacksNeverCompleteAnExitTwice() {
        val g = gate(); g.start(); g.start()
        display[0](true); display[0](false); recovery[0](true); clock.ready()
        display[0](false); recovery[0](false); clock.advance(20000)
        assertEquals(1, display.size); assertEquals(1, results.size); assertTrue(results.single().sdkReleased)
    }
    @Test fun aStalledSdkHasABoundedWaitAndRemainsUnconfirmed() {
        gate().start(); recovery[0](true); clock.ready()
        clock.advance(XingruiExitCoordinator.DEADLINE_MILLIS - 1); assertTrue(results.isEmpty())
        clock.advance(1); assertEquals(listOf(XingruiExitResult(false, true, true)), results)
        display[0](true); clock.ready(); assertEquals(1, results.size)
    }
    @Test fun missingRecoveryCallbackCannotReportNativeRestored() {
        gate().start(); display[0](true); clock.advance(12000)
        assertEquals(listOf(XingruiExitResult(true, false, true)), results)
    }
    @Test fun aFailedDisplayReleaseDoesNotBecomeASuccess() {
        gate().start(); display[0](false); recovery[0](true); clock.ready()
        assertEquals(listOf(XingruiExitResult(false, true, false)), results)
    }
    @Test fun recoveryRetriesAreBoundedAndAStaleCallbackCannotWin() {
        gate(0).start(); val old = recovery[0]
        old(false); clock.ready(); old(true); clock.ready(); assertTrue(results.isEmpty())
        clock.advance(500); recovery[1](false); clock.ready(); clock.advance(1000)
        recovery[2](false); clock.ready(); clock.advance(20000)
        assertEquals(3, recovery.size); assertEquals(listOf(XingruiExitResult(true, false, false)), results)
    }
    @Test fun recoveryCanSucceedOnItsLastRetry() {
        gate(0).start(); recovery[0](false); clock.advance(500)
        recovery[1](false); clock.advance(1000); recovery[2](true); clock.ready()
        assertEquals(listOf(XingruiExitResult(true, true, false)), results)
    }
    @Test fun throwingServicesStillProduceABoundedResult() {
        XingruiExitCoordinator(clock, listOf({ throw SecurityException("SDK") }),
            { throw SecurityException("native") }, { results.add(it) }).start()
        clock.advance(20000)
        assertEquals(listOf(XingruiExitResult(false, false, false)), results)
    }
    @Test fun registryIncludesAReleaseAlreadyStartedByPresentationOnStop() {
        val registry = XingruiReleaseRegistry(); val completion = XingruiReleaseCompletion()
        var requests = 0
        val handle = XingruiReleaseHandle { done -> requests++; completion.await(done) }
        registry.add(handle); handle.release { } // Presentation.onStop precedes software exit.
        XingruiExitCoordinator(clock, registry.pending(), { it(true) }, { results.add(it) }).start()
        clock.ready(); assertTrue(results.isEmpty()); assertEquals(2, requests)
        completion.finish(true); registry.remove(handle); clock.ready()
        assertTrue(results.single().sdkReleased); assertTrue(registry.pending().isEmpty())
    }
    @Test fun sessionRetriesFinishBeforeTeardownAndDisconnectHappensFirst() {
        val sdk = XingruiFixtureDimImpl(); XingruiFixtureDim.current = sdk
        val done = XingruiReleaseCompletion()
        val session = XingruiDimSession(xingruiDimPort(), clock, { true }, {}, { done.finish(it) })
        session.start(); sdk.watcher!!.onConnected(); clock.ready(); session.video(true)
        sdk.followsRequest = false
        XingruiExitCoordinator(clock, listOf({ callback -> done.await(callback); session.close() }),
            { it(true) }, { results.add(it); sdk.events.add("teardown") }).start()
        clock.ready(); assertTrue(results.isEmpty()); assertFalse(sdk.events.contains("disconnect"))
        (sdk.navi as XingruiFixtureLiveNavi).isScreenProjection = false
        clock.advance(1500)
        assertTrue(results.single().sdkReleased)
        assertTrue(sdk.events.indexOf("disconnect") < sdk.events.indexOf("teardown"))
    }
    @Test fun completionNotifiesEveryWaiterIncludingLateSubscribersOnce() {
        val done = XingruiReleaseCompletion(); val seen = mutableListOf<Boolean>()
        done.await { seen.add(it); throw IllegalStateException("listener") }
        done.await { seen.add(it) }; done.finish(false); done.finish(true); done.await { seen.add(it) }
        assertEquals(listOf(false, false, false), seen)
    }
    @Test fun concurrentShutdownCallersWaitForOneCleanupInsteadOfFinishingEarly() {
        val completion = XingruiReleaseCompletion(); var callbacks = 0
        completion.await { callbacks++ }; completion.await { callbacks++ }
        XingruiExitCoordinator(clock, listOf({ display.add(it); Unit }), { recovery.add(it) },
            { results.add(it); completion.finish(true) }).start()
        recovery[0](true); clock.ready(); assertEquals(0, callbacks)
        display[0](true); clock.ready(); assertEquals(2, callbacks)
        assertEquals(1, display.size); assertEquals(1, results.size)
    }
    @Test fun unsupportedGetterCompletesExitAsUnconfirmedWithoutAFalseTimeoutOrRepeatedWrite() {
        val sdk = XingruiFixtureDimImpl(); XingruiFixtureDim.current = sdk; sdk.readAbstract = true
        val done = XingruiReleaseCompletion()
        val session = XingruiDimSession(xingruiDimPort(), clock, { true }, {}, { done.finish(it) })
        session.start(); sdk.watcher!!.onConnected(); clock.ready(); session.video(true)
        XingruiExitCoordinator(clock, listOf({ callback -> done.await(callback); session.close() }),
            { it(true) }, { results.add(it) }).start()
        clock.ready()
        assertEquals(0L, clock.now)
        assertEquals(listOf(XingruiExitResult(false, true, false)), results)
        assertEquals(listOf(true, false), sdk.requests)
        clock.advance(20000); assertEquals(1, results.size)
    }
}
