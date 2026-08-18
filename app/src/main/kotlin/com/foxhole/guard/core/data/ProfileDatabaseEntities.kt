package com.foxhole.guard.core.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverters
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppBaseline
import com.foxhole.core.model.AppNetworkPresence
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.I2pTrafficTotals
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.RoutingCatalog
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingPresetOverrideMode
import com.foxhole.core.model.RoutingPresetSource
import com.foxhole.core.model.RoutingRule
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.TrafficBaseline
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import com.foxhole.core.model.storedProfileSourceType
import com.foxhole.core.model.storedProtocolHint
import java.util.Calendar

// Room @Entity row models + query projection data classes for ProfileDatabase.
// Split out of ProfileDatabase.kt (behaviour-preserving); the DAOs and @Database
// reference these same-package.

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
            sourceType = storedProfileSourceType(sourceType),
            secretRef = secretRef,
            protocolHint = storedProtocolHint(protocolHint),
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
            action = RoutingRuleAction.fromStoredName(action),
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
    val blockedDnsDomains: Map<String, Long>,
    // Keyed by DnsFilterCategory.name; stored as a plain string map to reuse the existing
    // converter (unknown keys from newer app versions degrade to "uncategorized" silently).
    @ColumnInfo(defaultValue = "{}") val blockedDnsByCategory: Map<String, Long> = emptyMap(),
    @ColumnInfo(defaultValue = "{}") val blockedDnsApps: Map<String, Long> = emptyMap(),
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
            blockedDnsDomains = blockedDnsDomains,
            blockedDnsByCategory =
            blockedDnsByCategory.entries.mapNotNull { (name, count) ->
                DnsFilterCategory.entries.firstOrNull { category -> category.name == name }
                    ?.let { category -> category to count }
            }.toMap(),
            blockedDnsApps = blockedDnsApps,
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
                blockedDnsDomains = window.blockedDnsDomains,
                blockedDnsByCategory =
                window.blockedDnsByCategory.entries.associate { (category, count) -> category.name to count },
                blockedDnsApps = window.blockedDnsApps,
            )
    }
}

