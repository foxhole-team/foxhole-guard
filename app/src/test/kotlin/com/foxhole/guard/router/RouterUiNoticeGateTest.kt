package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterUiNoticeGateTest {
    @Test fun returningToTheSamePageDoesNotRepeatTheSameStateNotice() {
        val gate = RouterUiNoticeGate()
        assertTrue(gate.claim("connection", "VPN_ACTIVE"))
        repeat(5) { assertFalse(gate.claim("connection", "VPN_ACTIVE")) }
    }
    @Test fun aNewCauseOrFreshAttemptCanNotify() {
        val gate = RouterUiNoticeGate()
        assertTrue(gate.claim("connection", "NETWORK"))
        assertFalse(gate.claim("connection", null))
        assertTrue(gate.claim("connection", "NETWORK"))
        assertTrue(gate.claim("connection", "PIN"))
    }
    @Test fun anotherNotificationSlotDoesNotResetAnExistingCause() {
        val gate = RouterUiNoticeGate()
        assertTrue(gate.claim("connection", "VPN_ACTIVE"))
        assertTrue(gate.claim("update", "DONE"))
        assertFalse(gate.claim("connection", "VPN_ACTIVE"))
    }
}
