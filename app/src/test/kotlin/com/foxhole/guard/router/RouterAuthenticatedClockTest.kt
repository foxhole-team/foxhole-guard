package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterAuthenticatedClockTest {
    @Test fun onlyFreshAuthenticatedMonotonicBasisCanTimestampAHandoff() {
        val clock = RouterAuthenticatedClock()
        assertEquals(null, clock.estimate(10000))
        assertFalse(clock.observe(9000, 9900, 10000))
        assertEquals(14000L, clock.estimate(15000))
        assertEquals(null, clock.estimate(15001))
        assertEquals(null, clock.estimate(9999))
    }

    @Test fun rebootOrNewAuthenticationInvalidatesTheOldClockWithoutWallTime() {
        val clock = RouterAuthenticatedClock()
        clock.observe(500000, 9900, 10000)
        assertTrue(clock.observe(10, 10100, 10200))
        assertEquals(110L, clock.estimate(10300))
        clock.clear()
        assertEquals(null, clock.estimate(10300))
    }

    @Test fun delayedInvalidOrOverflowingBasisCannotAuthorizeARequest() {
        val clock = RouterAuthenticatedClock()
        assertThrows(IllegalArgumentException::class.java) { clock.observe(100, 0, 5001) }
        assertThrows(IllegalArgumentException::class.java) { clock.observe(-1, 0, 0) }
        clock.observe(9007199254740991L, 10000, 10000)
        assertEquals(null, clock.estimate(10001))
    }
}
