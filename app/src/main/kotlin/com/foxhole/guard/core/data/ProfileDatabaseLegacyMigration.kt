package com.foxhole.guard.core.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

private const val LEGACY_DB_NAME = "foxhole.db"
private const val TAG = "FoxholeDbLegacy"

private data class LegacyProfileRow(
    val id: Long,
    val name: String,
    val sourceType: String,
    val secretRef: String,
    val protocolHint: String,
    val lastUpdatedAt: Long?,
    val lastEtag: String?,
    val isActive: Boolean,
)

internal fun migrateLegacyPlaintextDatabase(
    context: Context,
    secureDatabase: ProfileDatabase,
) {
    val legacy = context.getDatabasePath(LEGACY_DB_NAME)
    if (!legacy.exists()) {
        return
    }
    val legacyRows = readLegacyProfileRows(legacy)
    val targetDatabase = secureDatabase.openHelper.writableDatabase
    targetDatabase.beginTransaction()
    try {
        legacyRows.forEach { row -> targetDatabase.insertLegacyProfile(row) }
        targetDatabase.setTransactionSuccessful()
    } finally {
        targetDatabase.endTransaction()
    }
    val unverifiedLegacyRows =
        legacyRows
            .filterNot { row -> targetDatabase.legacyProfileRow(row.id) == row }
    if (unverifiedLegacyRows.isNotEmpty()) {
        Log.w(
            TAG,
            "legacy plaintext migration left unverified rows " +
                "ids=${unverifiedLegacyRows.joinToString { it.id.toString() }}; keeping legacy file",
        )
        return
    }
    deleteLegacyPlaintextDatabase(legacy)
}

private fun readLegacyProfileRows(legacyDatabase: File): List<LegacyProfileRow> {
    val database =
        SQLiteDatabase.openDatabase(
            legacyDatabase.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    database.use { db ->
        db.rawQuery(
            """
            select id, name, sourceType, secretRef, protocolHint, lastUpdatedAt, lastEtag, isActive
            from profiles
            order by id asc
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            val rows = mutableListOf<LegacyProfileRow>()
            while (cursor.moveToNext()) {
                rows += cursor.currentLegacyProfileRow()
            }
            return rows
        }
    }
}

private fun SupportSQLiteDatabase.insertLegacyProfile(row: LegacyProfileRow) {
    execSQL(
        """
        insert or ignore into profiles
            (id, name, sourceType, secretRef, protocolHint, lastUpdatedAt, lastEtag, isActive)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        arrayOf<Any?>(
            row.id,
            row.name,
            row.sourceType,
            row.secretRef,
            row.protocolHint,
            row.lastUpdatedAt,
            row.lastEtag,
            if (row.isActive) 1 else 0,
        ),
    )
}

private fun SupportSQLiteDatabase.legacyProfileRow(id: Long): LegacyProfileRow? =
    query(
        SimpleSQLiteQuery(
            """
            select id, name, sourceType, secretRef, protocolHint, lastUpdatedAt, lastEtag, isActive
            from profiles
            where id = ?
            limit 1
            """.trimIndent(),
            arrayOf(id),
        ),
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.currentLegacyProfileRow()
        } else {
            null
        }
    }

private fun Cursor.currentLegacyProfileRow(): LegacyProfileRow =
    LegacyProfileRow(
        id = getLong(0),
        name = getString(1),
        sourceType = getString(2),
        secretRef = getString(3),
        protocolHint = getString(4),
        lastUpdatedAt = if (isNull(5)) null else getLong(5),
        lastEtag = if (isNull(6)) null else getString(6),
        isActive = getLong(7) != 0L,
    )

private fun deleteLegacyPlaintextDatabase(legacy: File) {
    val files =
        listOf(
            legacy,
            File("${legacy.absolutePath}-wal"),
            File("${legacy.absolutePath}-shm"),
            File("${legacy.absolutePath}-journal"),
        )
    val failedDeletes = files.filter { file -> file.exists() && !file.delete() }
    if (failedDeletes.isNotEmpty()) {
        Log.w(
            TAG,
            "legacy plaintext database migrated but not yet removed: " +
                failedDeletes.joinToString { it.name },
        )
    }
}
