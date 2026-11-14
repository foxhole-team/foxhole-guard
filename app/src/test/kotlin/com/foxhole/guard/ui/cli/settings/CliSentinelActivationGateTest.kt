package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.ui.FoxholeUpdatePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliSentinelActivationGateTest {
    private val stamp = "2026-11-12T08:26:59Z"
    @Test fun repeatedDoneCannotAuthorizeANewOrCancelledRequest() {
        assertFalse(sentinelEnableRequestVerified(2, 1, true, FoxholeUpdatePhase.DONE, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(2, -1, true, FoxholeUpdatePhase.DONE, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(2, 2, false, FoxholeUpdatePhase.DONE, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(3, 2, true, FoxholeUpdatePhase.DONE, stamp, stamp))
    }
    @Test fun fastVerifiedCompletionCanActivateButLivePhaseOrDataChangesCannot() {
        // No observed CHECKING transition is required: the explicit request epoch owns completion.
        assertTrue(sentinelEnableRequestVerified(2, 2, true, FoxholeUpdatePhase.DONE, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(2, 2, true, FoxholeUpdatePhase.CHECKING, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(2, 2, true, FoxholeUpdatePhase.FAILED, stamp, stamp))
        assertFalse(sentinelEnableRequestVerified(2, 2, true, FoxholeUpdatePhase.DONE, null, stamp))
        assertFalse(sentinelEnableRequestVerified(2, 2, true, FoxholeUpdatePhase.DONE,
            "2026-11-11T08:26:59Z", stamp))
    }
}
