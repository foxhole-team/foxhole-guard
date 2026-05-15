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
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppBaseline
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.TrafficBaseline
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.readBytesMigratingLegacy
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom
import java.util.Calendar
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

fun anomalyHourBucket(timestampMs: Long): Int =
    Calendar.getInstance()
        .apply { timeInMillis = timestampMs.coerceAtLeast(0L) }
        .get(Calendar.HOUR_OF_DAY)
        .coerceIn(0, 23)

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String?,
    defaultValue: T,
): T =
    value
        ?.let { raw -> runCatching { enumValueOf<T>(raw) }.getOrNull() }
        ?: defaultValue

@Entity(
    tableName = "traffic_windows",
    indices = [
        Index("startedAtMs"),
        Index(value = ["profileId", "protocol", "networkType", "hourBucket"]),
    ],
)
@TypeConverters(RoomValueConverters::class)
data class TrafficWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val durationSec: Int,
    val networkType: String,
    val vpnMode: String,
    val profileId: String?,
    val protocol: String?,
    val hourBucket: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val blockedDns: Int,
    val allowedDns: Int,
    val reconnects: Int,
    val latencyMs: Int?,
    val destinationCountries: Map<String, Long>,
) {
    fun toDomain(): TrafficWindow =
        TrafficWindow(
            startedAtMs = startedAtMs,
            durationSec = durationSec,
            networkType = enumValueOrDefault(networkType, NetworkType.UNKNOWN),
            vpnMode = enumValueOrDefault(vpnMode, VpnMode.NORMAL),
            profileId = profileId,
            protocol = protocol,
            rxBytes = rxBytes,
            txBytes = txBytes,
            blockedDns = blockedDns,
            allowedDns = allowedDns,
            reconnects = reconnects,
            latencyMs = latencyMs,
            destinationCountries = destinationCountries,
        )

    companion object {
        fun from(window: TrafficWindow): TrafficWindowEntity =
            TrafficWindowEntity(
                startedAtMs = window.startedAtMs,
                durationSec = window.durationSec,
                networkType = window.networkType.name,
                vpnMode = window.vpnMode.name,
                profileId = window.profileId,
                protocol = window.protocol,
                hourBucket = anomalyHourBucket(window.startedAtMs),
                rxBytes = window.rxBytes,
                txBytes = window.txBytes,
                blockedDns = window.blockedDns,
                allowedDns = window.allowedDns,
                reconnects = window.reconnects,
                latencyMs = window.latencyMs,
                destinationCountries = window.destinationCountries,
            )
    }
}

@Entity(
    tableName = "app_traffic_windows",
    indices = [
        Index("startedAtMs"),
        Index("packageName"),
        Index(value = ["packageName", "networkType", "hourBucket"]),
    ],
)
data class AppTrafficWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val uid: Int,
    val startedAtMs: Long,
    val durationSec: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val foreground: Boolean?,
    val networkType: String,
    val hourBucket: Int,
) {
    fun toDomain(): AppTrafficWindow =
        AppTrafficWindow(
            packageName = packageName,
            startedAtMs = startedAtMs,
            durationSec = durationSec,
            rxBytes = rxBytes,
            txBytes = txBytes,
            foreground = foreground,
            networkType = enumValueOrDefault(networkType, NetworkType.UNKNOWN),
            uid = uid,
        )

    companion object {
        fun from(window: AppTrafficWindow): AppTrafficWindowEntity =
            AppTrafficWindowEntity(
                packageName = window.packageName,
                uid = window.uid,
                startedAtMs = window.startedAtMs,
                durationSec = window.durationSec,
                rxBytes = window.rxBytes,
                txBytes = window.txBytes,
                foreground = window.foreground,
                networkType = window.networkType.name,
                hourBucket = anomalyHourBucket(window.startedAtMs),
            )
    }
}

