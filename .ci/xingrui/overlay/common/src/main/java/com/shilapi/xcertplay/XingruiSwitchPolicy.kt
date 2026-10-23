package com.shilapi.xcertplay

internal enum class XingruiSwitchVerdict { CONFIRMED, RETRY, GAVE_UP }

/**
 * Decides, from SDK read-back values only, whether a cluster switch request took effect in software.
 * CONFIRMED is NOT proof of physical cluster visibility; it only means the SDK state matches the request.
 */
internal object XingruiSwitchPolicy {
    const val MAX_RETRIES = 3
    fun delayMillis(retry: Int): Long = retry * 1500L

    /**
     * @param switchState getPresentationToDimSwitch() read-back, null when unavailable
     * @param internalProjection navigation service isScreenProjection, null when unknown
     * @param failed the request, the power-source call or the read-back threw
     * @param retriesUsed retries already spent for this request
     */
    fun verdict(requested: Boolean, switchState: Any?, internalProjection: Any?, failed: Boolean, retriesUsed: Int): XingruiSwitchVerdict {
        // Unknown internal state never establishes success. Release must match internally, too.
        val matches = !failed && switchState == requested && internalProjection == requested
        return when {
            matches -> XingruiSwitchVerdict.CONFIRMED
            retriesUsed < MAX_RETRIES -> XingruiSwitchVerdict.RETRY
            else -> XingruiSwitchVerdict.GAVE_UP
        }
    }
}
