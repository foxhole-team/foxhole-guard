package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeDbGroupUi
import com.foxhole.guard.ui.FoxholeUpdatePhase
import org.junit.Assert.*
import org.junit.Test

class DnsPreparedDisplayTest {
    @Test fun stagedDataWaitsForCoreInsteadOfClaimingMissingOrActive() {
        val group = FoxholeDbGroupUi(true, null, preparedPending = true)
        assertFalse(group.needsData)
        val shown = foxholeDbGroupPresentation(group, FoxholeUpdatePhase.IDLE)
        assertEquals(R.string.dns_category_prepared_short, shown.labelRes)
        assertEquals(FoxholeDbGroupTone.WARNING, shown.tone)
    }
    @Test fun realFailureStillWinsOverPreviouslyPreparedData() {
        val shown = foxholeDbGroupPresentation(FoxholeDbGroupUi(true, null, preparedPending = true), FoxholeUpdatePhase.FAILED)
        assertEquals(FoxholeDbGroupTone.ERROR, shown.tone)
    }
    @Test fun checkingCachedPreparationDoesNotFlashMissingData() {
        val group = FoxholeDbGroupUi(true, null, checkingPreparation = true)
        assertFalse(group.needsData)
        assertEquals(FoxholeDbGroupTone.WARNING, foxholeDbGroupPresentation(group, FoxholeUpdatePhase.IDLE).tone)
        assertTrue(FoxholeDbGroupUi(true, null).needsData)
    }
    @Test fun aNewUpdateStillNeedsDataEvenWithPreparedPreviousData() {
        assertTrue(FoxholeDbGroupUi(true, null, updateAvailable = true, preparedPending = true).needsData)
    }
}
