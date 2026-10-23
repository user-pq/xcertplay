package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiClusterGuardPolicyTest {
    @Test fun newSessionsPreserveAnEnabledOrDisabledNativeService() {
        for (enabled in listOf(null, false, true)) for (platform in listOf(false, true)) {
            assertEquals(XingruiGuardAction.NONE, XingruiClusterGuardPolicy.onClaim(platform, false, false, enabled))
        }
    }
    @Test fun onlyOurOldJournalAuthorizesARecovery() {
        assertEquals(XingruiGuardAction.RESTORE_NATIVE, XingruiClusterGuardPolicy.onRelease(true))
        assertEquals(XingruiGuardAction.NONE, XingruiClusterGuardPolicy.onRelease(false))
    }
    @Test fun oldRecoveryStillRunsAfterOptOut() {
        assertEquals(XingruiGuardAction.RESTORE_NATIVE, XingruiClusterGuardPolicy.onClaim(true, true, true, false))
    }
}
