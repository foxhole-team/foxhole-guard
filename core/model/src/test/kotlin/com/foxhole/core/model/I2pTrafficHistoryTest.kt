package com.foxhole.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pTrafficHistoryTest {
    @Test
    fun `a bucket start is the wall-clock hour the sample fell into`() {
        assertEquals(NOW_HOUR, i2pTrafficBucketStart(NOW_HOUR))
        assertEquals(NOW_HOUR, i2pTrafficBucketStart(NOW_HOUR + 1L))
        assertEquals(NOW_HOUR, i2pTrafficBucketStart(NOW_HOUR + I2P_TRAFFIC_BUCKET_MS - 1L))
        assertEquals(NOW_HOUR + I2P_TRAFFIC_BUCKET_MS, i2pTrafficBucketStart(NOW_HOUR + I2P_TRAFFIC_BUCKET_MS))
    }

    @Test
    fun `each period covers exactly its own number of whole hours`() {
        val buckets = (0 until I2P_TRAFFIC_MONTH_BUCKETS).map { index ->
            bucket(NOW_HOUR - index * I2P_TRAFFIC_BUCKET_MS, ownBytes = 1L)
        }
        val periods = i2pTrafficPeriods(buckets, lifetime = I2pTrafficTotals(), nowMs = NOW)

        assertEquals(I2P_TRAFFIC_DAY_BUCKETS.toLong(), periods.day.ownBytes)
        assertEquals(I2P_TRAFFIC_WEEK_BUCKETS.toLong(), periods.week.ownBytes)
        assertEquals(I2P_TRAFFIC_MONTH_BUCKETS.toLong(), periods.month.ownBytes)
    }

    @Test
    fun `the oldest hour of a period rolls off as soon as the clock crosses the hour`() {
        val oldest = NOW_HOUR - (I2P_TRAFFIC_DAY_BUCKETS - 1) * I2P_TRAFFIC_BUCKET_MS
        val buckets = listOf(bucket(oldest, ownBytes = 500L), bucket(NOW_HOUR, ownBytes = 7L))

        assertEquals(507L, i2pTrafficPeriods(buckets, I2pTrafficTotals(), NOW).day.ownBytes)
        assertEquals(
            507L,
            i2pTrafficPeriods(buckets, I2pTrafficTotals(), NOW_HOUR + I2P_TRAFFIC_BUCKET_MS - 1L).day.ownBytes,
        )

        assertEquals(
            7L,
            i2pTrafficPeriods(buckets, I2pTrafficTotals(), NOW_HOUR + I2P_TRAFFIC_BUCKET_MS).day.ownBytes,
        )
    }

    @Test
    fun `own and transit bytes are summed apart, never merged`() {
        val buckets =
            listOf(
                bucket(NOW_HOUR, ownBytes = 10L, transitBytes = 900L),
                bucket(NOW_HOUR - I2P_TRAFFIC_BUCKET_MS, ownBytes = 5L, transitBytes = 100L),
            )
        val periods = i2pTrafficPeriods(buckets, I2pTrafficTotals(), NOW)

        assertEquals(15L, periods.day.ownBytes)
        assertEquals(1_000L, periods.day.transitBytes)
    }

    @Test
    fun `all time comes from the lifetime aggregate and outlives pruned buckets`() {
        val periods =
            i2pTrafficPeriods(
                buckets = listOf(bucket(NOW_HOUR, ownBytes = 3L, transitBytes = 4L)),
                lifetime = I2pTrafficTotals(ownBytes = 9_000L, transitBytes = 8_000L),
                nowMs = NOW,
            )

        assertEquals(3L, periods.month.ownBytes)
        assertEquals(9_000L, periods.allTime.ownBytes)
        assertEquals(8_000L, periods.allTime.transitBytes)
        assertFalse(periods.isEmpty)
    }

    @Test
    fun `nothing recorded reads as empty rather than as four measured zeros`() {
        assertTrue(i2pTrafficPeriods(emptyList(), I2pTrafficTotals(), NOW).isEmpty)
    }

    @Test
    fun `retention keeps exactly the hours the longest period can still show`() {
        val cutoff = i2pTrafficRetentionCutoffMs(NOW)
        assertEquals(NOW_HOUR - (I2P_TRAFFIC_MONTH_BUCKETS - 1) * I2P_TRAFFIC_BUCKET_MS, cutoff)

        assertEquals(i2pTrafficPeriodCutoffMs(NOW, I2P_TRAFFIC_MONTH_BUCKETS), cutoff)
    }

    @Test
    fun `a counter that restarted is read as a fresh start, never as a negative delta`() {
        assertEquals(400L, i2pCumulativeDelta(current = 1_400L, previous = 1_000L))
        assertEquals(0L, i2pCumulativeDelta(current = 1_000L, previous = 1_000L))

        assertEquals(30L, i2pCumulativeDelta(current = 30L, previous = 9_999L))
    }

    private fun bucket(
        hourStartMs: Long,
        ownBytes: Long = 0L,
        transitBytes: Long = 0L,
    ): I2pTrafficBucket =
        I2pTrafficBucket(
            hourStartMs = hourStartMs,
            totals = I2pTrafficTotals(ownBytes = ownBytes, transitBytes = transitBytes),
        )

    private companion object {
        const val NOW = 1_700_000_123_456L
        val NOW_HOUR = i2pTrafficBucketStart(NOW)
    }
}
