package com.shilapi.xcertplay

internal enum class XingruiSwitchVerdict { CONFIRMED, SENT_UNVERIFIED, RETRY, GAVE_UP }

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
     * @param failed a write request or power-source call threw; read-back unavailability is separate
     * @param retriesUsed retries already spent for this request
     */
    fun verdict(requested: Boolean, switchState: Any?, internalProjection: Any?, failed: Boolean, retriesUsed: Int): XingruiSwitchVerdict {
        val mismatch = (switchState is Boolean && switchState != requested) ||
            (internalProjection is Boolean && internalProjection != requested)
        return when {
            !failed && switchState == requested && internalProjection == requested -> XingruiSwitchVerdict.CONFIRMED
            // Unknown is neither confirmation nor a failed write. Repeating a write cannot supply a missing getter.
            !failed && !mismatch -> XingruiSwitchVerdict.SENT_UNVERIFIED
            retriesUsed < MAX_RETRIES -> XingruiSwitchVerdict.RETRY
            else -> XingruiSwitchVerdict.GAVE_UP
        }
    }
}
