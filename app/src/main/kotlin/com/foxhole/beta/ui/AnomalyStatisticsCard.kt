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

@Composable
internal fun AnomalyStatisticsCard(
    events: List<AnomalyEvent>,
    totalEventsCount: Int,
    installedApps: List<InstalledAppOption>,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
    onShowAllEvents: () -> Unit,
) {
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    val recentEvents = remember(events) { events.sortedByDescending(AnomalyEvent::createdAtMs) }
    val appsCount =
        remember(recentEvents) {
            recentEvents.mapNotNull(AnomalyEvent::packageName).distinct().size
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Outlined.WarningAmber,
                title = stringResource(R.string.statistics_anomaly_summary_title),
                trailing = {
                    StatisticsRangePillDropdown(
                        value = range,
                        expanded = rangeExpanded,
                        onExpandedChange = { rangeExpanded = it },
                        onSelect = onRangeSelected,
                    )
                },
            )
            if (recentEvents.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.statistics_anomaly_empty))
            } else {
                DetailMetricGrid(
                    metrics =
                    listOfNotNull(
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_events),
                            recentEvents.size,
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_high_events),
                            recentEvents.count { event -> event.severity == AnomalySeverity.HIGH },
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_notified_events),
                            recentEvents.count(AnomalyEvent::notificationShown),
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_apps),
                            appsCount,
                        ),
                    ),
                )
                if (recentEvents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.statistics_anomaly_recent_events),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentEvents.take(STATISTICS_TOP_PREVIEW_LIMIT).forEach { event ->
                            AnomalyEventRow(event = event, installedApps = installedApps)
                        }
                    }
                    if (recentEvents.size > STATISTICS_TOP_PREVIEW_LIMIT || totalEventsCount > recentEvents.size) {
                        TextButton(onClick = onShowAllEvents) {
                            Text(stringResource(R.string.statistics_anomaly_show_all_events))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnomalyEventRow(
    event: AnomalyEvent,
    installedApps: List<InstalledAppOption>,
) {
    val context = LocalContext.current
    val appLabel =
        event.packageName
            ?.let { packageName -> installedApps.firstOrNull { app -> app.packageName == packageName }?.label ?: packageName }
            ?: stringResource(R.string.statistics_anomaly_whole_tunnel)
    val eventColor =
        when (event.severity) {
            AnomalySeverity.HIGH -> MaterialTheme.colorScheme.error
            AnomalySeverity.NOTIFICATION -> MaterialTheme.colorScheme.primary
            AnomalySeverity.ACTIVITY_LOG -> MaterialTheme.colorScheme.tertiary
            AnomalySeverity.SILENT -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(eventColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anomalyReasonText(
                        event = event,
                        appLabel = appLabel,
                        formatBytes = { bytes -> formatBytes(context, bytes) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        event.packageName,
                        event.protocol,
                        event.createdAtMs.formatLastActivity(),
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.score.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = eventColor,
            )
        }
    }
}

@Composable
internal fun AnomalyBadges(badges: Set<AppAnomalyBadge>) {
    val visibleBadges = badges.take(2)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        visibleBadges.forEach { badge ->
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = anomalyBadgeColor(badge),
            ) {
                Text(
                    text = anomalyBadgeLabel(badge),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = anomalyBadgeContentColor(badge),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun anomalyBadgeLabel(badge: AppAnomalyBadge): String =
    stringResource(
        when (badge) {
            AppAnomalyBadge.NORMAL -> R.string.anomaly_badge_normal
            AppAnomalyBadge.UNUSUAL -> R.string.anomaly_badge_unusual
            AppAnomalyBadge.HIGH_UPLOAD -> R.string.anomaly_badge_high_upload
            AppAnomalyBadge.NEW_ROUTE -> R.string.anomaly_badge_new_route
            AppAnomalyBadge.BACKGROUND -> R.string.anomaly_badge_background
        },
    )

@Composable
internal fun anomalyBadgeColor(badge: AppAnomalyBadge): Color {
    val semanticColors = LocalFoxholeSemanticColors.current
    return when (badge) {
        AppAnomalyBadge.NORMAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        AppAnomalyBadge.UNUSUAL -> MaterialTheme.colorScheme.secondaryContainer
        AppAnomalyBadge.HIGH_UPLOAD -> MaterialTheme.colorScheme.errorContainer
        AppAnomalyBadge.NEW_ROUTE -> MaterialTheme.colorScheme.tertiaryContainer
        AppAnomalyBadge.BACKGROUND -> semanticColors.warning.copy(alpha = 0.18f)
    }
}

@Composable
internal fun anomalyBadgeContentColor(badge: AppAnomalyBadge): Color =
    when (badge) {
        AppAnomalyBadge.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        AppAnomalyBadge.UNUSUAL -> MaterialTheme.colorScheme.onSecondaryContainer
        AppAnomalyBadge.HIGH_UPLOAD -> MaterialTheme.colorScheme.onErrorContainer
        AppAnomalyBadge.NEW_ROUTE -> MaterialTheme.colorScheme.onTertiaryContainer
        AppAnomalyBadge.BACKGROUND -> MaterialTheme.colorScheme.onSurface
    }

@Composable
internal fun anomalyReasonText(
    event: AnomalyEvent,
    appLabel: String,
    formatBytes: (Long) -> String,
): String =
    when (event.type) {
        AnomalyType.APP_UPLOAD_SPIKE ->
            stringResource(
                R.string.anomaly_reason_app_upload,
                appLabel,
                event.evidence["tx_per_min"]?.toLongOrNull()?.let(formatBytes) ?: stringResource(R.string.statistics_no_data),
                event.evidence["upload_ratio"].asPercentText(),
            )
        AnomalyType.APP_BACKGROUND_TRAFFIC ->
            stringResource(
                R.string.anomaly_reason_background,
                appLabel,
                event.evidence["app_share"].asPercentText(),
                event.evidence["upload_ratio"].asPercentText(),
            )
        AnomalyType.TOTAL_TRAFFIC_SPIKE ->
            stringResource(
                R.string.anomaly_reason_total_spike,
                event.evidence["bytes_per_min"]?.toLongOrNull()?.let(formatBytes) ?: stringResource(R.string.statistics_no_data),
                event.evidence["robust_z"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
            )
        AnomalyType.NEW_DESTINATION_COUNTRY ->
            stringResource(
                R.string.anomaly_reason_new_country,
                event.evidence["country"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
                event.evidence["traffic_share"].asPercentText(),
            )
        AnomalyType.DNS_BLOCK_RATIO_SPIKE ->
            stringResource(
                R.string.anomaly_reason_dns_blocks,
                event.evidence["blocked_dns"].orEmpty().ifBlank { "0" },
                event.evidence["allowed_dns"].orEmpty().ifBlank { "0" },
                event.evidence["blocked_ratio"].asPercentText(),
            )
        AnomalyType.RECONNECT_STORM ->
            stringResource(
                R.string.anomaly_reason_reconnects,
                event.evidence["reconnects"].orEmpty().ifBlank { "0" },
            )
        AnomalyType.LATENCY_SHIFT ->
            stringResource(
                R.string.anomaly_reason_latency,
                event.evidence["latency_ms"].orEmpty().ifBlank { "0" },
                event.evidence["robust_z"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
            )
        AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH ->
            stringResource(
                R.string.anomaly_reason_privacy_route,
                event.evidence["route_share"].asPercentText(),
            )
    }