@Entity(tableName = "traffic_baselines")
data class TrafficBaselineEntity(
    @PrimaryKey val baselineKey: String,
    val profileId: String?,
    val protocol: String?,
    val networkType: String,
    val hourBucket: Int,
    val metric: String,
    val median: Double,
    val mad: Double,
    val ewma: Double,
    val ewmad: Double,
    val sampleCount: Int,
    val lastUpdatedAt: Long,
) {
    fun toDomain(): TrafficBaseline =
        TrafficBaseline(
            key = baselineKey,
            profileId = profileId,
            protocol = protocol,
            networkType = enumValueOrDefault(networkType, NetworkType.UNKNOWN),
            hourBucket = hourBucket,
            metric = metric,
            median = median,
            mad = mad,
            ewma = ewma,
            ewmad = ewmad,
            sampleCount = sampleCount,
            lastUpdatedAt = lastUpdatedAt,
        )

    companion object {
        fun from(baseline: TrafficBaseline): TrafficBaselineEntity =
            TrafficBaselineEntity(
                baselineKey = baseline.key,
                profileId = baseline.profileId,
                protocol = baseline.protocol,
                networkType = baseline.networkType.name,
                hourBucket = baseline.hourBucket,
                metric = baseline.metric,
                median = baseline.median,
                mad = baseline.mad,
                ewma = baseline.ewma,
                ewmad = baseline.ewmad,
                sampleCount = baseline.sampleCount,
                lastUpdatedAt = baseline.lastUpdatedAt,
            )
    }
}

@Entity(
    tableName = "app_baselines",
    indices = [
        Index("packageName"),
        Index(value = ["packageName", "networkType", "hourBucket"]),
    ],
)
data class AppBaselineEntity(
    @PrimaryKey val baselineKey: String,
    val packageName: String,
    val profileId: String?,
    val protocol: String?,
    val networkType: String,
    val hourBucket: Int,
    val metric: String,
    val median: Double,
    val mad: Double,
    val ewma: Double,
    val ewmad: Double,
    val sampleCount: Int,
    val lastUpdatedAt: Long,
) {
    fun toDomain(): AppBaseline =
        AppBaseline(
            key = baselineKey,
            packageName = packageName,
            profileId = profileId,
            protocol = protocol,
            networkType = enumValueOrDefault(networkType, NetworkType.UNKNOWN),
            hourBucket = hourBucket,
            metric = metric,
            median = median,
            mad = mad,
            ewma = ewma,
            ewmad = ewmad,
            sampleCount = sampleCount,
            lastUpdatedAt = lastUpdatedAt,
        )

    companion object {
        fun from(baseline: AppBaseline): AppBaselineEntity =
            AppBaselineEntity(
                baselineKey = baseline.key,
                packageName = baseline.packageName,
                profileId = baseline.profileId,
                protocol = baseline.protocol,
                networkType = baseline.networkType.name,
                hourBucket = baseline.hourBucket,
                metric = baseline.metric,
                median = baseline.median,
                mad = baseline.mad,
                ewma = baseline.ewma,
                ewmad = baseline.ewmad,
                sampleCount = baseline.sampleCount,
                lastUpdatedAt = baseline.lastUpdatedAt,
            )
    }
}

@Entity(
    tableName = "anomaly_events",
    indices = [
        Index("createdAtMs"),
        Index("packageName"),
        Index(value = ["type", "packageName", "createdAtMs"]),
    ],
)
@TypeConverters(RoomValueConverters::class)
data class AnomalyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long,
    val type: String,
    val severity: String,
    val score: Int,
    val reason: String,
    val evidence: Map<String, String>,
    val packageName: String?,
    val profileId: String?,
    val protocol: String?,
    val notificationShown: Boolean,
) {
    fun toDomain(): AnomalyEvent =
        AnomalyEvent(
            id = id,
            createdAtMs = createdAtMs,
            type = enumValueOrDefault(type, AnomalyType.TOTAL_TRAFFIC_SPIKE),
            severity = enumValueOrDefault(severity, AnomalySeverity.SILENT),
            score = score,
            reason = reason,
            evidence = evidence,
            packageName = packageName,
            profileId = profileId,
            protocol = protocol,
            notificationShown = notificationShown,
        )

    companion object {
        fun from(event: AnomalyEvent): AnomalyEventEntity =
            AnomalyEventEntity(
                id = event.id,
                createdAtMs = event.createdAtMs,
                type = event.type.name,
                severity = event.severity.name,
                score = event.score,
                reason = event.reason,
                evidence = event.evidence,
                packageName = event.packageName,
                profileId = event.profileId,
                protocol = event.protocol,
                notificationShown = event.notificationShown,
            )
    }
}

