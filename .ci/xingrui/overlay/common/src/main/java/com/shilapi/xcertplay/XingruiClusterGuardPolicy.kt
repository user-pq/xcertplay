package com.shilapi.xcertplay

internal enum class XingruiGuardAction { NONE, DISABLE_NATIVE, RESTORE_NATIVE }

/**
 * Safety rules for the cluster conflict handling. Pure, so the "never leave the vehicle's navigation
 * service disabled and never touch a service we did not disable" guarantees are unit-tested.
 * Car log 2026-10-23: ecarx.naviservice restarting (12:35:12) took the cluster back from CarPlay.
 */
internal object XingruiClusterGuardPolicy {
    const val NATIVE_PACKAGE = "ecarx.naviservice"

    /**
     * Disable only on a platform build, when the user has not opted out, the service is currently
     * enabled and we are not already holding it. Opting out while we hold it gives it back at once.
     * [nativeEnabled] is null when the package state could not be read.
     */
    fun onClaim(platform: Boolean, optedOut: Boolean, weDisabledIt: Boolean, nativeEnabled: Boolean?): XingruiGuardAction = when {
        optedOut && weDisabledIt -> XingruiGuardAction.RESTORE_NATIVE
        platform && !optedOut && !weDisabledIt && nativeEnabled == true -> XingruiGuardAction.DISABLE_NATIVE
        else -> XingruiGuardAction.NONE
    }

    /** Session end, app start after a crash, boot and package replacement all use this one rule. */
    fun onRelease(weDisabledIt: Boolean): XingruiGuardAction =
        if (weDisabledIt) XingruiGuardAction.RESTORE_NATIVE else XingruiGuardAction.NONE
}