@Entity(
    tableName = "app_traffic_windows",
    indices = [
        Index("startedAtMs"),
        Index("packageName"),
        Index(value = ["packageName", "networkType", "hourBucket", "startedAtMs", "id"]),
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

@Entity(
    tableName = "network_activity_events",
    indices = [
        Index("timestampMs"),
        Index("profileId"),
        Index("sessionId"),
        Index("remoteHost"),
    ],
)
@TypeConverters(RoomValueConverters::class)
data class NetworkActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val packageNames: List<String>,
    val protocol: String,
    val remoteHost: String,
    val remotePort: Int?,
    val countryCode: String?,
    val bytesRx: Long,
    val bytesTx: Long,
    val profileId: Long?,
    val sessionId: String?,
) {
    fun toDomain(): NetworkActivityEvent =
        NetworkActivityEvent(
            id = id,
            timestampMs = timestampMs,
            packageNames = packageNames,
            protocol = protocol,
            remoteHost = remoteHost,
            remotePort = remotePort,
            countryCode = countryCode,
            bytesRx = bytesRx,
            bytesTx = bytesTx,
            profileId = profileId,
            sessionId = sessionId,
        )

    companion object {
        fun from(event: NetworkActivityEvent): NetworkActivityEventEntity =
            NetworkActivityEventEntity(
                id = event.id,
                timestampMs = event.timestampMs,
                packageNames = event.packageNames,
                protocol = event.protocol,
                remoteHost = event.remoteHost,
                remotePort = event.remotePort,
                countryCode = event.countryCode,
                bytesRx = event.bytesRx,
                bytesTx = event.bytesTx,
                profileId = event.profileId,
                sessionId = event.sessionId,
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
) {
    fun toDomain(): ProtocolMetricEvent =
        ProtocolMetricEvent(
            timestampMs = timestampMs,
            profileId = profileId,
            optionId = optionId,
            protocol = protocol,
            kind = enumValueOrDefault(event, ProtocolMetricEventKind.PROBE_SUCCESS),
            latencyMs = latencyMs,
            reasonCode = reasonCode,
        )

    companion object {
        fun from(event: ProtocolMetricEvent): ProtocolMetricEventEntity =
            ProtocolMetricEventEntity(
                timestampMs = event.timestampMs,
                profileId = event.profileId,
                optionId = event.optionId,
                protocol = event.protocol,
                event = event.kind.name,
                latencyMs = event.latencyMs,
                reasonCode = event.reasonCode,
            )
    }
}

// Long-horizon anomaly memory: tiny aggregates that deliberately outlive statistics retention so
// "ever seen" checks (new-country novelty, dormant-app wake-ups) stay correct after cleanup.
// The privacy clear paths delete them together with the windowed data.

@Entity(tableName = "seen_destination_countries")
data class SeenDestinationCountryEntity(
    @PrimaryKey val country: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val totalBytes: Long,
)

@Entity(tableName = "app_network_presence")
data class AppNetworkPresenceEntity(
    @PrimaryKey val packageName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val windowCount: Long,
) {
    fun toDomain(): AppNetworkPresence =
        AppNetworkPresence(
            packageName = packageName,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            windowCount = windowCount,
        )
}

@Entity(tableName = "anomaly_counters")
data class AnomalyCounterEntity(
    @PrimaryKey val counterKey: String,
    val value: Long,
)

/**
 * One wall-clock hour of I2P byte accounting. Two counters, no timestamps beyond the bucket's own
 * start and nothing that identifies a peer or a destination — which is why an hour of history costs
 * a single tiny row and thirty days of it stays a few hundred.
 *
 * `ownBytes` is the historical column name for i2pd's received + sent network total;
 * `transitBytes` is the reported subset forwarded for other people while relaying. They are stored
 * and printed apart, never summed.
 */
@Entity(tableName = "i2p_traffic_buckets")
data class I2pTrafficBucketEntity(
    @PrimaryKey val hourStartMs: Long,
    val ownBytes: Long,
    val transitBytes: Long,
) {
    fun toDomain(): I2pTrafficBucket =
        I2pTrafficBucket(
            hourStartMs = hourStartMs,
            totals = I2pTrafficTotals(ownBytes = ownBytes, transitBytes = transitBytes),
        )
}

/**
 * The lifetime I2P aggregate: exactly one row, two counters, no time dimension at all. It is kept
 * beside the buckets rather than summed from them because the buckets are pruned at thirty days,
 * and "all time" must not shrink when they are.
 */
@Entity(tableName = "i2p_traffic_totals")
data class I2pTrafficTotalEntity(
    @PrimaryKey val id: Int = I2P_TRAFFIC_TOTALS_ROW_ID,
    val ownBytes: Long,
    val transitBytes: Long,
) {
    fun toDomain(): I2pTrafficTotals = I2pTrafficTotals(ownBytes = ownBytes, transitBytes = transitBytes)
}

// The single row's key. A fixed id keeps the accumulate-in-place update a one-liner and makes a
// second lifetime row structurally impossible.
internal const val I2P_TRAFFIC_TOTALS_ROW_ID = 0

// A user-added HTML5 web app: origin URL, cached name and icon, and the watchdog's notification
// counter that feeds the screen and widget badges.
@Entity(tableName = "web_apps")
data class WebAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String,
    // Icon path relative to filesDir; null means the letter placeholder.
    val iconPath: String?,
    val sortOrder: Int,
    // Stored as an enum token so unknown values from a future build can fail back to DEFAULT.
    val route: String = com.foxhole.core.model.WebAppRoute.DEFAULT.name,
    val badgeCount: Int = 0,
    val lastPolledAtMs: Long? = null,
    val createdAtMs: Long,
) {
    fun webAppRoute(): com.foxhole.core.model.WebAppRoute = com.foxhole.core.model.storedWebAppRoute(route)
}
