package com.foxhole.guard.core.sentinel.anomaly

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale

internal interface AnomalyNotificationDelivery {
    fun isAppEnabled(): Boolean
    fun ensureChannel()
    fun isChannelEnabled(): Boolean
    fun submit(event: AnomalyEvent, notificationId: Int)
    fun cancel(notificationId: Int)
}

internal class AndroidAnomalyNotificationDelivery(context: Context) : AnomalyNotificationDelivery {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    override fun isAppEnabled(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    override fun isChannelEnabled(): Boolean =
        notificationManager?.getNotificationChannel(AnomalyNotifier.CHANNEL_ID)?.importance?.let {
            it != NotificationManager.IMPORTANCE_NONE
        } == true

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun submit(event: AnomalyEvent, notificationId: Int) {
        val title = appContext.getString(event.notificationTitleRes())
        val body = event.userFacingMessage(appContext)
        val notification =
            NotificationCompat.Builder(appContext, AnomalyNotifier.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_anomaly)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(appPendingIntent(notificationId))
                .build()
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(appContext).cancel(notificationId)
    }

    override fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                AnomalyNotifier.CHANNEL_ID,
                appContext.getString(R.string.anomaly_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.anomaly_notification_channel_description)
            },
        )
    }

    private fun appPendingIntent(notificationId: Int): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            notificationId,
            Intent(appContext, CliMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun AnomalyEvent.notificationTitleRes(): Int =
        when {
            type == com.foxhole.core.model.AnomalyType.APP_STATIC_REFERENCE -> R.string.sentinel_static_notification_title
            severity == AnomalySeverity.HIGH || score >= 85 -> R.string.anomaly_notification_red_flag_title
            score >= 75 -> R.string.anomaly_notification_orange_flag_title
            else -> R.string.anomaly_notification_yellow_flag_title
        }
}
