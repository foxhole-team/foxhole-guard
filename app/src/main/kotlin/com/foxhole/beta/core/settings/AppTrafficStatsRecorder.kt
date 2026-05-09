package com.foxhole.beta.core.settings

import android.content.Context
import com.foxhole.beta.core.anomaly.AnomalyRepository
import com.foxhole.beta.core.anomaly.AppTrafficSampler
import com.foxhole.beta.core.model.AppTrafficWindow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppTrafficStatsRecorder(
    private val anomalyRepository: AnomalyRepository,
    context: Context,
    private val sampler: AppTrafficSampler = AppTrafficSampler(context),
) {
    private val sampleMutex = Mutex()
    private var cachedSampledAtMs: Long = 0L
    private var cachedWindows: List<AppTrafficWindow> = emptyList()

    suspend fun recordSnapshot(minDurationMs: Long = AppTrafficSampler.DEFAULT_SAMPLE_WINDOW_MS) {
        anomalyRepository.recordAppTrafficWindows(sampleWindows(minDurationMs))
    }

    suspend fun sampleWindows(
        minDurationMs: Long = AppTrafficSampler.DEFAULT_SAMPLE_WINDOW_MS,
        maxCacheAgeMs: Long = 0L,
    ): List<AppTrafficWindow> =
        sampleMutex.withLock {
            val now = System.currentTimeMillis()
            if (maxCacheAgeMs > 0L && cachedSampledAtMs > 0L && now - cachedSampledAtMs <= maxCacheAgeMs) {
                return@withLock cachedWindows
            }
            sampler.sampleWindows(minDurationMs).also { windows ->
                cachedWindows = windows
                cachedSampledAtMs = System.currentTimeMillis()
            }
        }

    fun hasUsageAccess(): Boolean = sampler.hasUsageAccess()
}
