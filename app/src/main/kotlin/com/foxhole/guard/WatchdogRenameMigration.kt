package com.foxhole.guard

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.work.WorkManager

/**
 * Clears what the watchdogs left behind under their old identifiers.
 *
 * Naming the two watchdogs changed the ids they register with, and both registries the app writes
 * into are owned by the system rather than by the app: WorkManager keeps unique periodic work by
 * name, and Android keeps notification channels by id. On a fresh install neither exists. On an
 * install that already ran the previous build both do, and neither goes away by itself:
 *
 *  - the old unique work stays enqueued and keeps running its worker beside the newly named one —
 *    a second web-app poll and a second heartbeat every fifteen minutes, forever;
 *  - the old channels stay in Android's notification settings as entries nothing posts to.
 *
 * Cancelling the work is the half that matters; deleting the channels is tidiness with one real
 * consequence, and it is the reason this is not simply left alone: a user who had muted web app
 * notifications muted the OLD channel, and that preference does not follow the rename. Deleting the
 * orphan at least stops the settings screen from showing a switch that controls nothing — the mute
 * itself has to be set again, and no migration can carry it across an id change.
 *
 * Runs once, gated by a flag in the same preferences the terminal uses, and does nothing on a
 * fresh install where the ids were never registered.
 */
internal fun Context.migrateRenamedWatchdogs() {
    val preferences = getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    if (preferences.getBoolean(WATCHDOG_RENAME_DONE, false)) {
        return
    }
    // The flag is written first and unconditionally. A migration that only records success would
    // retry forever on a device where one of these calls throws, and retrying costs more than the
    // orphan it would clear: both operations are best-effort cleanup of state the app no longer
    // uses.
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

/** What the two watchdogs were called before they had names. Frozen: this list only shrinks. */
private val LEGACY_WORK_NAMES = listOf("webapps-watchdog", "guard-heartbeat")

private val LEGACY_CHANNEL_IDS = listOf("foxhole_webapps", "foxhole-guard")

private const val MIGRATION_PREFS = "foxhole_migrations"

private const val WATCHDOG_RENAME_DONE = "watchdog_rename_cleanup_done"

/**
 * The two lists, for the test that holds this generation and the previous one side by side.
 *
 * Nothing else reads the legacy ids, so without a test they are a comment that compiles: the next
 * rename could leave them pointing at the generation before last and nobody would notice.
 */
internal fun legacyWatchdogWorkNamesForTest(): List<String> = LEGACY_WORK_NAMES

internal fun legacyWatchdogChannelIdsForTest(): List<String> = LEGACY_CHANNEL_IDS
