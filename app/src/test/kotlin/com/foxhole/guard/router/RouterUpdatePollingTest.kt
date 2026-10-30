package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterUpdatePollingTest {
    private fun state() = RouterControlState(stage = RouterControlStage.CONNECTED,
        preferences = RouterControlPreferences("pair-test", enabled = true), selectedRouterId = "saved-test",
        updates = RouterUpdateControlState(supported = true,
            job = parseRouterUpdateJob(UpdateDocuments.job(), UpdateDocuments.ROUTER), pollEpoch = 1))

    @Test fun networkFlipsUseFiniteBackoffWithoutResettingTheNinetySecondBudget() {
        val budget = RouterUpdatePollBudget(0, "saved-test")
        val offline = state().copy(stage = RouterControlStage.OFFLINE, updates = state().updates.copy(unreachable = true))
        assertEquals(2000L, budget.next(offline, 0)?.delayMs)
        assertEquals(1500L, budget.next(state(), 2000)?.delayMs)
        assertEquals(4000L, budget.next(offline, 4000)?.delayMs)
        assertEquals(8000L, budget.next(offline, 8000)?.delayMs)
        assertNull(budget.next(offline, 90_000))
    }

    @Test fun recoveryStopsAfterTenAttemptsEvenIfWallClockHasNotAdvanced() {
        val budget = RouterUpdatePollBudget(0, "saved-test")
        val offline = state().copy(stage = RouterControlStage.OFFLINE)
        repeat(10) { assertNotNull(budget.next(offline, 0)) }
        assertNull(budget.next(offline, 0))
    }

    @Test fun authenticationRouterSelectionAndUnknownStartupStopRecovery() {
        val budget = RouterUpdatePollBudget(0, "saved-test")
        assertNull(budget.next(state().copy(stage = RouterControlStage.PAIRING_REQUIRED), 0))
        assertNull(budget.next(state().copy(selectedRouterId = "other"), 0))
        assertNull(budget.next(state().copy(error = RouterWizardError.IDENTITY), 0))
        assertNull(budget.next(state().copy(updates = RouterUpdateControlState(reconnecting = true, unreachable = true)), 0))
    }

    @Test fun leavingAndReenteringThePageCannotRenewTheSameJobBudget() {
        // The remembered per-router/job object exists before the first visible
        // page starts it. Subsequent page effects reuse that same object.
        val budget = RouterUpdatePollBudget(selectedRouterId = "saved-test")
        assertEquals(10_000L, budget.begin(10_000L))
        val offline = state().copy(stage = RouterControlStage.OFFLINE)
        assertEquals(2000L, budget.next(offline, 10_000L)?.delayMs)
        assertEquals(10_000L, budget.begin(99_000L))
        assertNull(budget.next(offline, 99_000L)) // only1s left; no renewed recovery window
        assertNull(budget.next(offline, 100_000L))
        assertEquals(10_000L, budget.begin(310_000L))
        assertNull(budget.next(state(), 310_000L)) // total5min also remains consumed
    }

    @Test fun overallForegroundTransactionBudgetDoesNotResetWithConnectionState() {
        val budget = RouterUpdatePollBudget(0, "saved-test")
        assertNotNull(budget.next(state(), 250_000))
        assertNull(budget.next(state(), 300_000))
    }
}
