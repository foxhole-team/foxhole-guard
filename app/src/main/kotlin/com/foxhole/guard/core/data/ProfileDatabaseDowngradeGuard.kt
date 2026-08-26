package com.foxhole.guard.core.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

private const val DB_META_PREFERENCES = "foxhole_db_meta"
private const val KEY_SCHEMA_VERSION = "profile_db_schema_version"

private const val TAG = "FoxholeDbGuard"

internal class ProfileDatabaseDowngradeException(
    storedVersion: Int,
    supportedVersion: Int,
) : IllegalStateException(
    "profile database schema v$storedVersion was written by a newer build; this build supports " +
        "up to v$supportedVersion - refusing to open (a destructive recreate would wipe the " +
        "user's profiles)",
)

// Refuse newer schemas before Room can apply its corruption-only destructive fallback during a downgrade.
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

            Log.w(TAG, "profile database version precheck skipped: ${failure.message}")
            null
        }
    } finally {
        passphraseCopy.fill(0)
    }
}

internal fun recordProfileDatabaseSchemaVersion(
    context: Context,
    version: Int,
) {
    context.applicationContext
        .getSharedPreferences(DB_META_PREFERENCES, Context.MODE_PRIVATE)
        .edit { putInt(KEY_SCHEMA_VERSION, version) }
}

fun profileDatabaseDowngradeDetected(context: Context): Boolean {
    val recorded =
        context.applicationContext
            .getSharedPreferences(DB_META_PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_SCHEMA_VERSION, 0)
    return isDowngradedProfileDatabase(recorded, PROFILE_DATABASE_VERSION)
}