@Entity(
    tableName = "runtime_timeline_events",
    indices = [
        Index("timestampMs"),
        Index("generationId"),
        Index("sessionId"),
        Index(value = ["owner", "stage", "status", "timestampMs"]),
    ],
)
@TypeConverters(RoomValueConverters::class)
data class RuntimeTimelineEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val generationId: Long,
    val sessionId: String?,
    val owner: String,
    val mode: String,
    val stage: String,
    val status: String,
    val durationMs: Long?,
    val profileId: Long?,
    val protocol: String?,
    val details: Map<String, String>,
)

@Entity(
    tableName = "protocol_metric_events",
    indices = [
        Index("timestampMs"),
        Index("profileId"),
        Index(value = ["protocol", "event", "timestampMs"]),
    ],
)
data class ProtocolMetricEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val profileId: Long,
    val optionId: String?,
    val protocol: String,
    val event: String,
    val latencyMs: Long?,
    val reasonCode: String?,
)

data class TrafficBucketEntity(
    val bucketStartMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val blockedDns: Int,
    val allowedDns: Int,
    val reconnects: Int,
    val avgLatencyMs: Double?,
    val sampleCount: Int,
)

data class AppTrafficBucketEntity(
    val bucketStartMs: Long,
    val packageName: String,
    val rxBytes: Long,
    val txBytes: Long,
    val sampleCount: Int,
)

