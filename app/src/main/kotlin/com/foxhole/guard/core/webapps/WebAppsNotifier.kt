package com.foxhole.guard.core.webapps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale

/**
 * The watchdog's system notifications: one stable id per app, so they overwrite rather than spam,
 * and a tap opens that app's frame through an intent extra. Modelled on AnomalyNotifier, plus the
 * contentIntent anomalies do not have.
 */
class WebAppsNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notify(app: WebAppEntity, count: Int) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val notificationId = (NOTIFICATION_ID_BASE + (app.id % NOTIFICATION_ID_RANGE)).toInt()
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, CliMainActivity::class.java)
                    .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, app.id)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val body = appContext.getString(R.string.cli_webapps_notification_text, count)
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_app_guard)
                .setContentTitle(app.name)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        }
    }

    private fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.cli_webapps_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "foxhole_webapps"
        private const val NOTIFICATION_ID_BASE = 8600
        private const val NOTIFICATION_ID_RANGE = 200L
    }
}
