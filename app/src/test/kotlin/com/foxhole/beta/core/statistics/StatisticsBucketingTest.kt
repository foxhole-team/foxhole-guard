package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsBucketingTest {
    @Test
    fun `traffic buckets are aligned to bucket start`() {
        val buckets =
            trafficBuckets(
                windows =
                    listOf(
                        trafficWindow(startedAtMs = 1_200L, rxBytes = 10L),
                        trafficWindow(startedAtMs = 1_900L, txBytes = 5L),
                    ),
                startMs = 1_000L,
                endMs = 3_000L,
                bucketSizeMs = 1_000L,
            )

        assertEquals(listOf(1_000L, 2_000L), buckets.map(TrafficBucket::bucketStartMs))
        assertEquals(10L, buckets[0].rxBytes)
        assertEquals(5L, buckets[0].txBytes)
    }

    @Test
    fun `traffic buckets fill empty buckets`() {
        val buckets =
            trafficBuckets(
                windows = listOf(trafficWindow(startedAtMs = 2_100L, rxBytes = 7L)),
                startMs = 1_000L,
                endMs = 4_000L,
                bucketSizeMs = 1_000L,
            )

        assertEquals(listOf(1_000L, 2_000L, 3_000L), buckets.map(TrafficBucket::bucketStartMs))
        assertEquals(0L, buckets[0].totalBytes)
        assertEquals(7L, buckets[1].totalBytes)
        assertEquals(0L, buckets[2].totalBytes)
    }

    private fun trafficWindow(
        startedAtMs: Long,
        rxBytes: Long = 0L,
        txBytes: Long = 0L,
    ): TrafficWindow =
        TrafficWindow(
            startedAtMs = startedAtMs,
            durationSec = 60,
            networkType = NetworkType.UNKNOWN,
            vpnMode = VpnMode.NORMAL,
            profileId = null,
            protocol = null,
            rxBytes = rxBytes,
            txBytes = txBytes,
            blockedDns = 0,
            allowedDns = 0,
            reconnects = 0,
            latencyMs = null,
            destinationCountries = emptyMap(),
        )
}
