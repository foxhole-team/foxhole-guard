package com.foxhole.guard.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.withStoredAppLocale

/**
 * REINFORCED-mode host for the guard daemon: a lightweight foreground service that keeps
 * the daemon resident whenever no runtime (VPN/proxy) service is up. Uses a silent,
 * minimum-importance, lock-screen-secret notification so it is as unobtrusive as Android
 * allows for an always-on foreground service. It stops itself once a runtime service
 * re-attaches the daemon.
 */
class FoxholeGuardService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withStoredAppLocale())
    }

    private val sentinel get() = (applicationContext as FoxholeApplication).appGraph.securityComponents.guardSentinel

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val security = (applicationContext as FoxholeApplication).appGraph.securityComponents
        if (!security.isEventMonitoringActive()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!startAsForeground()) {
            (applicationContext as FoxholeApplication).appGraph.diagnosticsLogger.record(
                "security",
                "guard foreground start rejected",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // If a runtime service is already hosting the daemon, this dedicated service is redundant.
        // Exclude our own host so a repeated START_STICKY/onStart command stays idempotent instead
        // of mistaking itself for a competing runtime and stopping the guard.
        if (sentinel.hasAttachedHostOtherThan(HOST_NAME)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sentinel.attach(HOST_NAME)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        sentinel.journalTaskRemoved(HOST_NAME)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sentinel.detach(HOST_NAME, destroyed = true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_app_guard)
                .setContentTitle(getString(R.string.guard_service_notification_title))
                .setContentText(getString(R.string.guard_service_notification_text))
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess
    }

    private fun ensureChannel(manager: NotificationManager) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_service_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "foxhole-guard"
        const val NOTIFICATION_ID = 1003
        const val HOST_NAME = "guard-service"

        fun start(context: Context) {
            val intent = Intent(context, FoxholeGuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FoxholeGuardService::class.java)) }
        }
    }
}
