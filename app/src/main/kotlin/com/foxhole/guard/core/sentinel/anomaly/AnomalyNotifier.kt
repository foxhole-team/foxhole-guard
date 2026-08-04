package com.foxhole.guard.core.sentinel.anomaly

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
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

    fun notify(event: AnomalyEvent) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val title = appContext.getString(event.notificationTitleRes())
        val body = event.notificationBody()
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
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + event.type.ordinal, notification)
        }
    }

    private fun AnomalyEvent.notificationTitleRes(): Int =
        when {
            severity == AnomalySeverity.HIGH || score >= 85 -> R.string.anomaly_notification_red_flag_title
            score >= 75 -> R.string.anomaly_notification_orange_flag_title
            else -> R.string.anomaly_notification_yellow_flag_title
        }

    private fun AnomalyEvent.notificationBody(): String {
        val appLabel = packageName?.let(::labelForPackageName) ?: packageName
        val subject = appLabel ?: appContext.getString(R.string.statistics_anomaly_whole_tunnel)
        return when (type) {
            AnomalyType.APP_UPLOAD_SPIKE ->
                appContext.getString(
                    R.string.anomaly_reason_app_upload,
                    subject,
                    evidence["tx_per_min"].asBytesText(),
                    evidence["upload_ratio"].asPercentText(),
                )
            AnomalyType.APP_BACKGROUND_TRAFFIC ->
                appContext.getString(
                    R.string.anomaly_reason_background,
                    subject,
                    evidence["app_share"].asPercentText(),
                    evidence["upload_ratio"].asPercentText(),
                )
            AnomalyType.TOTAL_TRAFFIC_SPIKE ->
                appContext.getString(
                    R.string.anomaly_reason_total_spike,
                    evidence["bytes_per_min"].asBytesText(),
                    evidence["robust_z"].orEmpty().ifBlank { appContext.getString(R.string.statistics_no_data) },
                )
            AnomalyType.NEW_DESTINATION_COUNTRY ->
                appContext.getString(
                    R.string.anomaly_reason_new_country,
                    evidence["country"].orEmpty().ifBlank { appContext.getString(R.string.statistics_no_data) },
                    evidence["traffic_share"].asPercentText(),
                )
            AnomalyType.DNS_BLOCK_RATIO_SPIKE ->
                appContext.getString(
                    R.string.anomaly_reason_dns_blocks,
                    evidence["blocked_dns"].orEmpty().ifBlank { "0" },
                    evidence["allowed_dns"].orEmpty().ifBlank { "0" },
                    evidence["blocked_ratio"].asPercentText(),
                )
            AnomalyType.RECONNECT_STORM ->
                appContext.getString(
                    R.string.anomaly_reason_reconnects,
                    evidence["reconnects"].orEmpty().ifBlank { "0" },
                )
            AnomalyType.LATENCY_SHIFT ->
                appContext.getString(
                    R.string.anomaly_reason_latency,
                    evidence["latency_ms"].orEmpty().ifBlank { "0" },
                    evidence["robust_z"].orEmpty().ifBlank { appContext.getString(R.string.statistics_no_data) },
                )
            AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH ->
                appContext.getString(
                    R.string.anomaly_reason_privacy_route,
                    evidence["route_share"].asPercentText(),
                )
            AnomalyType.DORMANT_APP_NETWORK_ACTIVITY ->
                appContext.getString(
                    R.string.anomaly_reason_dormant_app,
                    subject,
                    evidence["idle_days"].orEmpty().ifBlank { "0" },
                    evidence["bytes"].asBytesText(),
                )
        }
    }

    private fun String?.asPercentText(): String =
        this
            ?.toFloatOrNull()
            ?.let { value -> "${(value.coerceIn(0f, 1f) * 100f).toInt()}%" }
            ?: appContext.getString(R.string.statistics_no_data)

    private fun String?.asBytesText(): String =
        this
            ?.toLongOrNull()
            ?.let { value -> Formatter.formatShortFileSize(appContext, value) }
            ?: appContext.getString(R.string.statistics_no_data)

    private fun labelForPackageName(packageName: String): String? =
        runCatching {
            appContext.packageManager
                .getApplicationInfo(packageName, 0)
                .loadLabel(appContext.packageManager)
                ?.toString()
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    private fun ensureChannel() {
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
