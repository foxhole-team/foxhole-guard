package com.foxhole.beta.core.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.readBytesMigratingLegacy
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,
    val secretRef: String,
    val protocolHint: String,
    val lastUpdatedAt: Long?,
    val lastEtag: String?,
    val isActive: Boolean,
) {
    fun toDomain(): Profile =
        Profile(
            id = id,
            name = name,
            sourceType = ProfileSourceType.valueOf(sourceType),
            secretRef = secretRef,
            protocolHint = ProtocolHint.valueOf(protocolHint),
            lastUpdatedAt = lastUpdatedAt,
            lastEtag = lastEtag,
            isActive = isActive,
        )
}

@Entity(
    tableName = "routing_presets",
    indices = [
        Index("catalogId"),
        Index("isActive"),
    ],
)
data class RoutingPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val catalogId: Long?,
    val overrideMode: String,
    val enabled: Boolean,
    val updatedAt: Long,
    val isActive: Boolean,
) {
    fun toDomain(rules: List<RoutingRule>): RoutingPreset =
        RoutingPreset(
            id = id,
            name = name,
            source = RoutingPresetSource.valueOf(source),
            catalogId = catalogId,
            overrideMode = RoutingPresetOverrideMode.valueOf(overrideMode),
            enabled = enabled,
            updatedAt = updatedAt,
            isActive = isActive,
            rules = rules.sortedBy(RoutingRule::order),
        )
}

@Entity(
    tableName = "routing_rules",
    foreignKeys = [
        ForeignKey(
            entity = RoutingPresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("presetId"),
    ],
)
@TypeConverters(RoomValueConverters::class)
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: Long,
    val name: String,
    val enabled: Boolean,
    val order: Int,
    val action: String,
    val matchDomains: List<String>,
    val matchIpCidrs: List<String>,
    val matchPorts: List<String>,
    val matchProtocols: List<String>,
    val matchNetworks: List<String>,
) {
    fun toDomain(): RoutingRule =
        RoutingRule(
            id = id,
            presetId = presetId,
            name = name,
            enabled = enabled,
            order = order,
            action = RoutingRuleAction.valueOf(action),
            matchDomains = matchDomains,
            matchIpCidrs = matchIpCidrs,
            matchPorts = matchPorts,
            matchProtocols = matchProtocols,
            matchNetworks = matchNetworks,
        )
}

@Entity(
    tableName = "routing_catalogs",
    indices = [
        Index(value = ["url"], unique = true),
    ],
)
data class RoutingCatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val etag: String?,
    val lastSyncAt: Long?,
    val warningAcceptedAt: Long?,
    val cachedManifestJson: String?,
) {
    fun toDomain(cachedPresetCount: Int): RoutingCatalog =
        RoutingCatalog(
            id = id,
            name = name,
            url = url,
            enabled = enabled,
            etag = etag,
            lastSyncAt = lastSyncAt,
            warningAcceptedAt = warningAcceptedAt,
            cachedPresetCount = cachedPresetCount,
        )
}

data class RoutingPresetWithRules(
    @Embedded val preset: RoutingPresetEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "presetId",
        entity = RoutingRuleEntity::class,
    )
    val rules: List<RoutingRuleEntity>,
)

@Dao
interface ProfileDao {
    @Query("select * from profiles order by isActive desc, id desc")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("select * from profiles where isActive = 1 limit 1")
    fun observeActiveProfile(): Flow<ProfileEntity?>

