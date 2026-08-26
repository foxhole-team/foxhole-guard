package com.foxhole.guard.runtime

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
import com.foxhole.guard.withStoredAppLocale

internal class NetworkRuleRecommendationNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notifyRecommendation(
        transportLabel: String,
        profileName: String,
        profileId: Long,
        protocolOptionId: String?,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val body = appContext.getString(R.string.network_rule_notification_body, profileName)
        val switchIntent = applySwitchPendingIntent(profileId, protocolOptionId)
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_network_rule)
                .setContentTitle(appContext.getString(R.string.network_rule_notification_title, transportLabel))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(switchIntent)
                .addAction(0, appContext.getString(R.string.network_rule_notification_switch), switchIntent)
                .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel() {
        runCatching { NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID) }
    }

    private fun applySwitchPendingIntent(
        profileId: Long,
        protocolOptionId: String?,
    ): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_SWITCH,

            Intent(appContext, com.foxhole.guard.ui.cli.CliMainActivity::class.java)
                .setAction(ACTION_APPLY_NETWORK_RULE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PROTOCOL_OPTION_ID, protocolOptionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.network_rule_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.network_rule_notification_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_APPLY_NETWORK_RULE = "com.foxhole.guard.action.APPLY_NETWORK_RULE"
        const val EXTRA_PROFILE_ID = "network_rule_profile_id"
        const val EXTRA_PROTOCOL_OPTION_ID = "network_rule_protocol_option_id"
        const val CHANNEL_ID = "foxhole_network_rules"
        private const val NOTIFICATION_ID = 9400
        private const val REQUEST_CODE_SWITCH = 210
    }
}
