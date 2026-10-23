package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiSwitchPolicyTest {
    private fun v(requested: Boolean, state: Any?, internal: Any? = null, failed: Boolean = false, used: Int = 0) =
        XingruiSwitchPolicy.verdict(requested, state, internal, failed, used)

    @Test fun readbackMatchingRequestIsConfirmed() {
        assertEquals(XingruiSwitchVerdict.CONFIRMED, v(true, true, true))
        assertEquals(XingruiSwitchVerdict.CONFIRMED, v(false, false, false))
    }
    @Test fun unknownInternalProjectionCannotConfirmEitherDirection() {
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(true, true, null))
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(false, false, null))
    }
    @Test fun switchStateDifferingFromRequestRetries() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, false))
        assertEquals(XingruiSwitchVerdict.RETRY, v(false, true))
    }
    @Test fun unavailableReadbackIsNeverConfirmed() {
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(true, null))
    }
    @Test fun internalProjectionOffBlocksEnableButNotRelease() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, true, false))
        assertEquals(XingruiSwitchVerdict.CONFIRMED, v(false, false, false))
    }
    @Test fun internalProjectionStillOnBlocksRelease() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(false, false, true))
    }
    @Test fun failedPowerRequestRetriesEvenWhenSwitchMatches() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, true, true, failed = true))
    }
    @Test fun givesUpAfterThreeRetries() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, false, used = 0))
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, false, used = 1))
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, false, used = 2))
        assertEquals(XingruiSwitchVerdict.GAVE_UP, v(true, false, used = XingruiSwitchPolicy.MAX_RETRIES))
    }
    @Test fun lateSuccessStillConfirmsOnTheLastAttempt() {
        assertEquals(XingruiSwitchVerdict.CONFIRMED, v(true, true, true, used = XingruiSwitchPolicy.MAX_RETRIES))
    }
    @Test fun backoffGrowsLinearly() {
        assertEquals(1500L, XingruiSwitchPolicy.delayMillis(1))
        assertEquals(3000L, XingruiSwitchPolicy.delayMillis(2))
        assertEquals(4500L, XingruiSwitchPolicy.delayMillis(3))
    }
    @Test fun unavailableGetterWithMatchingInternalStateIsSentWithoutClaimingConfirmation() {
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(true, null, true))
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(false, null, false))
        assertEquals(XingruiSwitchVerdict.SENT_UNVERIFIED, v(false, null, null, used = 3))
    }
    @Test fun aReadableConflictStillRetriesEvenWhenOtherReadbackIsUnavailable() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, null, false))
        assertEquals(XingruiSwitchVerdict.GAVE_UP, v(false, null, true, used = 3))
        assertEquals(XingruiSwitchVerdict.GAVE_UP, v(true, false, null, used = 3))
    }
    @Test fun failedWritesRemainFailuresEvenWithoutAnyReadback() {
        assertEquals(XingruiSwitchVerdict.RETRY, v(true, null, null, failed = true))
        assertEquals(XingruiSwitchVerdict.GAVE_UP, v(false, null, null, failed = true, used = 3))
    }
}
