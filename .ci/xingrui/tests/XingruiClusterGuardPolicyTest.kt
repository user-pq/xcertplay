package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiClusterGuardPolicyTest {
    private fun claim(platform: Boolean = true, optedOut: Boolean = false, held: Boolean = false, enabled: Boolean? = true) =
        XingruiClusterGuardPolicy.onClaim(platform, optedOut, held, enabled)

    @Test fun platformBuildDisablesAnEnabledNativeService() {
        assertEquals(XingruiGuardAction.DISABLE_NATIVE, claim())
    }
    @Test fun nonPlatformBuildNeverTouchesTheService() {
        assertEquals(XingruiGuardAction.NONE, claim(platform = false))
    }
    @Test fun optOutPreventsDisabling() {
        assertEquals(XingruiGuardAction.NONE, claim(optedOut = true))
    }
    @Test fun optingOutWhileHeldGivesTheServiceBackImmediately() {
        assertEquals(XingruiGuardAction.RESTORE_NATIVE, claim(optedOut = true, held = true))
    }
    @Test fun alreadyHeldByUsDoesNotDisableAgainOnSwitchRetries() {
        assertEquals(XingruiGuardAction.NONE, claim(held = true, enabled = false))
        assertEquals(XingruiGuardAction.NONE, claim(held = true, enabled = true))
    }
    @Test fun serviceDisabledBySomeoneElseIsNotOursToManage() {
        assertEquals(XingruiGuardAction.NONE, claim(enabled = false))
        assertEquals(XingruiGuardAction.NONE, claim(held = false, enabled = false))
    }
    @Test fun unreadablePackageStateDoesNothing() {
        assertEquals(XingruiGuardAction.NONE, claim(enabled = null))
    }
    @Test fun releaseRestoresOnlyWhatWeDisabled() {
        assertEquals(XingruiGuardAction.RESTORE_NATIVE, XingruiClusterGuardPolicy.onRelease(true))
        assertEquals(XingruiGuardAction.NONE, XingruiClusterGuardPolicy.onRelease(false))
    }
    // Replays the car's flow: stream restarts (several claims), session ends, boot recovery after a crash.
    @Test fun sessionLifecycleAlwaysEndsWithTheServiceRestored() {
        var held = false
        var nativeEnabled = true
        fun apply(a: XingruiGuardAction) {
            if (a == XingruiGuardAction.DISABLE_NATIVE) { held = true; nativeEnabled = false }
            if (a == XingruiGuardAction.RESTORE_NATIVE) { held = false; nativeEnabled = true }
        }
        repeat(3) { apply(claim(held = held, enabled = nativeEnabled)) } // first frame, retry, stream restart
        assertTrue(held && !nativeEnabled)
        apply(XingruiClusterGuardPolicy.onRelease(held)) // session end
        assertTrue(!held && nativeEnabled)
        apply(claim(held = held, enabled = nativeEnabled)) // next session
        assertTrue(held)
        // process died mid-session; boot receiver runs the same release rule
        apply(XingruiClusterGuardPolicy.onRelease(held))
        assertTrue(!held && nativeEnabled)
        apply(XingruiClusterGuardPolicy.onRelease(held)) // second boot: nothing to do
        assertTrue(nativeEnabled)
    }
}
