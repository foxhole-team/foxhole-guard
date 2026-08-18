package com.foxhole.guard.core.data

import com.foxhole.core.model.I2P_TRAFFIC_BUCKET_MS
import com.foxhole.core.model.I2P_TRAFFIC_MONTH_BUCKETS
import com.foxhole.core.model.i2pTrafficBucketStart
import com.foxhole.core.model.i2pTrafficRetentionCutoffMs
import com.foxhole.core.runtime.I2pRouterStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pTrafficRepositoryTest {
    private val dao = FakeI2pTrafficDao()
    private var networkTotalBytes = 0L
    private var transitTotalBytes: Long? = 0L
    private var nowMs = NOW

    private val repository =
        I2pTrafficRepository(
            daoProvider = { dao },
            routerStatusProvider = {
                I2pRouterStatus(
                    rxTotalBytes = networkTotalBytes,
                    txTotalBytes = 0L,
                    transitTotalBytes = transitTotalBytes,
                )
            },
            nowProvider = { nowMs },
        )

    @Test
    fun `the first sample only primes the cursors so no gap is banked as one hour`() =
        runBlocking {
            networkTotalBytes = 5_000L
            transitTotalBytes = 9_000L
            repository.sample()

            assertTrue(dao.buckets.isEmpty())
            assertEquals(0L, dao.totalsOwn)
            assertEquals(0L, dao.totalsTransit)
        }

    @Test
    fun `later samples record deltas into the current hour and into the lifetime row`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 300L
            transitTotalBytes = 700L
            repository.sample()

            assertEquals(mapOf(i2pTrafficBucketStart(NOW) to (300L to 700L)), dao.buckets)
            assertEquals(300L, dao.totalsOwn)
            assertEquals(700L, dao.totalsTransit)
        }

    @Test
    fun `an immediate zero prime preserves the first timed traffic interval`() =
        runBlocking {
            repository.sample()
            nowMs += 5_000L
            networkTotalBytes = 1_234L
            repository.sample()

            assertEquals(1_234L, dao.totalsOwn)
            assertEquals(1_234L, dao.buckets.values.single().first)
        }

    @Test
    fun `a sample after the hour rolls over opens a new bucket instead of growing the old one`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 100L
            repository.sample()
            nowMs = NOW + I2P_TRAFFIC_BUCKET_MS
            networkTotalBytes = 250L
            repository.sample()

            assertEquals(
                mapOf(
                    i2pTrafficBucketStart(NOW) to (100L to 0L),
                    i2pTrafficBucketStart(NOW + I2P_TRAFFIC_BUCKET_MS) to (150L to 0L),
                ),
                dao.buckets,
            )
            assertEquals(250L, dao.totalsOwn)
        }

    @Test
    fun `a restarted counter contributes its current reading, never a negative delta`() =
        runBlocking {
            repository.sample()
            transitTotalBytes = 4_000L
            repository.sample()
            transitTotalBytes = 60L
            repository.sample()

            assertEquals(4_060L, dao.totalsTransit)
        }

    @Test
    fun `a console read that failed leaves the transit cursor alone`() =
        runBlocking {
            repository.sample()
            transitTotalBytes = 1_000L
            repository.sample()
            transitTotalBytes = null
            repository.sample()
            transitTotalBytes = 1_500L
            repository.sample()

            assertEquals(1_500L, dao.totalsTransit)
        }

    @Test
    fun `reset makes the next session start from its own zero instead of under-counting`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 6_000L
            repository.sample()
            repository.resetSampleCursors()

            networkTotalBytes = 0L
            repository.sample()
            networkTotalBytes = 7_000L
            repository.sample()

            assertEquals(13_000L, dao.totalsOwn)
        }

    @Test
    fun `clear wipes every bucket and the lifetime row`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 800L
            transitTotalBytes = 900L
            repository.sample()

            repository.clear()

            assertTrue(dao.buckets.isEmpty())
            assertEquals(0L, dao.totalsOwn)
            assertEquals(0L, dao.totalsTransit)
        }

    @Test
    fun `a clear during a live session does not let the running counters refill the store`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 800L
            repository.sample()
            repository.clear()

            networkTotalBytes = 950L
            repository.sample()
            assertTrue("the sample after a clear must only re-prime", dao.buckets.isEmpty())
            networkTotalBytes = 1_000L
            repository.sample()

            assertEquals(50L, dao.totalsOwn)
        }

    @Test
    fun `recording prunes the store to the hours the longest period can still show`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 10L
            repository.sample()

            assertEquals(listOf(i2pTrafficRetentionCutoffMs(NOW)), dao.deletedBefore)
            assertEquals(listOf(I2P_TRAFFIC_MONTH_BUCKETS), dao.trimmedTo)
        }

    @Test
    fun `pruning runs at most once an hour, not on every sample`() =
        runBlocking {
            repository.sample()
            networkTotalBytes = 10L
            repository.sample()
            networkTotalBytes = 20L
            repository.sample()
            assertEquals(1, dao.deletedBefore.size)

            nowMs = NOW + I2P_TRAFFIC_BUCKET_MS
            networkTotalBytes = 30L
            repository.sample()
            assertEquals(2, dao.deletedBefore.size)
        }

    @Test
    fun `router snapshot counts received plus sent while transit remains a separate subset`() {
        val totals =
            I2pRouterStatus(
                rxTotalBytes = 1_200L,
                txTotalBytes = 800L,
                transitTotalBytes = 300L,
            ).toTrafficTotals()

        assertEquals(I2pRouterTrafficTotals(networkTotalBytes = 2_000L, transitTotalBytes = 300L), totals)
    }

    @Test
    fun `partial router traffic snapshot is skipped instead of moving a cumulative cursor`() {
        assertEquals(null, I2pRouterStatus(rxTotalBytes = 1_200L).toTrafficTotals())
    }

    private companion object {
        const val NOW = 1_700_000_123_456L
    }
}

