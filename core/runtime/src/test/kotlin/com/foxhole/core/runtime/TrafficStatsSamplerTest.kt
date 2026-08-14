package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficStatsSamplerTest {
    @Test
    fun `calculates rates and totals from sampled bytes`() {
        val source = FakeTrafficByteSource(rxValues = listOf(1_000L, 3_000L), txValues = listOf(2_000L, 5_000L))
        var now = 10_000L
        val sampler = TrafficStatsSampler(uid = 42, byteSource = source) { now }

        sampler.start()
        now += 1_000L
        val snapshot = sampler.sample()

        assertTrue(snapshot.available)
        assertEquals(2_000L, snapshot.rxBytesPerSec)
        assertEquals(3_000L, snapshot.txBytesPerSec)
        assertEquals(2_000L, snapshot.rxTotalBytes)
        assertEquals(3_000L, snapshot.txTotalBytes)
    }

    @Test
    fun `returns unavailable snapshot when traffic stats are unsupported`() {
        val source = FakeTrafficByteSource(rxValues = listOf(-1L), txValues = listOf(-1L))
        val sampler = TrafficStatsSampler(uid = 42, byteSource = source) { 10_000L }

        sampler.start()
        val snapshot = sampler.sample()

        assertFalse(snapshot.available)
        assertEquals(0L, snapshot.rxBytesPerSec)
        assertEquals(0L, snapshot.txBytesPerSec)
    }

    @Test
    fun `reset baseline sample keeps totals but starts rates from zero`() {
        val source = FakeTrafficByteSource(
            rxValues = listOf(1_000L, 1_600L, 2_200L),
            txValues = listOf(2_000L, 2_400L, 3_200L)
        )
        var now = 10_000L
        val sampler = TrafficStatsSampler(uid = 42, byteSource = source) { now }

        sampler.start()
        now += 5_000L
        val warmStart = sampler.sample(resetRateBaseline = true)
        now += 1_000L
        val followUp = sampler.sample()

        assertTrue(warmStart.available)
        assertEquals(0L, warmStart.rxBytesPerSec)
        assertEquals(0L, warmStart.txBytesPerSec)
        assertEquals(600L, warmStart.rxTotalBytes)
        assertEquals(400L, warmStart.txTotalBytes)
        assertEquals(600L, followUp.rxBytesPerSec)
        assertEquals(800L, followUp.txBytesPerSec)
        assertEquals(1_200L, followUp.rxTotalBytes)
        assertEquals(1_200L, followUp.txTotalBytes)
    }
}

private class FakeTrafficByteSource(
    rxValues: List<Long>,
    txValues: List<Long>,
) : TrafficByteSource {
    private val rxIterator = rxValues.iterator()
    private val txIterator = txValues.iterator()
    private var lastRx = 0L
    private var lastTx = 0L

    override fun uidRxBytes(uid: Int): Long {
        if (rxIterator.hasNext()) {
            lastRx = rxIterator.next()
        }
        return lastRx
    }

    override fun uidTxBytes(uid: Int): Long {
        if (txIterator.hasNext()) {
            lastTx = txIterator.next()
        }
        return lastTx
    }
}
