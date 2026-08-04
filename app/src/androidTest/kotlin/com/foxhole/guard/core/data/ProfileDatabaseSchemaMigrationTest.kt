package com.foxhole.guard.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.retiredCustomConfigProtocolStorageToken
import com.foxhole.core.model.retiredRawConfigSourceStorageToken
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDatabaseSchemaMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ProfileDatabase::class.java,
        )

    @Test
    fun version10ProfileTokensMigrateWithoutDroppingTheProfile() {
        val retiredSource = retiredRawConfigSourceStorageToken()
        val retiredProtocol = retiredCustomConfigProtocolStorageToken()
        helper.createDatabase(DATABASE_NAME, 10).apply {
            execSQL(
                """
                insert into `profiles`
                    (`id`, `name`, `sourceType`, `secretRef`, `protocolHint`,
                     `lastUpdatedAt`, `lastEtag`, `isActive`)
                values (?, ?, ?, ?, ?, null, null, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    7L,
                    "Migrated profile",
                    retiredSource,
                    "123e4567-e89b-12d3-a456-426614174000",
                    retiredProtocol,
                    1,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            PROFILE_DATABASE_VERSION,
            true,
            *ProfileDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query(
                "select `sourceType`, `protocolHint` from `profiles` where `id` = 7",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("RAW_CONFIG_JSON", cursor.getString(0))
                assertEquals("CUSTOM_CONFIG", cursor.getString(1))
                assertEquals(1, cursor.count)
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "profile-migration-test"
    }
}