    @Query("select * from profiles where id = :id limit 1")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("select * from profiles order by id asc")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("select * from profiles where isActive = 1 limit 1")
    suspend fun getActiveProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProfileEntity): Long

    @Query("update profiles set name = :name, protocolHint = :protocolHint, lastUpdatedAt = :lastUpdatedAt, lastEtag = :lastEtag where id = :id")
    suspend fun updateMetadata(
        id: Long,
        name: String,
        protocolHint: String,
        lastUpdatedAt: Long?,
        lastEtag: String?,
    )

    @Query(
        """
        update profiles
        set name = :name,
            secretRef = :secretRef,
            protocolHint = :protocolHint,
            lastUpdatedAt = :lastUpdatedAt,
            lastEtag = :lastEtag
        where id = :id
        """,
    )
    suspend fun updateMetadataAndSecretRef(
        id: Long,
        name: String,
        secretRef: String,
        protocolHint: String,
        lastUpdatedAt: Long?,
        lastEtag: String?,
    )

    @Query("update profiles set protocolHint = :protocolHint where id = :id")
    suspend fun updateProtocolHint(
        id: Long,
        protocolHint: String,
    )

    @Query("update profiles set isActive = 0")
    suspend fun clearActive()

    @Query("update profiles set isActive = 1 where id = :id")
    suspend fun setActive(id: Long)

    @Query("delete from profiles where id = :id")
    suspend fun delete(id: Long)

    @Query("select count(*) from profiles")
    suspend fun count(): Int

    @Query("select id from profiles order by id desc limit 1")
    suspend fun getMostRecentProfileId(): Long?
}

@Dao
interface RoutingPresetDao {
    @androidx.room.Transaction
    @Query("select * from routing_presets order by isActive desc, updatedAt desc, id desc")
    fun observePresets(): Flow<List<RoutingPresetWithRules>>

    @androidx.room.Transaction
    @Query("select * from routing_presets where isActive = 1 limit 1")
    fun observeActivePreset(): Flow<RoutingPresetWithRules?>

    @androidx.room.Transaction
    @Query("select * from routing_presets where id = :id limit 1")
    suspend fun getById(id: Long): RoutingPresetWithRules?

    @androidx.room.Transaction
    @Query("select * from routing_presets where isActive = 1 limit 1")
    suspend fun getActivePreset(): RoutingPresetWithRules?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutingPresetEntity): Long

    @Query(
        """
        update routing_presets
        set name = :name,
            source = :source,
            catalogId = :catalogId,
            overrideMode = :overrideMode,
            enabled = :enabled,
            updatedAt = :updatedAt
        where id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        source: String,
        catalogId: Long?,
        overrideMode: String,
        enabled: Boolean,
        updatedAt: Long,
    )

    @Query("update routing_presets set isActive = 0")
    suspend fun clearActive()

    @Query("update routing_presets set isActive = 1 where id = :id")
    suspend fun setActive(id: Long)

    @Query("delete from routing_presets where id = :id")
    suspend fun delete(id: Long)

    @Query("select count(*) from routing_presets")
    suspend fun count(): Int

    @Query("select id from routing_presets where id != :excludedId order by updatedAt desc, id desc limit 1")
    suspend fun getReplacementPresetId(excludedId: Long): Long?
}

@Dao
interface RoutingRuleDao {
    @Query("select * from routing_rules where presetId = :presetId order by `order` asc, id asc")
    fun observeByPreset(presetId: Long): Flow<List<RoutingRuleEntity>>

    @Query("select * from routing_rules where presetId = :presetId order by `order` asc, id asc")
    suspend fun getByPreset(presetId: Long): List<RoutingRuleEntity>

    @Query("select * from routing_rules where id = :id limit 1")
    suspend fun getById(id: Long): RoutingRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutingRuleEntity): Long

    @Query(
        """
        update routing_rules
        set name = :name,
            enabled = :enabled,
            `order` = :order,
            action = :action,
            matchDomains = :matchDomains,
            matchIpCidrs = :matchIpCidrs,
            matchPorts = :matchPorts,
            matchProtocols = :matchProtocols,
            matchNetworks = :matchNetworks
        where id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        enabled: Boolean,
        order: Int,
        action: String,
        matchDomains: List<String>,
        matchIpCidrs: List<String>,
        matchPorts: List<String>,
        matchProtocols: List<String>,
        matchNetworks: List<String>,
    )

    @Query("update routing_rules set `order` = :order where id = :id")
    suspend fun updateOrder(
        id: Long,
        order: Int,
    )

    @Query("delete from routing_rules where id = :id")
    suspend fun delete(id: Long)

    @Query("delete from routing_rules where presetId = :presetId")
    suspend fun deleteByPresetId(presetId: Long)

    @Query("select coalesce(max(`order`) + 1, 0) from routing_rules where presetId = :presetId")
    suspend fun nextOrder(presetId: Long): Int
}

