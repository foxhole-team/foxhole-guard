package com.foxhole.guard.core.sentinel.anomaly

import android.content.Context
import com.foxhole.core.model.AnomalyEvent

class SentinelDetectionNotifier(
    context: Context,
) {
    private val anomalyNotifier = AnomalyNotifier(context)

    fun notify(event: AnomalyEvent): Boolean = anomalyNotifier.notify(event)

    fun cancelAll() = anomalyNotifier.cancelAll()

    internal fun ensureChannel() = anomalyNotifier.ensureChannel()

    companion object {
        const val CHANNEL_ID = AnomalyNotifier.CHANNEL_ID
    }
}
