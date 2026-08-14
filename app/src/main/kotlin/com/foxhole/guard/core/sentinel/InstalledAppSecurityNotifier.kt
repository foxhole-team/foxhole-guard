package com.foxhole.guard.core.sentinel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale

class InstalledAppSecurityNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notifyInstalledApp(
        summary: InstalledAppSecuritySummary,
        quarantined: Boolean = false,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val source =
            summary.installerPackageName
                ?: appContext.getString(R.string.installed_app_source_unknown)
        val risk = appContext.getString(summary.riskLevel.labelRes())
        val signals = summary.riskSignals.joinToString(", ") { signal -> appContext.getString(signal.labelRes()) }
            .ifBlank { appContext.getString(R.string.installed_app_risk_signals_none) }
        val body =
            appContext.getString(
                R.string.installed_app_notification_body,
                summary.label,
                source,
                risk,
                signals,
            )
        val builder =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_app_guard)
                .setContentTitle(
                    appContext.getString(
                        if (quarantined) {
                            R.string.installed_app_quarantine_notification_title
                        } else {
                            R.string.installed_app_notification_title
                        },
                    ),
                )
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(!quarantined)
                .setContentIntent(
                    if (quarantined) {
                        foxholePendingIntent(summary.packageName)
                    } else {
                        appSettingsPendingIntent(summary.packageName, RequestCodeSettings)
                    },
                )
        if (!quarantined) {
            builder
                .addAction(
                    0,
                    appContext.getString(R.string.installed_app_action_uninstall),
                    uninstallPendingIntent(summary.packageName),
                )
                .addAction(
                    0,
                    appContext.getString(R.string.installed_app_action_settings),
                    appSettingsPendingIntent(summary.packageName, RequestCodeSettingsAction),
                )
                .addAction(
                    0,
                    appContext.getString(R.string.installed_app_action_restrict_access),
                    appSettingsPendingIntent(summary.packageName, RequestCodeAccessAction),
                )
        }
        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(
                    summary.packageName,
                    NOTIFICATION_ID,
                    builder.build(),
                )
        }
    }

    fun cancel(packageName: String) {
        NotificationManagerCompat.from(appContext).cancel(packageName, NOTIFICATION_ID)
    }

    private fun foxholePendingIntent(packageName: String): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            RequestCodeFoxhole + packageName.hashCode(),
            Intent(appContext, CliMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )

    private fun appSettingsPendingIntent(
        packageName: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            requestCode + packageName.hashCode(),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingIntentFlags(),
        )

    private fun uninstallPendingIntent(packageName: String): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            RequestCodeUninstall + packageName.hashCode(),
            Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingIntentFlags(),
        )

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    internal fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.installed_app_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.installed_app_notification_channel_description)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "foxhole_app_security"
        internal const val NOTIFICATION_ID = 9300
        private const val RequestCodeSettings = 110
        private const val RequestCodeSettingsAction = 120
        private const val RequestCodeAccessAction = 130
        private const val RequestCodeUninstall = 140
        private const val RequestCodeFoxhole = 150
    }
}

fun InstalledAppRiskLevel.labelRes(): Int =
    when (this) {
        InstalledAppRiskLevel.LOW -> R.string.installed_app_risk_low
        InstalledAppRiskLevel.MEDIUM -> R.string.installed_app_risk_medium
        InstalledAppRiskLevel.HIGH -> R.string.installed_app_risk_high
    }

fun InstalledAppRiskSignal.labelRes(): Int =
    when (this) {
        InstalledAppRiskSignal.ACCESSIBILITY_SERVICE -> R.string.installed_app_signal_accessibility
        InstalledAppRiskSignal.NOTIFICATION_LISTENER -> R.string.installed_app_signal_notification_listener
        InstalledAppRiskSignal.DEVICE_ADMIN -> R.string.installed_app_signal_device_admin
        InstalledAppRiskSignal.VPN_SERVICE -> R.string.installed_app_signal_vpn
        InstalledAppRiskSignal.OVERLAY_PERMISSION -> R.string.installed_app_signal_overlay
        InstalledAppRiskSignal.BATTERY_OPTIMIZATION_IGNORE -> R.string.installed_app_signal_battery_ignore
        InstalledAppRiskSignal.AUTOSTART -> R.string.installed_app_signal_autostart
        InstalledAppRiskSignal.UNKNOWN_INSTALLER -> R.string.installed_app_signal_unknown_installer
        InstalledAppRiskSignal.SYSTEM_LIKE_NAME -> R.string.installed_app_signal_system_like
        InstalledAppRiskSignal.KNOWN_THREAT -> R.string.installed_app_signal_known_threat
    }
