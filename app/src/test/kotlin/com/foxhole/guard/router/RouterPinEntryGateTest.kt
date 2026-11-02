package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterPinEntryGateTest {
    @Test fun exactSixDigitsSubmitOnlyOncePerNewEntry() {
        val gate = RouterPinEntryGate()
        assertFalse(gate.observe(0, true))
        for (length in 1..5) assertFalse(gate.observe(length, true))
        assertTrue(gate.observe(6, true))
        assertFalse(gate.observe(6, true))
        assertFalse(gate.observe(0, false))
        assertTrue(gate.observe(6, true))
    }
    @Test fun recompositionErrorResumeOrCooldownExpiryCannotRetryAnEntry() {
        val gate = RouterPinEntryGate()
        assertFalse(gate.observe(6, true))
        assertFalse(gate.observe(6, false))
        assertFalse(gate.observe(6, true))
        assertFalse(gate.observe(0, true))
        assertFalse(gate.observe(6, false))
        assertFalse(gate.observe(6, true))
    }
    @Test fun inactiveEntryRequiresAnActualEditAfterReactivation() {
        val gate = RouterPinEntryGate()
        assertFalse(gate.observe(0, false))
        assertFalse(gate.observe(6, false))
        assertFalse(gate.observe(6, true))
        assertFalse(gate.observe(5, true))
        assertTrue(gate.observe(6, true))
    }
}
