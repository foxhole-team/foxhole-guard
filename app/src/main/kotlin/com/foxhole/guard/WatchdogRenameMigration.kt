package com.foxhole.guard

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.work.WorkManager

internal fun Context.migrateRenamedWatchdogs() {
    val preferences = getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    if (preferences.getBoolean(WATCHDOG_RENAME_DONE, false)) {
        return
    }

    preferences.edit { putBoolean(WATCHDOG_RENAME_DONE, true) }

    runCatching {
        val workManager = WorkManager.getInstance(this)
        LEGACY_WORK_NAMES.forEach(workManager::cancelUniqueWork)
    }
    runCatching {
        val notificationManager = getSystemService<NotificationManager>() ?: return@runCatching
        val registered = notificationManager.notificationChannels.mapTo(mutableSetOf()) { it.id }
        LEGACY_CHANNEL_IDS.filter { id -> id in registered }
            .forEach(notificationManager::deleteNotificationChannel)
    }
}

private val LEGACY_WORK_NAMES = listOf("webapps-watchdog", "guard-heartbeat")

private val LEGACY_CHANNEL_IDS = listOf("foxhole_webapps", "foxhole-guard")

private const val MIGRATION_PREFS = "foxhole_migrations"

private const val WATCHDOG_RENAME_DONE = "watchdog_rename_cleanup_done"

internal fun legacyWatchdogWorkNamesForTest(): List<String> = LEGACY_WORK_NAMES

internal fun legacyWatchdogChannelIdsForTest(): List<String> = LEGACY_CHANNEL_IDS
