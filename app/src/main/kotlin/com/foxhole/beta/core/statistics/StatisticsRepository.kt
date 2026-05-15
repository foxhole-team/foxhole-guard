package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.data.AnomalyBucketEntity
import com.foxhole.beta.core.data.AnomalyDao
import com.foxhole.beta.core.data.AppTrafficBucketEntity
import com.foxhole.beta.core.data.ProtocolMetricEventEntity
import com.foxhole.beta.core.data.TrafficBucketEntity
import kotlinx.coroutines.flow.Flow

class StatisticsRepository(
    private val dao: AnomalyDao,
) {
    fun observeTrafficBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
        profileId: String? = null,
        protocol: String? = null,
        networkType: String? = null,
    ): Flow<List<TrafficBucketEntity>> =
        dao.observeTrafficBuckets(
            startMs = startMs,
            endMs = endMs,
            bucketMs = bucketMs,
            profileId = profileId,
            protocol = protocol,
            networkType = networkType,
        )

    fun observeAppTrafficBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
        packageName: String? = null,
        networkType: String? = null,
    ): Flow<List<AppTrafficBucketEntity>> =
        dao.observeAppTrafficBuckets(
            startMs = startMs,
            endMs = endMs,
            bucketMs = bucketMs,
            packageName = packageName,
            networkType = networkType,
        )

    fun observeAnomalyBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): Flow<List<AnomalyBucketEntity>> =
        dao.observeAnomalyBuckets(
            startMs = startMs,
            endMs = endMs,
            bucketMs = bucketMs,
        )

    fun observeProtocolMetricEvents(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ProtocolMetricEventEntity>> =
        dao.observeProtocolMetricEvents(
            startMs = startMs,
            endMs = endMs,
        )
}
