package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterPinPolicyTest {
    @Test fun onlySixNumericDigitsAreAccepted() {
        assertTrue(validRouterPin("123456".toCharArray()))
        for (value in listOf("1234", "12345", "1234567", "12a456", "１２３４５６")) assertFalse(validRouterPin(value.toCharArray()))
    }
    @Test fun serverCooldownProjectsToABoundedMonotonicDeadline() {
        assertEquals(60, routerPinCooldownRemaining(routerPinCooldownUntil(60, 100), 100))
        assertEquals(1, routerPinCooldownRemaining(1100, 1001))
        assertEquals(0, routerPinCooldownRemaining(1100, 1100))
        for (value in listOf(0, -1, 301)) try { routerPinCooldownUntil(value, 100); fail("invalid server duration") }
            catch (_: IllegalArgumentException) { }
    }
}
