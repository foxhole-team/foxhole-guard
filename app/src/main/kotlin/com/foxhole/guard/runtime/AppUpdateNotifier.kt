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
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.withStoredAppLocale

internal class AppUpdateNotifier(
    context: Context,
) {
    private val baseAppContext = context.applicationContext
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }

    fun notifyAvailable(
        update: AppUpdateCheck.Available,
        installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): Boolean {
        val versionCode = update.manifest.versionCode
        val versionName = update.versionName
        if (versionCode in 1L..installedVersionCode) {
            return false
        }
        if (
            !AppUpdatePolicy.updateNotificationRequired(
                severity = update.severity,
                availableVersionName = versionName,
                lastNotifiedVersionName = lastNotifiedVersionName(),
            )
        ) {
            return false
        }
        rememberNotifiedVersionCode(versionCode)
        rememberNotifiedVersionName(versionName)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel()
        val body = appContext.getString(R.string.app_update_notification_body, versionName)
        val detail = appContext.getString(R.string.app_update_notification_required_body, versionName)
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_update)
                .setContentTitle(appContext.getString(R.string.app_update_notification_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$body"))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(openUpdatesPendingIntent())
                .build()
        return runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        }.isSuccess
    }

    fun clearAnnouncedVersion() {
        preferences().edit {
            remove(KEY_LAST_NOTIFIED_VERSION_CODE)
            remove(KEY_LAST_NOTIFIED_VERSION_NAME)
        }
        runCatching { NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID) }
    }

    internal fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.app_update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.app_update_notification_channel_description)
            },
        )
    }

    private fun lastNotifiedVersionName(): String = preferences().getString(
        KEY_LAST_NOTIFIED_VERSION_NAME,
        ""
    ).orEmpty()

    private fun rememberNotifiedVersionCode(versionCode: Long) {
        preferences().edit { putLong(KEY_LAST_NOTIFIED_VERSION_CODE, versionCode) }
    }

    private fun rememberNotifiedVersionName(versionName: String) {
        preferences().edit { putString(KEY_LAST_NOTIFIED_VERSION_NAME, versionName) }
    }

    private fun preferences() = baseAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun openUpdatesPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_UPDATES,
            Intent(appContext, com.foxhole.guard.ui.cli.CliMainActivity::class.java)
                .setAction(ACTION_OPEN_APP_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_OPEN_APP_UPDATE = "com.foxhole.guard.action.OPEN_APP_UPDATE"
        const val CHANNEL_ID = "foxhole_app_updates"
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION_CODE = "last_notified_version_code"
        private const val KEY_LAST_NOTIFIED_VERSION_NAME = "last_notified_version_name"
        private const val NOTIFICATION_ID = 9500
        private const val REQUEST_CODE_OPEN_UPDATES = 220
    }
}
