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
    suspend fun recordSnapshot() {
        anomalyRepository.recordAppTrafficWindows(sampleWindows())
    }

    suspend fun sampleWindows(): List<AppTrafficWindow> = sampler.sampleWindows()

    fun hasUsageAccess(): Boolean = sampler.hasUsageAccess()
}
