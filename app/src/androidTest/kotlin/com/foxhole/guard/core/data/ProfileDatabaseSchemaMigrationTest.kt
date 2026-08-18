package com.foxhole.guard.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.retiredCustomConfigProtocolStorageToken
import com.foxhole.core.model.retiredRawConfigSourceStorageToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun version11WebAppsMigrateToTheGlobalDefaultRoute() {
        helper.createDatabase(DATABASE_NAME, 11).apply {
            execSQL(
                """
                insert into `web_apps`
                    (`id`, `url`, `name`, `iconPath`, `sortOrder`, `badgeCount`,
                     `lastPolledAtMs`, `createdAtMs`)
                values (?, ?, ?, null, ?, ?, null, ?)
                """.trimIndent(),
                arrayOf<Any?>(3L, "https://app.example", "Example", 0, 4, 123L),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            PROFILE_DATABASE_VERSION,
            true,
            *ProfileDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("select `route`, `badgeCount` from `web_apps` where `id` = 3").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("DEFAULT", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals(1, cursor.count)
            }
        }
    }

    @Test
    fun version13AppTrafficRowsGainTheOrderedHistoryIndex() {
        helper.createDatabase(APP_TRAFFIC_DATABASE_NAME, 13).apply {
            execSQL(
                """
                insert into `app_traffic_windows`
                    (`packageName`, `uid`, `startedAtMs`, `durationSec`, `rxBytes`, `txBytes`,
                     `foreground`, `networkType`, `hourBucket`)
                values ('app.saved', 10001, 1234, 60, 12, 34, 1, 'WIFI', 5)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            APP_TRAFFIC_DATABASE_NAME,
            PROFILE_DATABASE_VERSION,
            true,
            *ProfileDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query("select count(*) from `app_traffic_windows` where `packageName` = 'app.saved'")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            database.query("pragma index_list(`app_traffic_windows`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val indexes = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
                assertTrue(APP_TRAFFIC_HISTORY_INDEX in indexes)
                assertFalse(OLD_APP_TRAFFIC_HISTORY_INDEX in indexes)
            }
        }
    }

    @Test
    fun appTrafficHistoryQueryAvoidsTheCorrelatedScanAtProductionScale() {
        helper.createDatabase(APP_TRAFFIC_PLAN_DATABASE_NAME, 13).close()
        helper.runMigrationsAndValidate(
            APP_TRAFFIC_PLAN_DATABASE_NAME,
            PROFILE_DATABASE_VERSION,
            true,
            *ProfileDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.execSQL(
                """
                with digits(d) as (values (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                numbers(n) as (
                    select a.d + b.d * 10 + c.d * 100 + d.d * 1000 + e.d * 10000
                    from digits a, digits b, digits c, digits d, digits e
                )
                insert into `app_traffic_windows`
                    (`packageName`, `uid`, `startedAtMs`, `durationSec`, `rxBytes`, `txBytes`,
                     `foreground`, `networkType`, `hourBucket`)
                select 'app.' || (n % 250), 10000 + (n % 250), 1700000000000 + n,
                       60, n, n / 2, 1, 'WIFI', 5
                from numbers where n < 60000
                """.trimIndent(),
            )
            val query =
                """
                select * from `app_traffic_windows`
                where `packageName` = 'app.42' and `networkType` = 'WIFI' and `hourBucket` = 5
                order by `startedAtMs` desc, `id` desc limit 96
                """.trimIndent()
            val plan = buildList {
                database.query("explain query plan $query").use { cursor ->
                    val detailColumn = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detailColumn))
                }
            }
            assertTrue(plan.joinToString().contains(APP_TRAFFIC_HISTORY_INDEX))
            assertFalse(plan.any { detail -> detail.contains("CORRELATED", ignoreCase = true) })
            assertFalse(plan.any { detail -> detail.contains("TEMP B-TREE", ignoreCase = true) })
            database.query(query).use { cursor -> assertEquals(96, cursor.count) }
        }
    }

    private companion object {
        const val DATABASE_NAME = "profile-migration-test"
        const val APP_TRAFFIC_DATABASE_NAME = "profile-app-traffic-migration-test"
        const val APP_TRAFFIC_PLAN_DATABASE_NAME = "profile-app-traffic-plan-test"
        const val OLD_APP_TRAFFIC_HISTORY_INDEX =
            "index_app_traffic_windows_packageName_networkType_hourBucket"
        const val APP_TRAFFIC_HISTORY_INDEX =
            "index_app_traffic_windows_packageName_networkType_hourBucket_startedAtMs_id"
    }
}
