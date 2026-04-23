package com.foxhole.beta.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foxhole.beta.R

internal object ProfileRefreshResultNotifier {
    private const val CHANNEL_ID = "foxhole-profile-refresh-results"
    private const val CHANNEL_NAME_RES = R.string.profile_refresh_notification_channel_name
    private const val CHANNEL_DESCRIPTION_RES = R.string.profile_refresh_notification_channel_description
    private const val NOTIFICATION_GROUP_KEY = "foxhole-profile-refresh"
    private const val NOTIFICATION_ID_BASE = 2_100

    fun showSuccess(
        context: Context,
        profileId: Long,
        profileName: String? = null,
        reconnecting: Boolean = false,
    ) {
        show(
            context = context,
            profileId = profileId,
            title =
                if (reconnecting) {
                    context.getString(R.string.profile_refreshed_reconnecting)
                } else {
                    context.getString(R.string.profile_refreshed)
                },
            body = profileName,
            isError = false,
        )
    }

    fun showFailure(
        context: Context,
        profileId: Long,
        profileName: String? = null,
        details: String? = null,
    ) {
        show(
            context = context,
            profileId = profileId,
            title = context.getString(R.string.profile_refresh_failed),
            body = details?.takeIf(String::isNotBlank) ?: profileName,
            isError = true,
        )
    }

    private fun show(
        context: Context,
        profileId: Long,
        title: String,
        body: String?,
        isError: Boolean,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(
                    if (isError) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    },
                )
                .setContentIntent(context.launchPendingIntent())
        body?.takeIf(String::isNotBlank)?.let { message ->
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }
        NotificationManagerCompat.from(context).notify(notificationId(profileId), builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(CHANNEL_NAME_RES),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(CHANNEL_DESCRIPTION_RES)
            },
        )
    }

    private fun notificationId(profileId: Long): Int = NOTIFICATION_ID_BASE + (profileId % 1_000L).toInt()

    private fun Context.launchPendingIntent(): PendingIntent? {
        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