@Dao
interface RoutingCatalogDao {
    @Query("select * from routing_catalogs order by enabled desc, lastSyncAt desc, id desc")
    fun observeCatalogs(): Flow<List<RoutingCatalogEntity>>

    @Query("select * from routing_catalogs where id = :id limit 1")
    suspend fun getById(id: Long): RoutingCatalogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutingCatalogEntity): Long

    @Query(
        """
        update routing_catalogs
        set name = :name,
            url = :url,
            enabled = :enabled,
            etag = :etag,
            lastSyncAt = :lastSyncAt,
            warningAcceptedAt = :warningAcceptedAt,
            cachedManifestJson = :cachedManifestJson
        where id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        url: String,
        enabled: Boolean,
        etag: String?,
        lastSyncAt: Long?,
        warningAcceptedAt: Long?,
        cachedManifestJson: String?,
    )

    @Query("delete from routing_catalogs where id = :id")
    suspend fun delete(id: Long)
}

class RoomValueConverters {
    private val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(ListSerializer, value)

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching { json.decodeFromString(ListSerializer, value) }.getOrDefault(emptyList())
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
    }
}

@Database(
    entities = [
        ProfileEntity::class,
        RoutingPresetEntity::class,
        RoutingRuleEntity::class,
        RoutingCatalogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RoomValueConverters::class)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun routingPresetDao(): RoutingPresetDao

    abstract fun routingRuleDao(): RoutingRuleDao

    abstract fun routingCatalogDao(): RoutingCatalogDao

    companion object {
        private const val LEGACY_DB_NAME = "foxhole.db"
        private const val SECURE_DB_NAME = "foxhole.secure.db"
        private val sqlCipherLoaded = AtomicBoolean(false)

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
                    db.execSQL("create index if not exists `index_routing_presets_catalogId` on `routing_presets` (`catalogId`)")
                    db.execSQL("create index if not exists `index_routing_presets_isActive` on `routing_presets` (`isActive`)")
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
                    db.execSQL("create index if not exists `index_routing_rules_presetId` on `routing_rules` (`presetId`)")
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
                    db.execSQL("create unique index if not exists `index_routing_catalogs_url` on `routing_catalogs` (`url`)")
                }
            }

        fun create(context: Context): ProfileDatabase {
            val appContext = context.applicationContext
            val passphrase = DatabasePassphraseStore(appContext).readOrCreate()
            val database =
                Room.databaseBuilder(
                    appContext,
                    ProfileDatabase::class.java,
                    SECURE_DB_NAME,
                ).openHelperFactory(
                    SupportOpenHelperFactory(passphrase),
                ).addMigrations(
                    MIGRATION_1_2,
                ).fallbackToDestructiveMigration(false).build()
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

        private fun migrateLegacyPlaintextDatabase(
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
            check(unverifiedLegacyRows.isEmpty()) {
                "legacy plaintext profile migration was not verified for ids=${unverifiedLegacyRows.joinToString { it.id.toString() }}"
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
            check(failedDeletes.isEmpty()) {
                "legacy plaintext database migrated but could not be removed: ${failedDeletes.joinToString { it.name }}"
            }
        }

        private class DatabasePassphraseStore(
            context: Context,
        ) {
            private val appContext = context.applicationContext
            private val passphraseFile = File(appContext.filesDir, "keys/profile-db.passphrase")
            private val fileCipher = AndroidKeystoreFileCipher("foxhole.profile.db.passphrase")

            fun readOrCreate(): ByteArray {
                ensureSqlCipherLoaded()
                passphraseFile.parentFile?.mkdirs()
                if (passphraseFile.exists()) {
                    return fileCipher.readBytesMigratingLegacy(appContext, passphraseFile)
                }
                val created = ByteArray(32).also(SecureRandom()::nextBytes)
                fileCipher.writeBytesAtomic(passphraseFile, created)
                return created
            }
        }
    }
}
