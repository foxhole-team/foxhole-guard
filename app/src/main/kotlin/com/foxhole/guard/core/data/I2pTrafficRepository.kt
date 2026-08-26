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

class I2pTrafficRepository(
    daoProvider: () -> I2pTrafficDao,
    private val routerStatusProvider: suspend () -> I2pRouterStatus? = { readI2pdRouterStatus() },
    private val nowProvider: () -> Long = System::currentTimeMillis,
    // Suspends until the SQLCipher key is installed, like every other repository over this database.
    private val awaitDatabaseReady: suspend () -> Unit = {},
) {
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED, daoProvider)

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

    suspend fun resetSampleCursors() {
        sampleMutex.withLock {
            cursorsPrimed = false
            lastNetworkTotalBytes = 0L
            lastTransitTotalBytes = 0L
        }
    }

    suspend fun sample() {
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

            transit?.let { lastTransitTotalBytes = it }
            cursorsPrimed = true
            if (ownDelta <= 0L && transitDelta <= 0L) {
                return@withLock
            }
            awaitDatabaseReady()
            persist(nowMs = nowMs, ownDelta = ownDelta, transitDelta = transitDelta)
        }
    }

    suspend fun clear() {
        sampleMutex.withLock {
            awaitDatabaseReady()
            dao.deleteAllBuckets()
            dao.deleteAllTotals()

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

    private suspend fun pruneIfDue(nowMs: Long) {
        if (nowMs - lastPruneAtMs < PRUNE_INTERVAL_MS) {
            return
        }
        lastPruneAtMs = nowMs
        dao.deleteBucketsBefore(i2pTrafficRetentionCutoffMs(nowMs))
        dao.trimBucketsTo(I2P_TRAFFIC_MONTH_BUCKETS)
    }

    private companion object {
        const val PRUNE_INTERVAL_MS = 60L * 60L * 1000L
    }
}

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
