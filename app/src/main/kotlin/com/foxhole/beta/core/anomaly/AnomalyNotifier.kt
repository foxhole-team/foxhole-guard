package com.foxhole.beta.core.anomaly

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity

class AnomalyNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notify(event: AnomalyEvent) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_vpn)
                .setContentTitle(
                    appContext.getString(
                        if (event.severity == AnomalySeverity.HIGH) {
                            R.string.anomaly_notification_high_title
                        } else {
                            R.string.anomaly_notification_title
                        },
                    ),
                )
                .setContentText(event.reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.reason))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + event.type.ordinal, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.anomaly_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.anomaly_notification_channel_description)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "foxhole_anomaly"
        private const val NOTIFICATION_ID_BASE = 8400
    }
}
