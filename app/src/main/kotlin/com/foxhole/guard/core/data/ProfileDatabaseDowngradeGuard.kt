package com.foxhole.guard.core.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

// Written after every successful open so the downgrade notice can be decided before anything
// touches the (possibly newer) database. Plain preferences on purpose: the marker must be readable
// pre-unlock and pre-SQLCipher, and it carries no secret - only a schema number.
private const val DB_META_PREFERENCES = "foxhole_db_meta"
private const val KEY_SCHEMA_VERSION = "profile_db_schema_version"

private const val TAG = "FoxholeDbGuard"

/** Thrown instead of letting Room's destructive fallback wipe a database written by a newer build. */
internal class ProfileDatabaseDowngradeException(
    storedVersion: Int,
    supportedVersion: Int,
) : IllegalStateException(
    "profile database schema v$storedVersion was written by a newer build; this build supports " +
        "up to v$supportedVersion - refusing to open (a destructive recreate would wipe the " +
        "user's profiles)",
)

/**
 * Refuses to hand a newer-schema database to Room. Room is configured with a destructive fallback
 * as corruption recovery, which would also fire on a downgrade (an older APK installed over a
 * newer one - realistic with sideloaded builds) and silently wipe every saved profile. Reading
 * `user_version` costs one extra keyed open, paid once per process inside [ProfileDatabase.create].
 */
internal fun assertNoProfileDatabaseDowngrade(
    databaseFile: File,
    passphrase: ByteArray,
    supportedVersion: Int = PROFILE_DATABASE_VERSION,
) {
    if (!databaseFile.exists()) {
        return
    }
    val storedVersion = readStoredSchemaVersion(databaseFile, passphrase) ?: return
    if (isDowngradedProfileDatabase(storedVersion, supportedVersion)) {
        throw ProfileDatabaseDowngradeException(storedVersion, supportedVersion)
    }
}

internal fun isDowngradedProfileDatabase(
    storedVersion: Int,
    supportedVersion: Int,
): Boolean = storedVersion > supportedVersion

private fun readStoredSchemaVersion(
    databaseFile: File,
    passphrase: ByteArray,
): Int? {
    // SQLCipher keys with its own copy, zeroed below; the caller's array stays intact for Room.
    val passphraseCopy = passphrase.copyOf()
    return try {
        runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                passphraseCopy,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            ).use { database -> database.version }
        }.getOrElse { failure ->
            // Unreadable file (corruption, wrong key). Not a downgrade signal - let Room surface
            // its canonical open error instead of guessing here.
            Log.w(TAG, "profile database version precheck skipped: ${failure.message}")
            null
        }
    } finally {
        passphraseCopy.fill(0)
    }
}

/** Persisted from Room's onOpen callback; consumed by [profileDatabaseDowngradeDetected]. */
internal fun recordProfileDatabaseSchemaVersion(
    context: Context,
    version: Int,
) {
    context.applicationContext
        .getSharedPreferences(DB_META_PREFERENCES, Context.MODE_PRIVATE)
        .edit { putInt(KEY_SCHEMA_VERSION, version) }
}

/**
 * Cheap pre-UI check for the fail-loud downgrade notice: true when the last successfully opened
 * database schema is newer than this build supports. Must stay free of database/keybox touches -
 * MainActivity consults it before the view-model (and its database-backed flows) exists.
 */
fun profileDatabaseDowngradeDetected(context: Context): Boolean {
    val recorded =
        context.applicationContext
            .getSharedPreferences(DB_META_PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_SCHEMA_VERSION, 0)
    return isDowngradedProfileDatabase(recorded, PROFILE_DATABASE_VERSION)
}
