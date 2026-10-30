package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficStatsSamplerMonotonicTest {
    @Test
    fun `wall clock rollback never spikes rates and sampled at stays wall time`() {
        val source = MutableByteSource()
        var wall = 10_000L
        var elapsed = 1_000L
        val sampler = TrafficStatsSampler(
            uid = 42,
            byteSource = source,
            elapsedNowProvider = { elapsed },
            nowProvider = { wall }
        )
        sampler.start()
        wall -= 5_000L
        elapsed += 1_000L
        source.rx = 200L
        source.tx = 300L

        val snapshot = sampler.sample()
        assertEquals(200L, snapshot.rxBytesPerSec)
        assertEquals(300L, snapshot.txBytesPerSec)
        assertEquals(5_000L, snapshot.sampledAt)
    }

    @Test
    fun `wall clock jump forward never hides traffic and reset reanchors elapsed sampling`() {
        val source = MutableByteSource()
        var wall = 10_000L
        var elapsed = 1_000L
        val sampler = TrafficStatsSampler(
            uid = 42,
            byteSource = source,
            elapsedNowProvider = { elapsed },
            nowProvider = { wall }
        )
        sampler.start()
        wall += 60_000L
        elapsed += 1_000L
        source.rx = 200L
        source.tx = 300L
        val first = sampler.sample()
        assertEquals(200L, first.rxBytesPerSec)
        assertEquals(300L, first.txBytesPerSec)
        assertEquals(wall, first.sampledAt)

        wall -= 30_000L
        elapsed += 1_000L
        source.rx = 400L
        source.tx = 500L
        assertEquals(0L, sampler.sample(resetRateBaseline = true).rxBytesPerSec)
        elapsed += 1_000L
        source.rx += 200L
        source.tx += 300L
        val afterReset = sampler.sample()
        assertEquals(200L, afterReset.rxBytesPerSec)
        assertEquals(300L, afterReset.txBytesPerSec)
        assertEquals(wall, afterReset.sampledAt)
    }

    private class MutableByteSource : TrafficByteSource {
        var rx = 0L
        var tx = 0L
        override fun uidRxBytes(uid: Int): Long = rx
        override fun uidTxBytes(uid: Int): Long = tx
    }
}
