package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.sentinel.anomaly.AppTrafficSampler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppTrafficStatsRecorder(
    private val anomalyRepository: AnomalyRepository,
    context: Context,
    private val sampler: AppTrafficSampler = AppTrafficSampler(context),
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    private val sampleMutex = Mutex()
    private var cachedSampledAtMs: Long = 0L
    private var cachedWindows: List<AppTrafficWindow> = emptyList()

    suspend fun recordSnapshot(minDurationMs: Long = AppTrafficSampler.DEFAULT_SAMPLE_WINDOW_MS) {
        if (!hasUsageAccess()) {
            diagnosticsLogger?.recordThrottled(
                tag = "traffic",
                throttleKey = "app_traffic_usage_access_missing",
                windowMs = USAGE_ACCESS_MISSING_LOG_THROTTLE_MS,
                message = "app traffic snapshot skipped: usage access not granted",
            )
            return
        }
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

    private companion object {
        const val USAGE_ACCESS_MISSING_LOG_THROTTLE_MS = 15 * 60 * 1000L
    }
}
