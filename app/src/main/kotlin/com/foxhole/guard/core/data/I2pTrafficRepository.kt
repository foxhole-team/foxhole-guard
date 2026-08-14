package com.foxhole.guard.core.data

import com.foxhole.core.model.I2P_TRAFFIC_MONTH_BUCKETS
import com.foxhole.core.model.I2pTrafficHistory
import com.foxhole.core.model.I2pTrafficTotals
import com.foxhole.core.model.i2pCumulativeDelta
import com.foxhole.core.model.i2pTrafficBucketStart
import com.foxhole.core.model.i2pTrafficRetentionCutoffMs
import com.foxhole.core.runtime.I2pRouterStatus
import com.foxhole.core.runtime.readI2pdRouterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persisted I2P byte accounting: the store behind the statistics screen's I2P section.
 *
 * Both counters are read as CUMULATIVE totals and stored as deltas, which is what makes the writes
 * cheap. The recorder does not have to see every byte as it moves — it samples two running totals on
 * a slow ticker, so a sample costs two `+=` statements no matter how much traffic happened in
 * between, and nothing is lost when the sampler is late.
 *
 * Both values come from one i2pd webconsole snapshot. The network total is the router's cumulative
 * received + sent count, so bootstrap and tunnel maintenance move the statistic even before an
 * eepsite flow crosses FoxCore's optional I2P application lane. Transit is kept as a separate subset
 * for the relay readout; callers must not add it to the network total.
 *
 * Both restart from zero with their producer, so [i2pCumulativeDelta] treats a counter that went
 * backwards as a fresh start instead of a negative delta.
 */
