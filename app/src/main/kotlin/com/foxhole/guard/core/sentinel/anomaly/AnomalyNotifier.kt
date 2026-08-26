package com.foxhole.guard.core.sentinel.anomaly

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
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.guard.R
import com.foxhole.guard.withStoredAppLocale

class AnomalyNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notify(event: AnomalyEvent): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            ensureChannel()
            val title = appContext.getString(event.notificationTitleRes())
            val body = event.userFacingMessage(appContext)
            val notification =
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_anomaly)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + event.type.ordinal, notification)
            true
        }.getOrDefault(false)
    }

    fun cancelAll() {
        val manager = NotificationManagerCompat.from(appContext)
        AnomalyType.entries.forEach { type -> manager.cancel(NOTIFICATION_ID_BASE + type.ordinal) }
    }

    private fun AnomalyEvent.notificationTitleRes(): Int =
        when {
            severity == AnomalySeverity.HIGH || score >= 85 -> R.string.anomaly_notification_red_flag_title
            score >= 75 -> R.string.anomaly_notification_orange_flag_title
            else -> R.string.anomaly_notification_yellow_flag_title
        }

    internal fun ensureChannel() {
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
