package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class XingruiLegacyNavigationRecoveryTest {
    private class State : XingruiLegacyNavigationState {
        var held = true; var previous = 0; var current = 3
        var fail = false; var follows = true; var durable = true
        val writes = mutableListOf<Int>(); var reads = 0
        override fun heldByOldBuild() = held
        override fun previousState() = previous
        override fun currentState(): Int { reads++; return current }
        override fun restore(state: Int) { writes.add(state); if (fail) throw SecurityException(); if (follows) current = state }
        override fun clearRecoveryRecord(): Boolean { if (durable) held = false; return durable }
    }
    @Test fun aNewInstallNeverReadsOrWritesTheNativePackage() {
        val s=State(); s.held=false
        assertTrue(XingruiLegacyNavigationRecovery.recover(s)); assertEquals(0,s.reads); assertTrue(s.writes.isEmpty())
    }
    @Test fun restoresTheExactPreviouslyRecordedEnabledState() {
        for (previous in listOf(0,1)) {
            val s=State(); s.previous=previous
            assertTrue(XingruiLegacyNavigationRecovery.recover(s)); assertEquals(listOf(previous),s.writes); assertFalse(s.held)
        }
    }
    @Test fun alreadyRestoredStateOnlyClearsOurRecoveryJournal() {
        val s=State(); s.current=0
        assertTrue(XingruiLegacyNavigationRecovery.recover(s)); assertTrue(s.writes.isEmpty()); assertFalse(s.held)
    }
    @Test fun deniedRecoveryRetainsTheJournalForRetry() {
        val s=State(); s.fail=true
        assertFalse(XingruiLegacyNavigationRecovery.recover(s)); assertTrue(s.held)
        s.fail=false; assertTrue(XingruiLegacyNavigationRecovery.recover(s))
    }
    @Test fun readBackMismatchCannotClearTheRecoveryJournal() {
        val s=State(); s.follows=false
        assertFalse(XingruiLegacyNavigationRecovery.recover(s)); assertTrue(s.held)
    }
    @Test fun failedJournalCommitCanBeRetriedWithoutAnotherPackageWrite() {
        val s=State(); s.durable=false
        assertFalse(XingruiLegacyNavigationRecovery.recover(s)); assertTrue(s.held)
        s.durable=true; assertTrue(XingruiLegacyNavigationRecovery.recover(s)); assertEquals(listOf(0),s.writes)
    }
    @Test fun corruptRecordsNeverAuthorizeDisablingOrSuspendingNavigation() {
        for (previous in listOf(-1,2,3,4,999)) {
            val s=State(); s.previous=previous
            assertFalse(XingruiLegacyNavigationRecovery.recover(s)); assertTrue(s.writes.isEmpty()); assertTrue(s.held)
        }
    }
    @Test fun noSequenceOfNewClaimsCreatesAPersistentDisable() {
        for (platform in listOf(false,true)) for (optedOut in listOf(false,true)) for (enabled in listOf(null,false,true)) {
            assertEquals(XingruiGuardAction.NONE, XingruiClusterGuardPolicy.onClaim(platform,optedOut,false,enabled))
            assertEquals(XingruiGuardAction.RESTORE_NATIVE, XingruiClusterGuardPolicy.onClaim(platform,optedOut,true,enabled))
        }
    }
}
