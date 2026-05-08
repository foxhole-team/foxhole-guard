package com.foxhole.beta.core.settings

import android.content.Context
import com.foxhole.beta.core.anomaly.AnomalyRepository
import com.foxhole.beta.core.anomaly.AppTrafficSampler
import com.foxhole.beta.core.model.AppTrafficWindow

class AppTrafficStatsRecorder(
    private val anomalyRepository: AnomalyRepository,
    context: Context,
    private val sampler: AppTrafficSampler = AppTrafficSampler(context),
) {
    suspend fun recordSnapshot(minDurationMs: Long = AppTrafficSampler.DEFAULT_SAMPLE_WINDOW_MS) {
        anomalyRepository.recordAppTrafficWindows(sampleWindows(minDurationMs))
    }

    suspend fun sampleWindows(minDurationMs: Long = AppTrafficSampler.DEFAULT_SAMPLE_WINDOW_MS): List<AppTrafficWindow> =
        sampler.sampleWindows(minDurationMs)

    fun hasUsageAccess(): Boolean = sampler.hasUsageAccess()
}