data class AnomalyBucketEntity(
    val bucketStartMs: Long,
    val eventCount: Int,
    val highCount: Int,
    val notificationCount: Int,
    val maxScore: Int,
    val avgScore: Double?,
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

@Dao
interface AnomalyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrafficWindow(entity: TrafficWindowEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppTrafficWindows(entities: List<AppTrafficWindowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrafficBaseline(entity: TrafficBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppBaseline(entity: AppBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnomalyEvent(entity: AnomalyEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuntimeTimelineEvent(entity: RuntimeTimelineEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProtocolMetricEvent(entity: ProtocolMetricEventEntity): Long

    @Query("select * from anomaly_events where createdAtMs >= :cutoff order by createdAtMs desc, id desc")
    fun observeAnomalyEvents(cutoff: Long): Flow<List<AnomalyEventEntity>>

    @Query("select * from anomaly_events where createdAtMs >= :cutoff order by createdAtMs desc, id desc")
    suspend fun getAnomalyEvents(cutoff: Long): List<AnomalyEventEntity>

    @Query(
        """
        select
            :startMs + ((startedAtMs - :startMs) / :bucketMs) * :bucketMs as bucketStartMs,
            sum(rxBytes) as rxBytes,
            sum(txBytes) as txBytes,
            sum(blockedDns) as blockedDns,
            sum(allowedDns) as allowedDns,
            sum(reconnects) as reconnects,
            avg(latencyMs) as avgLatencyMs,
            count(*) as sampleCount
        from traffic_windows
        where startedAtMs >= :startMs
            and startedAtMs < :endMs
            and (:profileId is null or profileId = :profileId)
            and (:protocol is null or protocol = :protocol)
            and (:networkType is null or networkType = :networkType)
        group by bucketStartMs
        order by bucketStartMs asc
        """,
    )
    fun observeTrafficBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
        profileId: String?,
        protocol: String?,
        networkType: String?,
    ): Flow<List<TrafficBucketEntity>>

    @Query(
        """
        select
            :startMs + ((startedAtMs - :startMs) / :bucketMs) * :bucketMs as bucketStartMs,
            packageName,
            sum(rxBytes) as rxBytes,
            sum(txBytes) as txBytes,
            count(*) as sampleCount
        from app_traffic_windows
        where startedAtMs >= :startMs
            and startedAtMs < :endMs
            and (:packageName is null or packageName = :packageName)
            and (:networkType is null or networkType = :networkType)
        group by bucketStartMs, packageName
        order by bucketStartMs asc, packageName asc
        """,
    )
    fun observeAppTrafficBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
        packageName: String?,
        networkType: String?,
    ): Flow<List<AppTrafficBucketEntity>>

    @Query(
        """
        select
            :startMs + ((createdAtMs - :startMs) / :bucketMs) * :bucketMs as bucketStartMs,
            count(*) as eventCount,
            sum(case when severity = 'HIGH' then 1 else 0 end) as highCount,
            sum(case when notificationShown = 1 then 1 else 0 end) as notificationCount,
            max(score) as maxScore,
            avg(score) as avgScore
        from anomaly_events
        where createdAtMs >= :startMs and createdAtMs < :endMs
        group by bucketStartMs
        order by bucketStartMs asc
        """,
    )
    fun observeAnomalyBuckets(
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): Flow<List<AnomalyBucketEntity>>

    @Query("select * from runtime_timeline_events where timestampMs >= :startMs and timestampMs < :endMs order by timestampMs asc, id asc")
    fun observeRuntimeTimelineEvents(
        startMs: Long,
        endMs: Long,
    ): Flow<List<RuntimeTimelineEventEntity>>

    @Query("select * from protocol_metric_events where timestampMs >= :startMs and timestampMs < :endMs order by timestampMs asc, id asc")
    fun observeProtocolMetricEvents(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ProtocolMetricEventEntity>>

    @Query(
        """
        select * from traffic_windows
        where ((:profileId is null and profileId is null) or profileId = :profileId)
            and ((:protocol is null and protocol is null) or protocol = :protocol)
            and networkType = :networkType
            and hourBucket = :hourBucket
        order by startedAtMs desc
        limit :limit
        """,
    )
    suspend fun recentTrafficWindows(
        profileId: String?,
        protocol: String?,
        networkType: String,
        hourBucket: Int,
        limit: Int,
    ): List<TrafficWindowEntity>

    @Query(
        """
        select * from app_traffic_windows
        where packageName = :packageName
            and networkType = :networkType
            and hourBucket = :hourBucket
        order by startedAtMs desc
        limit :limit
        """,
    )
    suspend fun recentAppTrafficWindows(
        packageName: String,
        networkType: String,
        hourBucket: Int,
        limit: Int,
    ): List<AppTrafficWindowEntity>

    @Query(
        """
        select * from app_traffic_windows as outer_window
        where outer_window.packageName in (:packageNames)
            and outer_window.networkType = :networkType
            and outer_window.hourBucket = :hourBucket
            and outer_window.id in (
                select recent.id from app_traffic_windows as recent
                where recent.packageName = outer_window.packageName
                    and recent.networkType = outer_window.networkType
                    and recent.hourBucket = outer_window.hourBucket
                order by recent.startedAtMs desc, recent.id desc
                limit :limit
            )
        order by outer_window.packageName asc, outer_window.startedAtMs desc, outer_window.id desc
        """,
    )
    suspend fun recentAppTrafficWindowsForPackages(
        packageNames: List<String>,
        networkType: String,
        hourBucket: Int,
        limit: Int,
    ): List<AppTrafficWindowEntity>

    @Query("select * from app_traffic_windows where startedAtMs >= :cutoff order by startedAtMs desc")
    fun observeRecentAppTrafficWindows(cutoff: Long): Flow<List<AppTrafficWindowEntity>>

    @Query("select * from traffic_windows where startedAtMs >= :cutoff order by startedAtMs desc")
    fun observeRecentTrafficWindows(cutoff: Long): Flow<List<TrafficWindowEntity>>

    @Query("select * from traffic_baselines where baselineKey = :key limit 1")
    suspend fun getTrafficBaseline(key: String): TrafficBaselineEntity?

    @Query("select * from app_baselines where baselineKey = :key limit 1")
    suspend fun getAppBaseline(key: String): AppBaselineEntity?

    @Query(
        """
        select count(*) from anomaly_events
        where type = :type
            and ((:packageName is null and packageName is null) or packageName = :packageName)
            and createdAtMs >= :since
            and notificationShown = 1
        """,
    )
    suspend fun notificationCountSince(
        type: String,
        packageName: String?,
        since: Long,
    ): Int

    @Query("delete from traffic_windows where startedAtMs < :cutoff")
    suspend fun deleteTrafficWindowsBefore(cutoff: Long)

    @Query("delete from app_traffic_windows where startedAtMs < :cutoff")
    suspend fun deleteAppTrafficWindowsBefore(cutoff: Long)

    @Query("delete from anomaly_events where createdAtMs < :cutoff")
    suspend fun deleteAnomalyEventsBefore(cutoff: Long)
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

    @TypeConverter
    fun fromStringLongMap(value: Map<String, Long>): String = json.encodeToString(StringLongMapSerializer, value)

    @TypeConverter
    fun toStringLongMap(value: String?): Map<String, Long> {
        if (value.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching { json.decodeFromString(StringLongMapSerializer, value) }.getOrDefault(emptyMap())
    }

    @TypeConverter
    fun fromStringStringMap(value: Map<String, String>): String = json.encodeToString(StringStringMapSerializer, value)

    @TypeConverter
    fun toStringStringMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching { json.decodeFromString(StringStringMapSerializer, value) }.getOrDefault(emptyMap())
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
        val StringLongMapSerializer =
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<Long>(),
            )
        val StringStringMapSerializer =
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<String>(),
            )
    }
}

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
        RuntimeTimelineEventEntity::class,
        ProtocolMetricEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(RoomValueConverters::class)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun routingPresetDao(): RoutingPresetDao

    abstract fun routingRuleDao(): RoutingRuleDao

    abstract fun routingCatalogDao(): RoutingCatalogDao

    abstract fun anomalyDao(): AnomalyDao

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

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL("create index if not exists `index_traffic_windows_startedAtMs` on `traffic_windows` (`startedAtMs`)")
                    db.execSQL("create index if not exists `index_traffic_windows_profileId_protocol_networkType_hourBucket` on `traffic_windows` (`profileId`, `protocol`, `networkType`, `hourBucket`)")
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
                    db.execSQL("create index if not exists `index_app_traffic_windows_startedAtMs` on `app_traffic_windows` (`startedAtMs`)")
                    db.execSQL("create index if not exists `index_app_traffic_windows_packageName` on `app_traffic_windows` (`packageName`)")
                    db.execSQL("create index if not exists `index_app_traffic_windows_packageName_networkType_hourBucket` on `app_traffic_windows` (`packageName`, `networkType`, `hourBucket`)")
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
                    db.execSQL("create index if not exists `index_app_baselines_packageName` on `app_baselines` (`packageName`)")
                    db.execSQL("create index if not exists `index_app_baselines_packageName_networkType_hourBucket` on `app_baselines` (`packageName`, `networkType`, `hourBucket`)")
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
                    db.execSQL("create index if not exists `index_anomaly_events_createdAtMs` on `anomaly_events` (`createdAtMs`)")
                    db.execSQL("create index if not exists `index_anomaly_events_packageName` on `anomaly_events` (`packageName`)")
                    db.execSQL("create index if not exists `index_anomaly_events_type_packageName_createdAtMs` on `anomaly_events` (`type`, `packageName`, `createdAtMs`)")
                }
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
                    db.execSQL("create index if not exists `index_runtime_timeline_events_timestampMs` on `runtime_timeline_events` (`timestampMs`)")
                    db.execSQL("create index if not exists `index_runtime_timeline_events_generationId` on `runtime_timeline_events` (`generationId`)")
                    db.execSQL("create index if not exists `index_runtime_timeline_events_sessionId` on `runtime_timeline_events` (`sessionId`)")
                    db.execSQL("create index if not exists `index_runtime_timeline_events_owner_stage_status_timestampMs` on `runtime_timeline_events` (`owner`, `stage`, `status`, `timestampMs`)")
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
                    db.execSQL("create index if not exists `index_protocol_metric_events_timestampMs` on `protocol_metric_events` (`timestampMs`)")
                    db.execSQL("create index if not exists `index_protocol_metric_events_profileId` on `protocol_metric_events` (`profileId`)")
                    db.execSQL("create index if not exists `index_protocol_metric_events_protocol_event_timestampMs` on `protocol_metric_events` (`protocol`, `event`, `timestampMs`)")
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
                    MIGRATION_2_3,
                    MIGRATION_3_4,
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
