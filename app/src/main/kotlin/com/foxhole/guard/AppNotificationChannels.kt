package com.foxhole.guard

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.sentinel.anomaly.SentinelDetectionNotifier
import com.foxhole.guard.core.webapps.WebAppsNotifier
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.NetworkRuleRecommendationNotifier
import com.foxhole.guard.runtime.SystemDnsChangeMonitor
import com.foxhole.guard.runtime.ensureConnectionNotificationChannel

/**
 * Re-registers the notification channels the system already knows about so their names follow the
 * app language. Android stores the strings a channel was created with and never resolves them
 * again, so a channel registered under the previous locale kept the old name until its owner
 * happened to post the next notification — «уведомления» sitting in an English app.
 *
 * Re-registering an existing id rewrites name and description only: importance and everything the
 * user changed themselves are frozen at creation, which is why this is safe to call at any time.
 * Ids the system does not carry yet are skipped on purpose — a language switch must not conjure
 * channels for features that never fired.
 */
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
    if (FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID in registered) {
        // The services carry a locale-wrapped base context; from the app context the wrap is ours.
        withStoredAppLocale().ensureConnectionNotificationChannel(notificationManager)
    }
}
