package com.foxhole.guard

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.sentinel.anomaly.SentinelDetectionNotifier
import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.runtime.AppUpdateNotifier
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.NetworkRuleRecommendationNotifier
import com.foxhole.guard.runtime.SystemDnsChangeMonitor
import com.foxhole.guard.runtime.ensureConnectionNotificationChannel

// Android persists channel text and user-edited importance; re-register only existing IDs after a locale change.
internal fun Context.refreshLocalizedNotificationChannels() {
    val notificationManager = getSystemService<NotificationManager>() ?: return
    val registered = notificationManager.notificationChannels.mapTo(mutableSetOf()) { it.id }
    if (SentinelDetectionNotifier.CHANNEL_ID in registered) {
        SentinelDetectionNotifier(this).ensureChannel()
    }
    if (InstalledAppSecurityNotifier.CHANNEL_ID in registered) {
        InstalledAppSecurityNotifier(this).ensureChannel()
    }
    if (WebAppsNotifier.CHANNEL_ID in registered) {
        WebAppsNotifier(this).ensureChannel()
    }
    if (NetworkRuleRecommendationNotifier.CHANNEL_ID in registered) {
        NetworkRuleRecommendationNotifier(this).ensureChannel()
    }
    if (SystemDnsChangeMonitor.CHANNEL_ID in registered) {
        SystemDnsChangeMonitor(this).ensureChannel()
    }
    if (AppUpdateNotifier.CHANNEL_ID in registered) {
        AppUpdateNotifier(this).ensureChannel()
    }
    if (FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID in registered) {
        withStoredAppLocale().ensureConnectionNotificationChannel(notificationManager)
    }
}
