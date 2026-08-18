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
import com.foxhole.guard.WatchdogNames
import com.foxhole.guard.withStoredAppLocale

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
                WatchdogNames.GUARD_ID,
                "guard foreground start rejected",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        val body = getString(R.string.guard_service_notification_text)
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_app_guard)
                .setContentTitle(getString(R.string.guard_service_notification_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
                WatchdogNames.GUARD,
                NotificationManager.IMPORTANCE_MIN
            )
        channel.setShowBadge(false)
        channel.description = getString(R.string.guard_service_channel_name)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = WatchdogNames.GUARD_ID
        const val NOTIFICATION_ID = 1003

        const val HOST_NAME = WatchdogNames.GUARD

        fun start(context: Context) {
            val intent = Intent(context, FoxholeGuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FoxholeGuardService::class.java)) }
        }
    }
}
