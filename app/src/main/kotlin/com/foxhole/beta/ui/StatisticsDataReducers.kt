@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.UsageStatsAccess
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.InstalledAppInventoryChange
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.OverallStatisticsUiItem
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileComparisonSideUiItem
import com.foxhole.beta.core.model.ProfileComparisonUiItem
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileTrafficUiItem
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProtocolQuality
import com.foxhole.beta.core.model.ProtocolStatisticsUiItem
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.StatisticsRange
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TransportStatisticsUiItem
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import com.foxhole.beta.ui.statistics.charts.AnimatedProgressRing
import com.foxhole.beta.ui.statistics.charts.AnimatedSegmentDonutChart
import com.foxhole.beta.ui.statistics.charts.AnimatedSplitDonutChart
import com.foxhole.beta.ui.statistics.charts.SegmentedBarSegment
import com.foxhole.beta.ui.statistics.charts.SegmentedLinearBar
import com.foxhole.beta.ui.statistics.charts.SplitOutcomeBar
import com.foxhole.beta.ui.statistics.charts.VerticalValueBarChart
import com.foxhole.beta.ui.statistics.charts.chartColor
import com.foxhole.beta.ui.statistics.charts.chartCountryColors
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class TrafficTimelineBucket(
    val startedAtMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

internal data class ProfileStatisticsDetailModel(
    val totalBytes: Long,
    val successCount: Int,
    val failureCount: Int,
    val avgLatencyMs: Long?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val lastActivityAt: Long?,
    val lastProtocolHint: ProtocolHint,
    val protocols: List<ProfileProtocolDetail>,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
}

internal data class ProfileProtocolDetail(
    val label: String,
    val protocolHint: ProtocolHint,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val lastUsedAt: Long?,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
internal fun profileStatisticsDetail(
    state: SettingsRouteUiState,
    item: ProfileTrafficUiItem,
): ProfileStatisticsDetailModel {
    val profile = state.profiles.firstOrNull { profile -> profile.id == item.profileId }
    val optionList = profile?.protocolOptions.orEmpty()
    val options = optionList.associateBy(ProfileProtocolOption::id)
    val preference = state.settings.smartProfilePreferences.firstOrNull { preference -> preference.profileId == item.profileId }
    val memoriesByOptionId = preference?.protocolMemories.orEmpty().associateBy(SmartProfileProtocolMemory::optionId)
    val selectedOptionId = profile?.selectedProtocolOptionId ?: optionList.firstOrNull(ProfileProtocolOption::isSelected)?.id
    val configuredProtocolDetails =
        optionList
            .filter { option -> option.protocolHint != ProtocolHint.UNKNOWN }
            .map { option ->
                val memory = memoriesByOptionId[option.id]
                val latency = memory?.lastLatencyMs?.takeIf { value -> value > 0L }
                val lastUsed =
                    memory?.let {
                        maxOfNotNull(
                            it.lastSuccessAt,
                            it.lastFailureAt,
                            it.lastValidatedAt,
                            it.lastTrafficAt,
                        )
                    }
                val trafficForProtocol =
                    if (
                        option.id == selectedOptionId ||
                        (selectedOptionId == null && option.protocolHint == item.protocolHint) ||
                        (selectedOptionId == null && item.protocolHint == ProtocolHint.UNKNOWN && optionList.size == 1)
                    ) {
                        item
                    } else {
                        null
                    }
                ProfileProtocolDetail(
                    label = option.displayName.ifBlank { protocolDisplayName(option.protocolHint) },
                    protocolHint = option.protocolHint,
                    successCount = memory?.successCount?.coerceAtLeast(0) ?: 0,
                    failureCount = memory?.failureCount?.coerceAtLeast(0) ?: 0,
                    rxBytes = trafficForProtocol?.rxBytes ?: 0L,
                    txBytes = trafficForProtocol?.txBytes ?: 0L,
                    avgLatencyMs = latency,
                    minLatencyMs = latency,
                    maxLatencyMs = latency,
                    lastUsedAt = lastUsed ?: trafficForProtocol?.updatedAt?.takeIf { updatedAt -> updatedAt > 0L },
                )
            }
    val memoryOnlyDetails =
        preference
            ?.protocolMemories
            .orEmpty()
            .filterNot { memory -> memory.optionId in options }
            .mapNotNull { memory ->
                val protocol = profile?.protocolHint ?: item.protocolHint
                if (protocol == ProtocolHint.UNKNOWN) return@mapNotNull null
                val latency = memory.lastLatencyMs?.takeIf { value -> value > 0L }
                val lastUsed =
                    maxOfNotNull(
                        memory.lastSuccessAt,
                        memory.lastFailureAt,
                        memory.lastValidatedAt,
                        memory.lastTrafficAt,
                    )
                ProfileProtocolDetail(
                    label = protocolDisplayName(protocol),
                    protocolHint = protocol,
                    successCount = memory.successCount.coerceAtLeast(0),
                    failureCount = memory.failureCount.coerceAtLeast(0),
                    rxBytes = 0L,
                    txBytes = 0L,
                    avgLatencyMs = latency,
                    minLatencyMs = latency,
                    maxLatencyMs = latency,
                    lastUsedAt = lastUsed,
                )
            }
            .filter { detail -> detail.totalAttempts > 0 || detail.totalBytes > 0L || detail.lastUsedAt != null }
    val fallbackProtocol =
        item.protocolHint
            .takeIf { hint -> hint != ProtocolHint.UNKNOWN }
            ?: profile?.runtimeProtocolHint()
            ?: ProtocolHint.UNKNOWN
    val protocolDetails =
        (configuredProtocolDetails + memoryOnlyDetails)
            .takeIf(List<ProfileProtocolDetail>::isNotEmpty)
            ?: emptyList()
    val visibleProtocolDetails =
        protocolDetails.takeIf(List<ProfileProtocolDetail>::isNotEmpty)
            ?: listOf(
                ProfileProtocolDetail(
                    label = protocolDisplayName(fallbackProtocol),
                    protocolHint = fallbackProtocol,
                    successCount = 0,
                    failureCount = 0,
                    rxBytes = item.rxBytes,
                    txBytes = item.txBytes,
                    avgLatencyMs = null,
                    minLatencyMs = null,
                    maxLatencyMs = null,
                    lastUsedAt = item.updatedAt.takeIf { updatedAt -> updatedAt > 0L },
                ),
            )
    val latencies = visibleProtocolDetails.mapNotNull(ProfileProtocolDetail::avgLatencyMs)
    val successCount = visibleProtocolDetails.sumOf(ProfileProtocolDetail::successCount)
    val failureCount = visibleProtocolDetails.sumOf(ProfileProtocolDetail::failureCount)
    val lastProtocol =
        visibleProtocolDetails
            .maxByOrNull { detail -> detail.lastUsedAt ?: 0L }
            ?.protocolHint
            ?: fallbackProtocol
    return ProfileStatisticsDetailModel(
        totalBytes = item.totalBytes,
        successCount = successCount,
        failureCount = failureCount,
        avgLatencyMs = latencies.averageOrNull(),
        minLatencyMs = latencies.minOrNull(),
        maxLatencyMs = latencies.maxOrNull(),
        lastActivityAt =
        maxOfNotNull(
            item.updatedAt.takeIf { updatedAt -> updatedAt > 0L },
            visibleProtocolDetails.mapNotNull(ProfileProtocolDetail::lastUsedAt).maxOrNull(),
        ),
        lastProtocolHint = lastProtocol,
        protocols = visibleProtocolDetails,
    )
}

internal fun statisticsUiState(
    state: SettingsRouteUiState,
    retention: StatisticsRetention,
): StatisticsUiState {
    val profileTraffic = profileTrafficItems(state)
    val protocolTraffic = protocolTrafficItems(state)
    val protocolStats = protocolStatistics(state, protocolTraffic)
    val total = overallStatistics(profileTraffic, protocolStats, state)
    return StatisticsUiState(
        range = retention.toStatisticsRange(),
        extendedMode =
        state.settings.statistics.enabled &&
            state.settings.statistics.appTrafficEnabled &&
            state.settings.appTrafficStatsEnabled,
        profileTraffic = profileTraffic,
        total = total,
        vpnProtocols = protocolStats,
        profileComparisons = profileComparisons(state, protocolTraffic),
        transports = transportStatistics(protocolTraffic),
    )
}

internal fun protocolTrafficItems(state: SettingsRouteUiState): List<ProfileTrafficUiItem> {
    val items =
        state.settings.profileTrafficTotals.map { total ->
            ProfileTrafficUiItem(
                profileId = total.profileId,
                profileName = total.profileName,
                protocolHint = total.protocolHint,
                transport = total.transport,
                rxBytes = total.rxTotalBytes,
                txBytes = total.txTotalBytes,
                updatedAt = total.updatedAt,
            )
        }
            .toMutableList()
    val activeProfile = state.activeProfile
    val liveTraffic = state.traffic
    if (activeProfile != null && (liveTraffic.rxTotalBytes > 0L || liveTraffic.txTotalBytes > 0L)) {
        items +=
            ProfileTrafficUiItem(
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                protocolHint = activeProfile.runtimeProtocolHint(),
                transport = TransportProtocol.UNKNOWN,
                rxBytes = liveTraffic.rxTotalBytes,
                txBytes = liveTraffic.txTotalBytes,
                updatedAt = liveTraffic.sampledAt,
            )
    }
    return items.sortedByDescending(ProfileTrafficUiItem::updatedAt)
}

internal fun profileTrafficItems(state: SettingsRouteUiState): List<ProfileTrafficUiItem> =
    protocolTrafficItems(state)
        .groupBy(ProfileTrafficUiItem::profileId)
        .values
        .map { items ->
            val latest = items.maxBy(ProfileTrafficUiItem::updatedAt)
            ProfileTrafficUiItem(
                profileId = latest.profileId,
                profileName = latest.profileName,
                protocolHint = latest.protocolHint,
                transport = items.singleKnownTransportOrUnknown(),
                rxBytes = items.sumOf(ProfileTrafficUiItem::rxBytes),
                txBytes = items.sumOf(ProfileTrafficUiItem::txBytes),
                updatedAt = latest.updatedAt,
            )
        }.sortedByDescending(ProfileTrafficUiItem::updatedAt)

internal fun protocolStatistics(
    state: SettingsRouteUiState,
    profileTraffic: List<ProfileTrafficUiItem>,
): List<ProtocolStatisticsUiItem> {
    val profileById = state.profiles.associateBy(Profile::id)
    val accumulators = linkedMapOf<ProtocolHint, ProtocolAccumulator>()
    state.profiles.flatMap(Profile::statisticsProtocolHints).forEach { protocol ->
        if (protocol != ProtocolHint.UNKNOWN) {
            accumulators.getOrPut(protocol) { ProtocolAccumulator() }
        }
    }
    state.settings.smartProfilePreferences.forEach { preference ->
        val profile = profileById[preference.profileId] ?: return@forEach
        val options = profile.protocolOptions.associateBy(ProfileProtocolOption::id)
        preference.protocolMemories.forEach { memory ->
            val protocol = options[memory.optionId]?.protocolHint ?: return@forEach
            if (protocol != ProtocolHint.UNKNOWN) {
                accumulators.getOrPut(protocol) { ProtocolAccumulator() }.addMemory(memory)
            }
        }
    }
    profileTraffic.forEach { traffic ->
        if (traffic.protocolHint != ProtocolHint.UNKNOWN) {
            accumulators.getOrPut(traffic.protocolHint) { ProtocolAccumulator() }.addTraffic(traffic)
        }
    }
    return accumulators
        .map { (protocol, accumulator) -> accumulator.toProtocolItem(protocol) }
        .filter { item -> item.protocol != ProtocolHint.UNKNOWN && (item.successCount > 0 || item.totalBytes > 0L) }
        .sortedWith(
            compareByDescending<ProtocolStatisticsUiItem> { item -> item.totalBytes }
                .thenByDescending { item -> item.successCount }
                .thenBy { item -> item.protocol.name },
        )
}

internal fun overallStatistics(
    profileTraffic: List<ProfileTrafficUiItem>,
    protocolStats: List<ProtocolStatisticsUiItem>,
    state: SettingsRouteUiState,
): OverallStatisticsUiItem {
    val memoryLatencies =
        state.settings.smartProfilePreferences.flatMap { preference ->
            preference.protocolMemories.mapNotNull { memory -> memory.lastLatencyMs?.takeIf { latency -> latency > 0L } }
        }
    val successCount = protocolStats.sumOf(ProtocolStatisticsUiItem::successCount)
    val failureCount = protocolStats.sumOf(ProtocolStatisticsUiItem::failureCount)
    val lastActivity =
        maxOfNotNull(
            profileTraffic.maxOfOrNull(ProfileTrafficUiItem::updatedAt),
            protocolStats.mapNotNull(ProtocolStatisticsUiItem::lastUsedAt).maxOrNull(),
            state.traffic.sampledAt.takeIf { sampledAt -> sampledAt > 0L && state.traffic.rxTotalBytes + state.traffic.txTotalBytes > 0L },
        )
    return OverallStatisticsUiItem(
        totalBytes = profileTraffic.sumOf(ProfileTrafficUiItem::totalBytes),
        vpnSessions = successCount + failureCount,
        successCount = successCount,
        failureCount = failureCount,
        avgLatencyMs = memoryLatencies.averageOrNull(),
        lastActivityAt = lastActivity,
    )
}

internal fun profileComparisons(
    state: SettingsRouteUiState,
    profileTraffic: List<ProfileTrafficUiItem>,
): List<ProfileComparisonUiItem> {
    val comparisonByProtocol = linkedMapOf<ProtocolHint, MutableMap<Long, ComparisonAccumulator>>()
    val profileById = state.profiles.associateBy(Profile::id)
    state.settings.smartProfilePreferences.forEach { preference ->
        val profile = profileById[preference.profileId] ?: return@forEach
        val options = profile.protocolOptions.associateBy(ProfileProtocolOption::id)
        preference.protocolMemories.forEach { memory ->
            val protocol = options[memory.optionId]?.protocolHint ?: return@forEach
            if (protocol == ProtocolHint.UNKNOWN) {
                return@forEach
            }
            val profileMap = comparisonByProtocol.getOrPut(protocol) { linkedMapOf() }
            profileMap
                .getOrPut(profile.id) { ComparisonAccumulator(profile.id, profile.name) }
                .addMemory(memory)
        }
    }
    profileTraffic.forEach { traffic ->
        val protocol = traffic.protocolHint.takeIf { hint -> hint != ProtocolHint.UNKNOWN } ?: return@forEach
        comparisonByProtocol
            .getOrPut(protocol) { linkedMapOf() }
            .getOrPut(traffic.profileId) { ComparisonAccumulator(traffic.profileId, traffic.profileName) }
            .addTraffic(traffic)
    }
    return comparisonByProtocol.mapNotNull { (protocol, profileMap) ->
        val candidates =
            profileMap.values
                .map(ComparisonAccumulator::toSide)
                .filter { side -> side.totalAttempts >= PROFILE_COMPARISON_MIN_ATTEMPTS }
                .sortedWith(
                    compareByDescending<ProfileComparisonSideUiItem> { side -> side.totalAttempts }
                        .thenByDescending { side -> side.totalBytes },
                )
        if (candidates.size < 2) {
            null
        } else {
            ProfileComparisonUiItem(
                protocol = protocol,
                left = candidates[0],
                right = candidates[1],
            )
        }
    }
}

internal fun transportStatistics(profileTraffic: List<ProfileTrafficUiItem>): List<TransportStatisticsUiItem> {
    val accumulators = linkedMapOf<TransportProtocol, ProtocolAccumulator>()
    profileTraffic.forEach { traffic ->
        val transport = traffic.transport
        val accumulator = accumulators.getOrPut(transport) { ProtocolAccumulator() }
        accumulator.rxBytes += traffic.rxBytes
        accumulator.txBytes += traffic.txBytes
        accumulator.lastUsedAt = maxOfNotNull(accumulator.lastUsedAt, traffic.updatedAt.takeIf { it > 0L })
    }
    return accumulators
        .map { (transport, accumulator) ->
            TransportStatisticsUiItem(
                transport = transport,
                successCount = accumulator.successCount,
                failureCount = accumulator.failureCount,
                rxBytes = accumulator.rxBytes,
                txBytes = accumulator.txBytes,
                avgLatencyMs = accumulator.latencies.averageOrNull(),
            )
        }
        .filter { item -> item.transport != TransportProtocol.UNKNOWN && (item.totalAttempts > 0 || item.totalBytes > 0L) }
        .sortedWith(
            compareByDescending<TransportStatisticsUiItem> { item -> item.totalBytes }
                .thenBy { item -> item.transport.name },
        )
}

internal fun appTrafficRows(
    samples: List<AppTrafficWindow>,
    installedApps: List<InstalledAppOption>,
    anomalyEvents: List<AnomalyEvent>,
    retention: StatisticsRetention,
    nowMs: Long = System.currentTimeMillis(),
): List<AppTrafficRow> {
    val labels = installedApps.associate { it.packageName to it.label }
    val cutoff = retention.durationMs?.let { nowMs - it }
    return com.foxhole.beta.core.statistics.appTrafficRows(
        windows = samples.filter { sample -> cutoff == null || sample.startedAtMs >= cutoff },
        labelsByPackage = labels,
        anomalyEvents = anomalyEvents,
        includeOther = false,
    )
}

internal fun dnsProtectionSummary(
    trafficWindows: List<TrafficWindow>,
    appRows: List<AppTrafficRow>,
    retention: StatisticsRetention,
    displayRange: StatisticsDisplayRange? = null,
    dnsSettings: DnsSettings,
    nowMs: Long = System.currentTimeMillis(),
): DnsProtectionSummary {
    val cutoff = (displayRange?.durationMs ?: retention.durationMs)?.let { nowMs - it }
    val windows =
        trafficWindows.filter { window -> cutoff == null || window.startedAtMs >= cutoff }
    return com.foxhole.beta.core.statistics.dnsProtectionSummary(
        trafficWindows = windows,
        appRows = appRows,
        dnsSettings = dnsSettings,
    )
}

internal fun enabledDnsProtectionCategories(settings: DnsSettings): List<DnsProtectionCategory> =
    com.foxhole.beta.core.statistics.enabledDnsProtectionCategories(settings)

internal fun splitDnsBlockedByCategory(
    blocked: Int,
    categories: List<DnsProtectionCategory>,
): List<DnsProtectionCategoryRow> {
    return com.foxhole.beta.core.statistics.splitDnsBlockedByCategory(blocked, categories)
}

internal fun installedAppChangesForRetention(
    changes: List<InstalledAppInventoryChange>,
    retention: StatisticsRetention,
    nowMs: Long = System.currentTimeMillis(),
): List<InstalledAppInventoryChange> {
    val cutoff = retention.durationMs?.let { nowMs - it }
    return changes
        .asSequence()
        .filter { change -> cutoff == null || change.detectedAt >= cutoff }
        .sortedByDescending(InstalledAppInventoryChange::detectedAt)
        .toList()
}

internal fun countryTrafficRows(
    trafficWindows: List<TrafficWindow>,
    liveDestinations: List<TrafficMapPoint>,
): List<CountryTrafficUiRow> =
    com.foxhole.beta.core.statistics.countryTrafficRows(
        trafficWindows = trafficWindows,
        liveDestinations = liveDestinations,
    )

internal fun normalizedCountryCode(countryCode: String?): String? =
    com.foxhole.beta.core.statistics.normalizedCountryCode(countryCode)

internal fun countryDisplayName(countryCode: String): String =
    com.foxhole.beta.core.statistics.countryDisplayName(countryCode)

internal fun metricIfPositive(
    label: String,
    value: Int,
    displayValue: String = value.toString(),
): Pair<String, String>? = value.takeIf { it > 0 }?.let { label to displayValue }

internal fun metricIfPositive(
    label: String,
    value: Long,
    displayValue: String = value.toString(),
): Pair<String, String>? = value.takeIf { it > 0L }?.let { label to displayValue }

@Composable
internal fun rememberOneShotVisible(key: String): Boolean {
    var visible by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!visible) {
            visible = true
        }
    }
    return visible
}

