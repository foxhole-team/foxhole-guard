package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.data.AnomalyDao
import com.foxhole.beta.core.data.AppBaselineEntity
import com.foxhole.beta.core.data.TrafficBaselineEntity
import com.foxhole.beta.core.data.anomalyHourBucket
import com.foxhole.beta.core.model.AppBaseline
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficBaseline
import com.foxhole.beta.core.model.TrafficWindow
import kotlin.math.abs

class BaselineStore(
    private val dao: AnomalyDao,
) {
    suspend fun updateTrafficBaseline(
        window: TrafficWindow,
        historyBeforeCurrent: List<TrafficWindow>,
    ) {
        val metric = METRIC_TOTAL_BYTES_PER_MIN
        val key =
            trafficBaselineKey(
                profileId = window.profileId,
                protocol = window.protocol,
                networkType = window.networkType,
                hourBucket = anomalyHourBucket(window.startedAtMs),
                metric = metric,
            )
        val currentValue = window.totalBytes.perMinute(window.durationSec)
        val historyValues = historyBeforeCurrent.map { it.totalBytes.perMinute(it.durationSec) }
        val previous = dao.getTrafficBaseline(key)?.toDomain()
        val updated =
            updatedTrafficBaseline(
                previous = previous,
                key = key,
                profileId = window.profileId,
                protocol = window.protocol,
                networkType = window.networkType,
                hourBucket = anomalyHourBucket(window.startedAtMs),
                metric = metric,
                currentValue = currentValue,
                historyValues = historyValues,
                updatedAt = window.startedAtMs + window.durationSec * 1000L,
            )
        dao.upsertTrafficBaseline(TrafficBaselineEntity.from(updated))
    }

    suspend fun updateAppBaseline(
        window: AppTrafficWindow,
        profileId: String?,
        protocol: String?,
        historyBeforeCurrent: List<AppTrafficWindow>,
    ) {
        val metric = METRIC_APP_TX_BYTES_PER_MIN
        val hourBucket = anomalyHourBucket(window.startedAtMs)
        val key =
            appBaselineKey(
                packageName = window.packageName,
                profileId = profileId,
                protocol = protocol,
                networkType = window.networkType,
                hourBucket = hourBucket,
                metric = metric,
            )
        val currentValue = window.txBytes.perMinute(window.durationSec)
        val historyValues = historyBeforeCurrent.map { it.txBytes.perMinute(it.durationSec) }
        val previous = dao.getAppBaseline(key)?.toDomain()
        val updated =
            updatedAppBaseline(
                previous = previous,
                key = key,
                packageName = window.packageName,
                profileId = profileId,
                protocol = protocol,
                networkType = window.networkType,
                hourBucket = hourBucket,
                metric = metric,
                currentValue = currentValue,
                historyValues = historyValues,
                updatedAt = window.startedAtMs + window.durationSec * 1000L,
            )
        dao.upsertAppBaseline(AppBaselineEntity.from(updated))
    }

    private fun updatedTrafficBaseline(
        previous: TrafficBaseline?,
        key: String,
        profileId: String?,
        protocol: String?,
        networkType: NetworkType,
        hourBucket: Int,
        metric: String,
        currentValue: Double,
        historyValues: List<Double>,
        updatedAt: Long,
    ): TrafficBaseline {
        val values = historyValues + currentValue
        val median = RobustStats.median(values)
        val mad = RobustStats.mad(values, median)
        val ewma = nextEwma(previous?.ewma, currentValue)
        return TrafficBaseline(
            key = key,
            profileId = profileId,
            protocol = protocol,
            networkType = networkType,
            hourBucket = hourBucket,
            metric = metric,
            median = median,
            mad = mad,
            ewma = ewma,
            ewmad = nextEwma(previous?.ewmad, abs(currentValue - ewma)).coerceAtLeast(1.0),
            sampleCount = (previous?.sampleCount ?: 0) + 1,
            lastUpdatedAt = updatedAt,
        )
    }

    private fun updatedAppBaseline(
        previous: AppBaseline?,
        key: String,
        packageName: String,
        profileId: String?,
        protocol: String?,
        networkType: NetworkType,
        hourBucket: Int,
        metric: String,
        currentValue: Double,
        historyValues: List<Double>,
        updatedAt: Long,
    ): AppBaseline {
        val values = historyValues + currentValue
        val median = RobustStats.median(values)
        val mad = RobustStats.mad(values, median)
        val ewma = nextEwma(previous?.ewma, currentValue)
        return AppBaseline(
            key = key,
            packageName = packageName,
            profileId = profileId,
            protocol = protocol,
            networkType = networkType,
            hourBucket = hourBucket,
            metric = metric,
            median = median,
            mad = mad,
            ewma = ewma,
            ewmad = nextEwma(previous?.ewmad, abs(currentValue - ewma)).coerceAtLeast(1.0),
            sampleCount = (previous?.sampleCount ?: 0) + 1,
            lastUpdatedAt = updatedAt,
        )
    }

    private fun nextEwma(
        previous: Double?,
        current: Double,
    ): Double = previous?.let { old -> old * (1.0 - EWMA_ALPHA) + current * EWMA_ALPHA } ?: current

    companion object {
        const val METRIC_TOTAL_BYTES_PER_MIN = "total_bytes_per_min"
        const val METRIC_APP_TX_BYTES_PER_MIN = "app_tx_bytes_per_min"

        fun trafficBaselineKey(
            profileId: String?,
            protocol: String?,
            networkType: NetworkType,
            hourBucket: Int,
            metric: String,
        ): String =
            listOf("traffic", profileId.orEmpty(), protocol.orEmpty(), networkType.name, hourBucket.toString(), metric)
                .joinToString(separator = "|")

        fun appBaselineKey(
            packageName: String,
            profileId: String?,
            protocol: String?,
            networkType: NetworkType,
            hourBucket: Int,
            metric: String,
        ): String =
            listOf("app", packageName, profileId.orEmpty(), protocol.orEmpty(), networkType.name, hourBucket.toString(), metric)
                .joinToString(separator = "|")
    }
}

private fun Long.perMinute(durationSec: Int): Double =
    toDouble() / (durationSec.coerceAtLeast(1).toDouble() / 60.0)

private const val EWMA_ALPHA = 0.18
