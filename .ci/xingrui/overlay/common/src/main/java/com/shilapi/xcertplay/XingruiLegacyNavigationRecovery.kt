package com.shilapi.xcertplay

internal interface XingruiLegacyNavigationState {
    fun heldByOldBuild(): Boolean
    fun previousState(): Int
    fun currentState(): Int
    fun restore(state: Int)
    fun clearRecoveryRecord(): Boolean
}

/** Only undoes our R6 journaled changes. Never creates a disabled/suspended/stopped package state. */
internal object XingruiLegacyNavigationRecovery {
    fun recover(state: XingruiLegacyNavigationState): Boolean = runCatching {
        if (!state.heldByOldBuild()) return true
        val previous = state.previousState()
        require(previous == 0 || previous == 1) { "Invalid recorded navigation enabled state" }
        if (state.currentState() != previous) state.restore(previous)
        check(state.currentState() == previous) { "Navigation enabled state did not recover" }
        state.clearRecoveryRecord()
    }.getOrDefault(false)
}