internal fun protocolDisplayName(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.HYSTERIA2 -> "Hysteria2"
        ProtocolHint.SING_BOX -> "Sing-box"
        ProtocolHint.UNKNOWN -> "Unknown"
        else -> protocol.name
    }

internal fun transportLabel(transport: TransportProtocol): String =
    when (transport) {
        TransportProtocol.TCP -> "TCP"
        TransportProtocol.UDP -> "UDP"
        TransportProtocol.UNKNOWN -> "Unknown"
    }

internal fun maxOfNotNull(vararg values: Long?): Long? =
    values.filterNotNull().maxOrNull()

internal val COMPACT_PROTOCOL_GRID_WIDTH = 360.dp
internal const val PROFILE_DETAIL_OVERALL_KEY = "__overall__"
internal const val MIN_TIMELINE_TRAFFIC_SCALE_BYTES = 10L * 1024L * 1024L
internal const val PROFILE_TRAFFIC_PREVIEW_LIMIT = 6
internal const val STATISTICS_TOP_PREVIEW_LIMIT = 5
internal const val APP_TRAFFIC_CHART_LIMIT = 10
internal val DASHBOARD_DISPLAY_RANGES =
    listOf(
        StatisticsDisplayRange.HOURS_24,
        StatisticsDisplayRange.WEEK,
        StatisticsDisplayRange.MONTH,
    )
internal fun dashboardStatisticsDisplayRanges(): List<StatisticsDisplayRange> = DASHBOARD_DISPLAY_RANGES
internal const val PROFILE_COMPARISON_MIN_ATTEMPTS = 2
