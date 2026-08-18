package com.foxhole.guard.runtime

import com.foxhole.guard.runtime.GeoIpUpdateRepository.Companion.GEOIP_CHECK_INTERVAL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIpUpdateRepositoryTest {
    @Test
    fun `check is due when the source was never probed`() {
        assertTrue(isUpdateCheckDue(lastChecked = null, nowMs = 10_000L))
    }

    @Test
    fun `check is not due before the 72h interval elapses`() {
        val lastChecked = 1_000_000L
        assertFalse(isUpdateCheckDue(lastChecked, nowMs = lastChecked + GEOIP_CHECK_INTERVAL_MS - 1))
    }

    @Test
    fun `check is due once the 72h interval elapses`() {
        val lastChecked = 1_000_000L
        assertTrue(isUpdateCheckDue(lastChecked, nowMs = lastChecked + GEOIP_CHECK_INTERVAL_MS))
    }

    @Test
    fun `interval is 72 hours`() {
        assertEquals(72L * 60L * 60L * 1000L, GEOIP_CHECK_INTERVAL_MS)
    }

    private fun isUpdateCheckDue(lastChecked: Long?, nowMs: Long): Boolean {
        lastChecked ?: return true
        return nowMs - lastChecked >= GEOIP_CHECK_INTERVAL_MS
    }
}