class I2pTrafficRepository(
    daoProvider: () -> I2pTrafficDao,
    private val routerStatusProvider: suspend () -> I2pRouterStatus? = { readI2pdRouterStatus() },
    private val nowProvider: () -> Long = System::currentTimeMillis,
    // Suspends until the SQLCipher key is installed, like every other repository over this database.
    private val awaitDatabaseReady: suspend () -> Unit = {},
) {
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED, daoProvider)

    // Serializes the sample pipeline: a sample is read-cursor -> write -> advance-cursor, and two
    // interleaved samples would double-count the overlap.
    private val sampleMutex = Mutex()
    private var cursorsPrimed = false
    private var lastNetworkTotalBytes = 0L
    private var lastTransitTotalBytes = 0L
    private var lastPruneAtMs = 0L

    val history: Flow<I2pTrafficHistory> =
        flow {
            awaitDatabaseReady()
            val cutoff = i2pTrafficRetentionCutoffMs(nowProvider())
            emitAll(
                combine(
                    dao.observeBucketsSince(cutoff),
                    dao.observeTotals(I2P_TRAFFIC_TOTALS_ROW_ID),
                ) { buckets, totals ->
                    I2pTrafficHistory(
                        buckets = buckets.map(I2pTrafficBucketEntity::toDomain),
                        lifetime = totals?.toDomain() ?: I2pTrafficTotals(),
                    )
                },
            )
        }.flowOn(Dispatchers.IO)

    /**
     * Drops the cursors without recording anything, so the next sample only re-primes them.
     *
     * Called whenever the counters and this recorder can have drifted apart — a new session, or
     * statistics collection being off for a while. Without it, the first sample after the gap would
     * charge the whole gap to the hour it happened to land in.
     */
    suspend fun resetSampleCursors() {
        sampleMutex.withLock {
            cursorsPrimed = false
            lastNetworkTotalBytes = 0L
            lastTransitTotalBytes = 0L
        }
    }

    /**
     * Records everything the two counters moved since the previous sample.
     *
     * One webconsole read supplies both cumulative counters atomically enough for this hourly store.
     * A missing snapshot is skipped without disturbing either cursor: i2pd may still be running.
     */
    suspend fun sample() {
        // Outside the lock: this is a loopback HTTP read, and holding the lock across it would let
        // a slow console stall the teardown sample.
        val routerTraffic = routerStatusProvider()?.toTrafficTotals() ?: return
        sampleMutex.withLock {
            val nowMs = nowProvider()
            val network = routerTraffic.networkTotalBytes.coerceAtLeast(0L)
            val transit = routerTraffic.transitTotalBytes?.coerceAtLeast(0L)
            val primed = cursorsPrimed
            val ownDelta = if (primed) i2pCumulativeDelta(network, lastNetworkTotalBytes) else 0L
            val transitDelta =
                if (primed && transit != null) i2pCumulativeDelta(transit, lastTransitTotalBytes) else 0L
            lastNetworkTotalBytes = network
            // A console read that failed leaves the cursor alone: the router may still be running,
            // and zeroing it would replay its whole lifetime total on the next successful read.
            transit?.let { lastTransitTotalBytes = it }
            cursorsPrimed = true
            if (ownDelta <= 0L && transitDelta <= 0L) {
                return@withLock
            }
            awaitDatabaseReady()
            persist(nowMs = nowMs, ownDelta = ownDelta, transitDelta = transitDelta)
        }
    }

    /** Wipes every persisted I2P counter — buckets and the lifetime aggregate alike. */
    suspend fun clear() {
        sampleMutex.withLock {
            awaitDatabaseReady()
            dao.deleteAllBuckets()
            dao.deleteAllTotals()
            // A live session's counters keep climbing; re-priming stops the next sample from
            // pouring the traffic that was just erased straight back in.
            cursorsPrimed = false
            lastNetworkTotalBytes = 0L
            lastTransitTotalBytes = 0L
        }
    }

    private suspend fun persist(
        nowMs: Long,
        ownDelta: Long,
        transitDelta: Long,
    ) {
        val hourStartMs = i2pTrafficBucketStart(nowMs)
        dao.ensureBucket(hourStartMs)
        dao.addToBucket(hourStartMs = hourStartMs, ownDelta = ownDelta, transitDelta = transitDelta)
        dao.ensureTotals(I2P_TRAFFIC_TOTALS_ROW_ID)
        dao.addToTotals(id = I2P_TRAFFIC_TOTALS_ROW_ID, ownDelta = ownDelta, transitDelta = transitDelta)
        pruneIfDue(nowMs)
    }

    /**
     * Bounds the store. Buckets older than the longest period on screen can never be shown again, so
     * they go; the row cap behind the age cutoff covers a clock that jumped forward and left buckets
     * no cutoff can reach. The lifetime row has no time dimension and is never pruned.
     */
    private suspend fun pruneIfDue(nowMs: Long) {
        if (nowMs - lastPruneAtMs < PRUNE_INTERVAL_MS) {
            return
        }
        lastPruneAtMs = nowMs
        dao.deleteBucketsBefore(i2pTrafficRetentionCutoffMs(nowMs))
        dao.trimBucketsTo(I2P_TRAFFIC_MONTH_BUCKETS)
    }

    private companion object {
        // One pass an hour: the cutoff moves by whole buckets, so anything more often is wasted IO.
        const val PRUNE_INTERVAL_MS = 60L * 60L * 1000L
    }
}

/** One first-party i2pd snapshot used by the persisted network and relay counters. */
internal data class I2pRouterTrafficTotals(
    val networkTotalBytes: Long,
    val transitTotalBytes: Long?,
)

internal fun I2pRouterStatus.toTrafficTotals(): I2pRouterTrafficTotals? {
    val receivedBytes = rxTotalBytes ?: return null
    val sentBytes = txTotalBytes ?: return null
    return I2pRouterTrafficTotals(
        networkTotalBytes = saturatingNonNegativeSum(receivedBytes, sentBytes),
        transitTotalBytes = transitTotalBytes?.coerceAtLeast(0L),
    )
}

private fun saturatingNonNegativeSum(
    first: Long,
    second: Long,
): Long {
    val left = first.coerceAtLeast(0L)
    val right = second.coerceAtLeast(0L)
    return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
