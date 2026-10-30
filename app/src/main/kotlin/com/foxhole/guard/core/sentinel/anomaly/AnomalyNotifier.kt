package com.foxhole.guard.core.sentinel.anomaly

import android.content.Context
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalyType
import com.foxhole.guard.core.sentinel.sentinelAttempt

class AnomalyNotifier internal constructor(
    private val delivery: AnomalyNotificationDelivery,
) {
    constructor(context: Context) : this(AndroidAnomalyNotificationDelivery(context))

    fun notify(event: AnomalyEvent): Boolean =
        sentinelAttempt {
            if (!delivery.isAppEnabled()) return@sentinelAttempt false
            delivery.ensureChannel()
            if (!delivery.isChannelEnabled()) return@sentinelAttempt false
            delivery.submit(event, anomalyNotificationId(event.type))
            true
        }.getOrDefault(false)

    fun cancelAll() {
        AnomalyType.entries.forEach { type -> delivery.cancel(anomalyNotificationId(type)) }
    }

    internal fun ensureChannel() = delivery.ensureChannel()

    companion object {
        const val CHANNEL_ID = "foxhole_anomaly"
    }
}

internal fun anomalyNotificationId(type: AnomalyType): Int =
    when (type) {
        AnomalyType.APP_UPLOAD_SPIKE -> 8400
        AnomalyType.APP_BACKGROUND_TRAFFIC -> 8401
        AnomalyType.TOTAL_TRAFFIC_SPIKE -> 8402
        AnomalyType.NEW_DESTINATION_COUNTRY -> 8403
        AnomalyType.DNS_BLOCK_RATIO_SPIKE -> 8404
        AnomalyType.RECONNECT_STORM -> 8405
        AnomalyType.LATENCY_SHIFT -> 8406
        AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH -> 8407
        AnomalyType.DORMANT_APP_NETWORK_ACTIVITY -> 8408
        AnomalyType.KNOWN_THREAT_DESTINATION -> 8409
        AnomalyType.APP_STATIC_REFERENCE -> 8410
    }
