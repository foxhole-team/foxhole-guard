package com.foxhole.guard.core.sentinel.anomaly

import android.content.Context
import com.foxhole.core.model.AnomalyEvent

/**
 * Notification boundary for FoxHole Sentinel detections.
 *
 * Keeping this class separate from persistence means a detector result is marked as shown only
 * after Android has accepted the notification. Permission denial and NotificationManager errors
 * return false and leave the database event eligible for a later delivery attempt.
 */
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
