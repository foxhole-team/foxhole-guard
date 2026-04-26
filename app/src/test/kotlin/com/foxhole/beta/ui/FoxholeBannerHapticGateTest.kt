package com.foxhole.beta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeBannerHapticGateTest {
    @Test
    fun `success and error haptics share cooldown`() {
        var now = 1_000L
        val gate = FoxholeBannerHapticGate(nowElapsedMs = { now })

        assertTrue(gate.consume(FoxholeBannerTone.SUCCESS))
        now += 400L
        assertFalse(gate.consume(FoxholeBannerTone.ERROR))
        now += 900L
        assertTrue(gate.consume(FoxholeBannerTone.ERROR))
    }

    @Test
    fun `info banners never vibrate`() {
        val gate = FoxholeBannerHapticGate(nowElapsedMs = { 1_000L })

        assertFalse(gate.consume(FoxholeBannerTone.INFO))
    }
}
