package com.shilapi.xcertplay

internal enum class XingruiGuardAction { NONE, RESTORE_NATIVE }

/**
 * Recovery-only policy. New sessions do not modify the native navigation package's enabled state.
 */
internal object XingruiClusterGuardPolicy {
    const val NATIVE_PACKAGE = "ecarx.naviservice"

    /**
     * Preserve the old call shape for compatibility; only our pre-existing journal authorizes recovery.
     */
    fun onClaim(platform: Boolean, optedOut: Boolean, weDisabledIt: Boolean, nativeEnabled: Boolean?): XingruiGuardAction = when {
        weDisabledIt -> XingruiGuardAction.RESTORE_NATIVE
        else -> XingruiGuardAction.NONE
    }

    /** Session end, app start after a crash, boot and package replacement all use this one rule. */
    fun onRelease(weDisabledIt: Boolean): XingruiGuardAction =
        if (weDisabledIt) XingruiGuardAction.RESTORE_NATIVE else XingruiGuardAction.NONE
}
