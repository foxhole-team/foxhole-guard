package com.foxhole.guard.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foxhole.core.model.retiredCustomConfigProtocolStorageToken
import com.foxhole.core.model.retiredRawConfigSourceStorageToken
import com.foxhole.guard.core.security.DatabaseKeySource
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean

internal const val PROFILE_DATABASE_VERSION = 14

@Database(
    entities = [
        ProfileEntity::class,
        RoutingPresetEntity::class,
        RoutingRuleEntity::class,
        RoutingCatalogEntity::class,
        TrafficWindowEntity::class,
        AppTrafficWindowEntity::class,
        TrafficBaselineEntity::class,
        AppBaselineEntity::class,
        AnomalyEventEntity::class,
        ProtocolMetricEventEntity::class,
        NetworkActivityEventEntity::class,
        SeenDestinationCountryEntity::class,
        AppNetworkPresenceEntity::class,
        AnomalyCounterEntity::class,
        I2pTrafficBucketEntity::class,
        I2pTrafficTotalEntity::class,
        WebAppEntity::class,
    ],
    version = PROFILE_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(RoomValueConverters::class)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun routingPresetDao(): RoutingPresetDao

    abstract fun routingRuleDao(): RoutingRuleDao

    abstract fun routingCatalogDao(): RoutingCatalogDao

    abstract fun anomalyDao(): AnomalyDao

    abstract fun i2pTrafficDao(): I2pTrafficDao

    abstract fun webAppDao(): WebAppDao

    companion object {
        private const val SECURE_DB_NAME = "foxhole.secure.db"
        private val sqlCipherLoaded = AtomicBoolean(false)

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        create table if not exists `routing_presets` (
                            `id` integer primary key autoincrement not null,
                            `name` text not null,
                            `source` text not null,
                            `catalogId` integer,
                            `overrideMode` text not null,
                            `enabled` integer not null,
                            `updatedAt` integer not null,
                            `isActive` integer not null
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create index if not exists `index_routing_presets_catalogId` on `routing_presets` (`catalogId`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_routing_presets_isActive` on `routing_presets` (`isActive`)"
                    )
                    db.execSQL(
                        """
                        create table if not exists `routing_rules` (
                            `id` integer primary key autoincrement not null,
                            `presetId` integer not null,
                            `name` text not null,
                            `enabled` integer not null,
                            `order` integer not null,
                            `action` text not null,
                            `matchDomains` text not null,
                            `matchIpCidrs` text not null,
                            `matchPorts` text not null,
                            `matchProtocols` text not null,
                            `matchNetworks` text not null,
                            foreign key(`presetId`) references `routing_presets`(`id`) on update no action on delete cascade
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create index if not exists `index_routing_rules_presetId` on `routing_rules` (`presetId`)"
                    )
                    db.execSQL(
                        """
                        create table if not exists `routing_catalogs` (
                            `id` integer primary key autoincrement not null,
                            `name` text not null,
                            `url` text not null,
                            `enabled` integer not null,
                            `etag` text,
                            `lastSyncAt` integer,
                            `warningAcceptedAt` integer,
                            `cachedManifestJson` text
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create unique index if not exists `index_routing_catalogs_url` on `routing_catalogs` (`url`)"
                    )
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    createTrafficWindowTables(db)
                    createTrafficBaselineTables(db)
                    createAnomalyEventTables(db)
                }
            }

        private fun createTrafficWindowTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                create table if not exists `traffic_windows` (
                    `id` integer primary key autoincrement not null,
                    `startedAtMs` integer not null,
                    `durationSec` integer not null,
                    `networkType` text not null,
                    `vpnMode` text not null,
                    `profileId` text,
                    `protocol` text,
                    `hourBucket` integer not null,
                    `rxBytes` integer not null,
                    `txBytes` integer not null,
                    `blockedDns` integer not null,
                    `allowedDns` integer not null,
                    `reconnects` integer not null,
                    `latencyMs` integer,
                    `destinationCountries` text not null
                )
                """.trimIndent(),
            )
            db.execSQL(
                "create index if not exists `index_traffic_windows_startedAtMs` on `traffic_windows` (`startedAtMs`)"
            )
            db.execSQL(
                "create index if not exists `index_traffic_windows_profileId_protocol_networkType_hourBucket` on `traffic_windows` (`profileId`, `protocol`, `networkType`, `hourBucket`)"
            )
            db.execSQL(
                """
                create table if not exists `app_traffic_windows` (
                    `id` integer primary key autoincrement not null,
                    `packageName` text not null,
                    `uid` integer not null,
                    `startedAtMs` integer not null,
                    `durationSec` integer not null,
                    `rxBytes` integer not null,
                    `txBytes` integer not null,
                    `foreground` integer,
                    `networkType` text not null,
                    `hourBucket` integer not null
                )
                """.trimIndent(),
            )
            db.execSQL(
                "create index if not exists `index_app_traffic_windows_startedAtMs` on `app_traffic_windows` (`startedAtMs`)"
            )
            db.execSQL(
                "create index if not exists `index_app_traffic_windows_packageName` on `app_traffic_windows` (`packageName`)"
            )
            db.execSQL(
                "create index if not exists `index_app_traffic_windows_packageName_networkType_hourBucket_startedAtMs_id` " +
                    "on `app_traffic_windows` (`packageName`, `networkType`, `hourBucket`, `startedAtMs`, `id`)"
            )
        }

        private fun createTrafficBaselineTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                create table if not exists `traffic_baselines` (
                    `baselineKey` text not null primary key,
                    `profileId` text,
                    `protocol` text,
                    `networkType` text not null,
                    `hourBucket` integer not null,
                    `metric` text not null,
                    `median` real not null,
                    `mad` real not null,
                    `ewma` real not null,
                    `ewmad` real not null,
                    `sampleCount` integer not null,
                    `lastUpdatedAt` integer not null
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                create table if not exists `app_baselines` (
                    `baselineKey` text not null primary key,
                    `packageName` text not null,
                    `profileId` text,
                    `protocol` text,
                    `networkType` text not null,
                    `hourBucket` integer not null,
                    `metric` text not null,
                    `median` real not null,
                    `mad` real not null,
                    `ewma` real not null,
                    `ewmad` real not null,
                    `sampleCount` integer not null,
                    `lastUpdatedAt` integer not null
                )
                """.trimIndent(),
            )
            db.execSQL(
                "create index if not exists `index_app_baselines_packageName` on `app_baselines` (`packageName`)"
            )
            db.execSQL(
                "create index if not exists `index_app_baselines_packageName_networkType_hourBucket` on `app_baselines` (`packageName`, `networkType`, `hourBucket`)"
            )
        }

        private fun createAnomalyEventTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                create table if not exists `anomaly_events` (
                    `id` integer primary key autoincrement not null,
                    `createdAtMs` integer not null,
                    `type` text not null,
                    `severity` text not null,
                    `score` integer not null,
                    `reason` text not null,
                    `evidence` text not null,
                    `packageName` text,
                    `profileId` text,
                    `protocol` text,
                    `notificationShown` integer not null
                )
                """.trimIndent(),
            )
            db.execSQL(
                "create index if not exists `index_anomaly_events_createdAtMs` on `anomaly_events` (`createdAtMs`)"
            )
            db.execSQL(
                "create index if not exists `index_anomaly_events_packageName` on `anomaly_events` (`packageName`)"
            )
            db.execSQL(
                "create index if not exists `index_anomaly_events_type_packageName_createdAtMs` on `anomaly_events` (`type`, `packageName`, `createdAtMs`)"
            )
        }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        create table if not exists `runtime_timeline_events` (
                            `id` integer primary key autoincrement not null,
                            `timestampMs` integer not null,
                            `generationId` integer not null,
                            `sessionId` text,
                            `owner` text not null,
                            `mode` text not null,
                            `stage` text not null,
                            `status` text not null,
                            `durationMs` integer,
                            `profileId` integer,
                            `protocol` text,
                            `details` text not null
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create index if not exists `index_runtime_timeline_events_timestampMs` on `runtime_timeline_events` (`timestampMs`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_runtime_timeline_events_generationId` on `runtime_timeline_events` (`generationId`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_runtime_timeline_events_sessionId` on `runtime_timeline_events` (`sessionId`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_runtime_timeline_events_owner_stage_status_timestampMs` on `runtime_timeline_events` (`owner`, `stage`, `status`, `timestampMs`)"
                    )
                    db.execSQL(
                        """
                        create table if not exists `protocol_metric_events` (
                            `id` integer primary key autoincrement not null,
                            `timestampMs` integer not null,
                            `profileId` integer not null,
                            `optionId` text,
                            `protocol` text not null,
                            `event` text not null,
                            `latencyMs` integer,
                            `reasonCode` text
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create index if not exists `index_protocol_metric_events_timestampMs` on `protocol_metric_events` (`timestampMs`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_protocol_metric_events_profileId` on `protocol_metric_events` (`profileId`)"
                    )
                    db.execSQL(
                        "create index if not exists `index_protocol_metric_events_protocol_event_timestampMs` on `protocol_metric_events` (`protocol`, `event`, `timestampMs`)"
                    )
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        create table if not exists `network_activity_events` (
                            `id` integer primary key autoincrement not null,
                            `timestampMs` integer not null,
                            `packageNames` text not null,
                            `protocol` text not null,
                            `remoteHost` text not null,
                            `remotePort` integer,
                            `countryCode` text,
                            `bytesRx` integer not null,
                            `bytesTx` integer not null,
                            `profileId` integer,
                            `sessionId` text
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "create index if not exists `index_network_activity_events_timestampMs` on `network_activity_events` (`timestampMs`)",
                    )
                    db.execSQL(
                        "create index if not exists `index_network_activity_events_profileId` on `network_activity_events` (`profileId`)",
                    )
                    db.execSQL(
                        "create index if not exists `index_network_activity_events_sessionId` on `network_activity_events` (`sessionId`)",
                    )
                    db.execSQL(
                        "create index if not exists `index_network_activity_events_remoteHost` on `network_activity_events` (`remoteHost`)",
                    )
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "alter table `traffic_windows` add column `blockedDnsDomains` text not null default '{}'",
                    )
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "alter table `traffic_windows` add column `blockedDnsByCategory` text not null default '{}'",
                    )
                    db.execSQL(
                        "alter table `traffic_windows` add column `blockedDnsApps` text not null default '{}'",
                    )
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("drop table if exists `runtime_timeline_events`")
                }
            }

        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "create table if not exists `seen_destination_countries` " +
                            "(`country` text not null, `firstSeenMs` integer not null, " +
                            "`lastSeenMs` integer not null, `totalBytes` integer not null, " +
                            "primary key(`country`))",
                    )
                    db.execSQL(
                        "create table if not exists `app_network_presence` " +
                            "(`packageName` text not null, `firstSeenMs` integer not null, " +
                            "`lastSeenMs` integer not null, `windowCount` integer not null, " +
                            "primary key(`packageName`))",
                    )
                    db.execSQL(
                        "create table if not exists `anomaly_counters` " +
                            "(`counterKey` text not null, `value` integer not null, " +
                            "primary key(`counterKey`))",
                    )
                }
            }

        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "create table if not exists `web_apps` " +
                            "(`id` integer primary key autoincrement not null, " +
                            "`url` text not null, `name` text not null, `iconPath` text, " +
                            "`sortOrder` integer not null, `badgeCount` integer not null, " +
                            "`lastPolledAtMs` integer, `createdAtMs` integer not null)",
                    )
                }
            }

        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "update `profiles` set `sourceType` = ? where `sourceType` = ?",
                        arrayOf(
                            com.foxhole.core.model.ProfileSourceType.RAW_CONFIG_JSON.name,
                            retiredRawConfigSourceStorageToken(),
                        ),
                    )
                    db.execSQL(
                        "update `profiles` set `protocolHint` = ? where `protocolHint` = ?",
                        arrayOf(
                            com.foxhole.core.model.ProtocolHint.CUSTOM_CONFIG.name,
                            retiredCustomConfigProtocolStorageToken(),
                        ),
                    )
                }
            }

        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "alter table `web_apps` add column `route` text not null default 'DEFAULT'",
                    )
                }
            }

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "create table if not exists `i2p_traffic_buckets` " +
                            "(`hourStartMs` integer not null, `ownBytes` integer not null, " +
                            "`transitBytes` integer not null, primary key(`hourStartMs`))",
                    )
                    db.execSQL(
                        "create table if not exists `i2p_traffic_totals` " +
                            "(`id` integer not null, `ownBytes` integer not null, " +
                            "`transitBytes` integer not null, primary key(`id`))",
                    )
                }
            }

        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "drop index if exists `index_app_traffic_windows_packageName_networkType_hourBucket`",
                    )
                    db.execSQL(
                        "create index if not exists " +
                            "`index_app_traffic_windows_packageName_networkType_hourBucket_startedAtMs_id` " +
                            "on `app_traffic_windows` " +
                            "(`packageName`, `networkType`, `hourBucket`, `startedAtMs`, `id`)",
                    )
                }
            }

        internal val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
            )

        fun create(
            context: Context,
            keySource: DatabaseKeySource,
        ): ProfileDatabase {
            val appContext = context.applicationContext
            ensureSqlCipherLoaded()
            val passphrase = keySource.acquirePassphrase()

            assertNoProfileDatabaseDowngrade(appContext.getDatabasePath(SECURE_DB_NAME), passphrase)
            val builder =
                Room.databaseBuilder(
                    appContext,
                    ProfileDatabase::class.java,
                    SECURE_DB_NAME,
                ).openHelperFactory(SupportOpenHelperFactory(passphrase))
            ALL_MIGRATIONS.forEach { migration -> builder.addMigrations(migration) }
            builder.addCallback(
                object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        recordProfileDatabaseSchemaVersion(appContext, db.version)
                    }
                },
            )
            val database =
                builder
                    .fallbackToDestructiveMigration(false)
                    .build()
            migrateLegacyPlaintextDatabase(appContext, database)
            return database
        }

        private fun ensureSqlCipherLoaded() {
            if (sqlCipherLoaded.get()) {
                return
            }
            synchronized(this) {
                if (!sqlCipherLoaded.get()) {
                    System.loadLibrary("sqlcipher")
                    sqlCipherLoaded.set(true)
                }
            }
        }
    }
}
