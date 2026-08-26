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
import com.foxhole.guard.WatchdogNames
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale
import kotlin.math.roundToInt

class WebAppsNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    internal fun notify(
        app: WebAppEntity,
        count: Int,
        content: WebAppNotificationContent? = null,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val notificationId = notificationIdFor(app.id)
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, CliMainActivity::class.java)
                    .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, app.id)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val fallbackBody = appContext.getString(R.string.cli_webapps_notification_text, count)
        val body = content?.body ?: content?.title ?: fallbackBody
        val largeIcon =
            decodeWebAppIcon(
                filesDir = appContext.filesDir,
                relativePath = app.iconPath,
                targetSizePx = (LARGE_ICON_DP * appContext.resources.displayMetrics.density).roundToInt(),
            )
                ?.circularCrop((LARGE_ICON_DP * appContext.resources.displayMetrics.density).roundToInt())
        val builder =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_app_guard)
                .setContentTitle(app.name)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setNumber(count)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
        largeIcon?.let(builder::setLargeIcon)
        val notification = builder.build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        }
    }

    fun deliveryBlocked(): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return true
        }
        val channel = notificationManager?.getNotificationChannel(CHANNEL_ID)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Removes the app's notification: a deleted or wiped app must not keep ringing in the shade. */
    fun cancel(appId: Long) {
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(notificationIdFor(appId))
        }
    }

    private fun notificationIdFor(appId: Long): Int =
        (NOTIFICATION_ID_BASE + (appId % NOTIFICATION_ID_RANGE)).toInt()

    internal fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                WatchdogNames.WEB,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.cli_webapps_channel_name)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = WatchdogNames.WEB_ID
        private const val NOTIFICATION_ID_BASE = 8600
        private const val NOTIFICATION_ID_RANGE = 200L
        private const val LARGE_ICON_DP = 48
    }
}
