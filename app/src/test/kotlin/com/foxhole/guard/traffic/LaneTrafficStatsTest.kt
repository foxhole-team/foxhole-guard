package com.foxhole.guard.traffic

import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.TorTrafficStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LaneTrafficStatsTest {
    @After
    fun resetStats() {
        TorTrafficStats.reset()
        I2pTrafficStats.reset()
    }

    @Test
    fun `tor lane keeps totals and returns rate to zero on idle sample`() {
        TorTrafficStats.record(rxDelta = 0L, txDelta = 0L, nowMs = 1_000L)
        TorTrafficStats.record(rxDelta = 2_000L, txDelta = 1_000L, nowMs = 2_000L)
        assertEquals(2_000L, TorTrafficStats.snapshot.value.rxBytesPerSec)
        assertEquals(1_000L, TorTrafficStats.snapshot.value.txBytesPerSec)

        TorTrafficStats.record(rxDelta = 0L, txDelta = 0L, nowMs = 3_000L)
        assertEquals(2_000L, TorTrafficStats.snapshot.value.rxTotalBytes)
        assertEquals(1_000L, TorTrafficStats.snapshot.value.txTotalBytes)
        assertEquals(0L, TorTrafficStats.snapshot.value.rxBytesPerSec)
        assertEquals(0L, TorTrafficStats.snapshot.value.txBytesPerSec)
    }

    @Test
    fun `i2p lane keeps totals and returns rate to zero on idle sample`() {
        I2pTrafficStats.record(rxDelta = 0L, txDelta = 0L, nowMs = 1_000L)
        I2pTrafficStats.record(rxDelta = 4_000L, txDelta = 3_000L, nowMs = 2_000L)
        assertEquals(4_000L, I2pTrafficStats.snapshot.value.rxBytesPerSec)
        assertEquals(3_000L, I2pTrafficStats.snapshot.value.txBytesPerSec)

        I2pTrafficStats.record(rxDelta = 0L, txDelta = 0L, nowMs = 3_000L)
        assertEquals(4_000L, I2pTrafficStats.snapshot.value.rxTotalBytes)
        assertEquals(3_000L, I2pTrafficStats.snapshot.value.txTotalBytes)
        assertEquals(0L, I2pTrafficStats.snapshot.value.rxBytesPerSec)
        assertEquals(0L, I2pTrafficStats.snapshot.value.txBytesPerSec)
    }
}
