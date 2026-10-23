package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class XingruiDimContractTest {
    private lateinit var sdk: XingruiFixtureDimImpl
    private lateinit var clock: XingruiManualScheduler
    private lateinit var session: XingruiDimSession
    private val messages = mutableListOf<String>()
    private var finishes = 0
    private var allowed = true
    private var claims = 0
    private var releases = 0
    @Before fun setup() {
        sdk = XingruiFixtureDimImpl(); XingruiFixtureDim.current = sdk
        clock = XingruiManualScheduler(); finishes = 0; allowed = true; messages.clear(); claims = 0; releases = 0
        session = XingruiDimSession(xingruiDimPort(), clock, { allowed }, messages::add, { finishes++ },
            { claims++; sdk.events.add("claim-native") }, { releases++; sdk.events.add("restore-native") })
    }
    private fun connected() { sdk.watcher!!.onConnected(); clock.ready() }
    private fun enable() { session.start(); connected(); session.video(true) }
    @Test fun watcherRegistersBeforeConnectAndNoWritePrecedesBothReadinessSignals() {
        session.start(); assertEquals(listOf("watch", "connect"), sdk.events)
        session.video(true); assertTrue(sdk.requests.isEmpty())
        connected(); assertEquals(listOf(true), sdk.requests)
        assertTrue(sdk.events.indexOf("claim-native") < sdk.events.indexOf("request:0:owner.pkg:owner.Host:true"))
        assertTrue(sdk.events.indexOf("source:false:2") < sdk.events.indexOf("read"))
    }
    @Test fun connectionBeforeFrameWaitsAndDuplicateFramesDoNotWriteAgain() {
        session.start(); connected(); assertTrue(sdk.requests.isEmpty())
        session.video(true); session.video(true); assertEquals(listOf(true), sdk.requests)
    }
    @Test fun disablingUltraBeforePendingAttemptPreventsAnEnableWrite() {
        session.start(); session.video(true); allowed = false
        connected(); assertTrue(sdk.requests.isEmpty())
        session.close(); assertEquals(1, finishes)
    }
    @Test fun nullNavigationNeverConfirmsAndRetriesOnlyThreeTimes() {
        sdk.navi = null; enable(); clock.advance(10000)
        assertEquals(listOf(true, true, true, true), sdk.requests)
        assertFalse(messages.any { it.contains("软件判定=CONFIRMED") })
        assertTrue(messages.any { it.contains("已重试 3 次") })
    }
    @Test fun readExceptionDoesNotConfirmEvenIfSwitchWasSet() {
        sdk.readFails = true; enable(); clock.advance(10000)
        assertEquals(4, sdk.requests.size); assertFalse(messages.any { it.contains("软件判定=CONFIRMED") })
    }
    @Test fun closeChecksInternalReleaseBeforeUnwatchAndDisconnect() {
        enable(); sdk.followsRequest = false
        session.close()
        assertEquals(listOf(true, false), sdk.requests); assertFalse(sdk.events.contains("disconnect"))
        (sdk.navi as XingruiFixtureLiveNavi).isScreenProjection = false
        clock.advance(1500)
        assertEquals(listOf(true, false, false), sdk.requests)
        assertEquals(listOf("unwatch", "disconnect"), sdk.events.takeLast(2)); assertEquals(1, finishes)
        assertEquals(1, releases)
    }
    @Test fun failedReleaseStillDisposesOnceAfterBoundedAttempts() {
        enable(); sdk.closeFails = true
        session.close(); session.close(); clock.advance(10000)
        assertEquals(4, sdk.requests.count { !it }); assertEquals(1, finishes)
        assertEquals(1, sdk.events.count { it == "disconnect" })
        assertTrue(messages.any { it.contains("仍无法确认") })
    }
    @Test fun closeInvalidatesPendingEnableRetryAndLateCallbacks() {
        sdk.navi = null; enable(); val late = sdk.watcher!!
        session.close(); late.onConnected(); session.video(true); clock.advance(15000)
        assertEquals(1, sdk.requests.count { it }); assertEquals(1, finishes)
    }
    @Test fun repeatedCloseAndLateFrameNeverReopenReleasedDisplay() {
        enable(); val late = sdk.watcher!!
        session.close(); session.close(); late.onConnected(); session.video(true); clock.advance(10000)
        assertEquals(listOf(true, false), sdk.requests); assertEquals(1, finishes)
    }
    @Test fun disconnectInvalidatesRetryAndReconnectReassertsOnlyLiveVideo() {
        sdk.navi = null; enable(); sdk.watcher!!.onDisConnected(); clock.ready(); clock.advance(10000)
        assertEquals(1, sdk.requests.size)
        sdk.navi = XingruiFixtureLiveNavi(sdk.events); connected()
        assertEquals(listOf(true, true), sdk.requests)
    }
    @Test fun stoppedStreamDoesNotReopenOnReconnect() {
        enable(); session.video(false); sdk.watcher!!.onDisConnected(); clock.ready(); connected()
        assertEquals(listOf(true, false), sdk.requests)
    }
    @Test fun closeWhileDisconnectedReportsUnknownInsteadOfClaimingRelease() {
        enable(); sdk.watcher!!.onDisConnected(); clock.ready(); session.close(); clock.advance(10000)
        assertEquals(listOf(true), sdk.requests); assertEquals(1, finishes)
        assertTrue(messages.any { it.contains("无法确认仪表释放") })
    }
    @Test fun failedEnableAndLostConnectionBothRestoreTheNativeService() {
        sdk.navi = null; enable(); clock.advance(10000)
        assertEquals(1, releases)
        sdk.navi = XingruiFixtureLiveNavi(sdk.events); sdk.watcher!!.onDisConnected(); clock.ready(); connected()
        assertEquals(1, releases)
        sdk.watcher!!.onDisConnected(); clock.ready()
        assertEquals(2, releases)
        session.close(); assertEquals(2, releases)
    }
    @Test fun staleReleaseRetryCannotRestoreNativeWhileANewStreamIsActive() {
        enable(); sdk.followsRequest = false; session.video(false)
        sdk.followsRequest = true; session.video(true); clock.advance(10000)
        assertEquals(listOf(true, false, true), sdk.requests); assertEquals(0, releases)
        session.close(); assertEquals(1, releases)
    }
}
