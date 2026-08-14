package com.foxhole.guard.ui

import com.foxhole.core.model.ProfileComparisonSideUiItem
import com.foxhole.core.model.ProfileTrafficUiItem
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolQuality
import com.foxhole.core.model.ProtocolStatisticsUiItem
import com.foxhole.core.model.SmartProfileProtocolMemory

internal data class ProtocolAccumulator(
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
    var lastUsedAt: Long? = null,
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { it > 0L }?.let(latencies::add)
        lastUsedAt = maxOfNotNull(
            lastUsedAt,
            memory.lastSuccessAt,
            memory.lastFailureAt,
            memory.lastValidatedAt,
            memory.lastTrafficAt,
        )
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
        lastUsedAt = maxOfNotNull(lastUsedAt, item.updatedAt.takeIf { it > 0L })
    }

    fun toProtocolItem(protocol: ProtocolHint): ProtocolStatisticsUiItem = ProtocolStatisticsUiItem(
        protocol = protocol,
        successCount = successCount,
        failureCount = failureCount,
        rxBytes = rxBytes,
        txBytes = txBytes,
        avgLatencyMs = latencies.averageOrNull(),
        lastUsedAt = lastUsedAt,
        quality = if (successCount + failureCount == 0 && rxBytes + txBytes > 0L) {
            ProtocolQuality.TRAFFIC_ONLY
        } else {
            ProtocolQuality.MEASURED
        },
    )
}

internal data class ComparisonAccumulator(
    val profileId: Long,
    val profileName: String,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { it > 0L }?.let(latencies::add)
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
    }

    fun toSide(): ProfileComparisonSideUiItem = ProfileComparisonSideUiItem(
        profileId = profileId,
        profileName = profileName,
        successCount = successCount,
        failureCount = failureCount,
        rxBytes = rxBytes,
        txBytes = txBytes,
        avgLatencyMs = latencies.averageOrNull(),
    )
}

internal fun List<Long>.averageOrNull(): Long? = if (isEmpty()) null else average().toLong()
