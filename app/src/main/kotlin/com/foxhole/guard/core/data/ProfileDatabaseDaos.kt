package com.foxhole.guard.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Room DAOs for ProfileDatabase (profiles, routing presets/rules/catalog, anomaly/stats).
// Split out of ProfileDatabase.kt (behaviour-preserving); the @Database references them same-package.

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

    @Query(
        "update profiles set name = :name, protocolHint = :protocolHint, lastUpdatedAt = :lastUpdatedAt, lastEtag = :lastEtag where id = :id"
    )
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

    @Query("update profiles set name = :name where id = :id")
    suspend fun updateName(
        id: Long,
        name: String,
    )

    @Query("update profiles set sourceType = :sourceType where id = :id")
    suspend fun updateSourceType(
        id: Long,
        sourceType: String,
    )

    @Query("update profiles set isActive = 0")
    suspend fun clearActive()

    @Query("update profiles set isActive = 1 where id = :id")
    suspend fun setActive(id: Long): Int

    @Query("delete from profiles where id = :id")
    suspend fun delete(id: Long)

    @Query("delete from profiles")
    suspend fun deleteAll()

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
    suspend fun insertNetworkActivityEvent(entity: NetworkActivityEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetworkActivityEvents(entities: List<NetworkActivityEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrafficBaseline(entity: TrafficBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppBaseline(entity: AppBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnomalyEvent(entity: AnomalyEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProtocolMetricEvent(entity: ProtocolMetricEventEntity): Long

    @Query("delete from protocol_metric_events where timestampMs < :cutoff")
    suspend fun deleteProtocolMetricEventsBefore(cutoff: Long): Int

    @Query("delete from protocol_metric_events")
    suspend fun deleteAllProtocolMetricEvents(): Int

    @Query("select * from anomaly_events where createdAtMs >= :cutoff order by createdAtMs desc, id desc")
    fun observeAnomalyEvents(cutoff: Long): Flow<List<AnomalyEventEntity>>

    @Query("select * from anomaly_events where createdAtMs >= :cutoff order by createdAtMs desc, id desc")
    suspend fun getAnomalyEvents(cutoff: Long): List<AnomalyEventEntity>

    @Query(
        "select * from protocol_metric_events where timestampMs >= :startMs and timestampMs < :endMs order by timestampMs asc, id asc"
    )
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

    // Bounded on purpose: under the "forever" retention the cutoff is 0, so an unlimited select
    // would stream the entire table into memory on every change.
    @Query("select * from app_traffic_windows where startedAtMs >= :cutoff order by startedAtMs desc limit :limit")
    fun observeRecentAppTrafficWindows(
        cutoff: Long,
        limit: Int,
    ): Flow<List<AppTrafficWindowEntity>>

    @Query("select * from traffic_windows where startedAtMs >= :cutoff order by startedAtMs desc limit :limit")
    fun observeRecentTrafficWindows(
        cutoff: Long,
        limit: Int,
    ): Flow<List<TrafficWindowEntity>>

    @Query(
        """
        select * from network_activity_events
        where timestampMs >= :cutoff
        order by timestampMs desc, id desc
        limit :limit
        """,
    )
    fun observeRecentNetworkActivityEvents(
        cutoff: Long,
        limit: Int,
    ): Flow<List<NetworkActivityEventEntity>>

    @Query("select * from traffic_baselines where baselineKey = :key limit 1")
    suspend fun getTrafficBaseline(key: String): TrafficBaselineEntity?

    @Query("select * from app_baselines where baselineKey = :key limit 1")
    suspend fun getAppBaseline(key: String): AppBaselineEntity?

    @Query("select * from app_baselines where baselineKey in (:keys)")
    suspend fun getAppBaselines(keys: List<String>): List<AppBaselineEntity>

    @Query("select * from seen_destination_countries")
    suspend fun getSeenDestinationCountries(): List<SeenDestinationCountryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeenDestinationCountries(entities: List<SeenDestinationCountryEntity>)

    @Query("select * from app_network_presence where packageName in (:packageNames)")
    suspend fun getAppNetworkPresence(packageNames: List<String>): List<AppNetworkPresenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppNetworkPresence(entities: List<AppNetworkPresenceEntity>)

    @Query("select value from anomaly_counters where counterKey = :key limit 1")
    suspend fun getAnomalyCounter(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnomalyCounter(entity: AnomalyCounterEntity)

    @Query(
        """
        select * from anomaly_events
        where type = :type
            and ((:packageName is null and packageName is null) or packageName = :packageName)
            and createdAtMs >= :since
        order by createdAtMs desc, id desc
        limit 1
        """,
    )
    suspend fun latestAnomalyEventSince(
        type: String,
        packageName: String?,
        since: Long,
    ): AnomalyEventEntity?

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

    // Row caps: the "forever" retention has no time cutoff, so age-based pruning alone lets these
    // tables (and the unbounded observe queries that feed the UI) grow without limit.
    @Query(
        """
        delete from traffic_windows where id not in (
            select id from traffic_windows order by startedAtMs desc limit :keep
        )
        """,
    )
    suspend fun trimTrafficWindowsTo(keep: Int)

    @Query(
        """
        delete from app_traffic_windows where id not in (
            select id from app_traffic_windows order by startedAtMs desc limit :keep
        )
        """,
    )
    suspend fun trimAppTrafficWindowsTo(keep: Int)

    @Query(
        """
        delete from network_activity_events where id not in (
            select id from network_activity_events order by timestampMs desc limit :keep
        )
        """,
    )
    suspend fun trimNetworkActivityEventsTo(keep: Int)

    @Query(
        """
        delete from protocol_metric_events where id not in (
            select id from protocol_metric_events order by timestampMs desc limit :keep
        )
        """,
    )
    suspend fun trimProtocolMetricEventsTo(keep: Int)

    @Query("delete from traffic_baselines")
    suspend fun deleteTrafficBaselines()

    @Query("delete from app_baselines")
    suspend fun deleteAppBaselines()

    @Query("delete from anomaly_events where packageName is not null")
    suspend fun deleteAppAnomalyEvents()

    @Query("delete from seen_destination_countries")
    suspend fun deleteSeenDestinationCountries()

    @Query("delete from app_network_presence")
    suspend fun deleteAppNetworkPresence()

    @Query("delete from anomaly_counters")
    suspend fun deleteAnomalyCounters()

    @Query("delete from network_activity_events where timestampMs < :cutoff")
    suspend fun deleteNetworkActivityEventsBefore(cutoff: Long)

    @Query("delete from network_activity_events")
    suspend fun deleteNetworkActivityEvents()

    @Query("delete from anomaly_events where createdAtMs < :cutoff")
    suspend fun deleteAnomalyEventsBefore(cutoff: Long)
}

@Dao
interface WebAppDao {
    @Query("select * from web_apps order by sortOrder asc, id asc")
    fun observeAll(): Flow<List<WebAppEntity>>

    @Query("select * from web_apps order by sortOrder asc, id asc")
    suspend fun listAll(): List<WebAppEntity>

    @Query("select * from web_apps where id = :id")
    suspend fun byId(id: Long): WebAppEntity?

    @Insert
    suspend fun insert(entity: WebAppEntity): Long

    @Query("update web_apps set name = :name where id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("update web_apps set iconPath = :iconPath where id = :id")
    suspend fun updateIconPath(id: Long, iconPath: String?)

    @Query("delete from web_apps where id = :id")
    suspend fun delete(id: Long)

    @Query("update web_apps set badgeCount = :count, lastPolledAtMs = :polledAtMs where id = :id")
    suspend fun updateBadge(id: Long, count: Int, polledAtMs: Long)

    @Query("update web_apps set badgeCount = 0 where id = :id")
    suspend fun resetBadge(id: Long)
}