private class FakeI2pTrafficDao : I2pTrafficDao {
    val buckets = linkedMapOf<Long, Pair<Long, Long>>()
    var totalsOwn = 0L
    var totalsTransit = 0L
    private var totalsRowExists = false
    val deletedBefore = mutableListOf<Long>()
    val trimmedTo = mutableListOf<Int>()

    private val revisions = MutableStateFlow(0)

    override suspend fun ensureBucket(hourStartMs: Long) {
        buckets.getOrPut(hourStartMs) { 0L to 0L }
    }

    override suspend fun addToBucket(
        hourStartMs: Long,
        ownDelta: Long,
        transitDelta: Long,
    ) {
        val current = buckets[hourStartMs] ?: return
        buckets[hourStartMs] = (current.first + ownDelta) to (current.second + transitDelta)
        revisions.value++
    }

    override suspend fun ensureTotals(id: Int) {
        totalsRowExists = true
    }

    override suspend fun addToTotals(
        id: Int,
        ownDelta: Long,
        transitDelta: Long,
    ) {
        if (!totalsRowExists) {
            return
        }
        totalsOwn += ownDelta
        totalsTransit += transitDelta
        revisions.value++
    }

    override fun observeBucketsSince(cutoff: Long): Flow<List<I2pTrafficBucketEntity>> =
        revisions.map {
            buckets
                .filterKeys { hourStartMs -> hourStartMs >= cutoff }
                .map { (hourStartMs, totals) ->
                    I2pTrafficBucketEntity(
                        hourStartMs = hourStartMs,
                        ownBytes = totals.first,
                        transitBytes = totals.second,
                    )
                }
        }

    override fun observeTotals(id: Int): Flow<I2pTrafficTotalEntity?> =
        revisions.map {
            if (totalsRowExists) {
                I2pTrafficTotalEntity(id = id, ownBytes = totalsOwn, transitBytes = totalsTransit)
            } else {
                null
            }
        }

    override suspend fun deleteBucketsBefore(cutoff: Long) {
        deletedBefore += cutoff
        buckets.keys.retainAll { hourStartMs -> hourStartMs >= cutoff }
    }

    override suspend fun trimBucketsTo(keep: Int) {
        trimmedTo += keep
    }

    override suspend fun deleteAllBuckets() {
        buckets.clear()
    }

    override suspend fun deleteAllTotals() {
        totalsRowExists = false
        totalsOwn = 0L
        totalsTransit = 0L
    }
}
