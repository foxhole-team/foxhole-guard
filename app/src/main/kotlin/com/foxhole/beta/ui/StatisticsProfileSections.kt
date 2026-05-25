package com.foxhole.beta.ui

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileComparisonSideUiItem
import com.foxhole.beta.core.model.ProfileComparisonUiItem
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileTrafficUiItem
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProtocolQuality
import com.foxhole.beta.core.model.ProtocolStatisticsUiItem
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRange
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TransportStatisticsUiItem
import com.foxhole.beta.ui.statistics.charts.AnimatedSplitDonutChart
import com.foxhole.beta.ui.statistics.charts.SegmentedBarSegment
import com.foxhole.beta.ui.statistics.charts.SegmentedLinearBar
import java.util.Locale
import kotlin.math.max

@Composable
internal fun ProfileTrafficOverviewCard(
    statistics: StatisticsUiState,
    state: SettingsRouteUiState,
    onClear: () -> Unit,
    onProfileClick: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.statistics_profile_traffic_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.clear_statistics_history_content_description),
                    )
                }
            }
            ProfileTrafficList(
                items = statistics.profileTraffic,
                state = state,
                onProfileClick = onProfileClick,
            )
        }
    }
}

@Composable
internal fun ProfileTrafficList(
    items: List<ProfileTrafficUiItem>,
    state: SettingsRouteUiState,
    onProfileClick: (Long) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.diagnostics_usage_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val context = LocalContext.current
    val previewItems =
        remember(items) {
            items
                .sortedByDescending(ProfileTrafficUiItem::totalBytes)
                .take(PROFILE_TRAFFIC_PREVIEW_LIMIT)
        }
    val detailsByProfileId =
        remember(previewItems, state.profiles, state.settings.smartProfilePreferences) {
            previewItems.associate { item -> item.profileId to profileStatisticsDetail(state, item) }
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        previewItems
            .forEachIndexed { index, item ->
                val detail = detailsByProfileId.getValue(item.profileId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProfileClick(item.profileId) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.profileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.statistics_profile_subtitle,
                                protocolDisplayName(detail.lastProtocolHint),
                                detail.avgLatencyMs.formatLatency(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(
                                R.string.statistics_profile_rx_tx,
                                formatBytes(context, item.rxBytes),
                                formatBytes(context, item.txBytes),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                        Text(
                            text = formatBytes(context, item.totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (index != previewItems.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                }
            }
    }
}

@Composable
@Suppress("UnusedParameter")
internal fun CountryTrafficCard(
    state: TrafficMapUiState,
    rows: List<CountryTrafficUiRow>,
    totalRowsCount: Int,
    enabled: Boolean,
    networkActivityLoggingEnabled: Boolean,
    onEnableFirewall: () -> Unit,
    onEnableNetworkActivityLogging: () -> Unit,
    onShowAll: () -> Unit,
) {
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
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.statistics_country_traffic_top_title),
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.traffic_map_live_requires_firewall),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEnableFirewall) {
                    Text(stringResource(R.string.statistics_country_enable_firewall_action))
                }
            } else if (!networkActivityLoggingEnabled && rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.statistics_country_network_log_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEnableNetworkActivityLogging) {
                    Text(stringResource(R.string.statistics_app_detail_enable_network_log_action))
                }
            } else if (rows.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.traffic_map_waiting_connections))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    CountryVerticalBarChart(
                        points = rows,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    CountryTrafficList(
                        points = rows,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (totalRowsCount > rows.size) {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.show_all_label))
                    }
                }
            }
        }
    }
}

@Composable
internal fun CountryTrafficRow(row: CountryTrafficUiRow) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countryEmoji(row.countryCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                pluralStringResource(
                    R.plurals.statistics_country_connections,
                    row.sessions,
                    row.sessions,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatBytes(context, row.bytes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun StatisticsDisabledState(onEnable: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = stringResource(R.string.statistics_disabled_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onEnable) {
                Text(stringResource(R.string.statistics_enable_action))
            }
        }
    }
}

@Composable
internal fun CountryTrafficList(
    points: List<CountryTrafficUiRow>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = statisticsCountryChartColors()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        points.forEachIndexed { index, point ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(9.dp).clearAndSetSemantics {}) {
                    drawCircle(color = colors[index % colors.size])
                }
                Text(
                    text = "${countryEmoji(point.countryCode)} ${point.label}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(context, point.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
internal fun ProtocolStatisticsSection(items: List<ProtocolStatisticsUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.Route,
        title = stringResource(R.string.statistics_protocols_title),
    ) {
        if (items.isEmpty()) {
            EmptySectionText(text = stringResource(R.string.statistics_protocols_empty))
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= COMPACT_PROTOCOL_GRID_WIDTH) 3 else 2
                val rows = remember(items, columns) { items.chunked(columns) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { item ->
                                ProtocolStatCard(item = item, modifier = Modifier.weight(1f))
                            }
                            repeat(columns - rowItems.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProtocolStatCard(
    item: ProtocolStatisticsUiItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visible = rememberOneShotVisible("protocol:${item.protocol.name}")
    val successRate = item.successRateOrNull
    val errorRate = item.errorRateOrNull
    val successText = successRate?.let(::formatPercent) ?: stringResource(R.string.smart_profile_metric_unavailable)
    val errorText = errorRate?.let(::formatPercent) ?: stringResource(R.string.smart_profile_metric_unavailable)
    val footerText =
        if (item.quality == ProtocolQuality.TRAFFIC_ONLY) {
            stringResource(
                R.string.statistics_protocol_footer_unmeasured,
                formatBytes(context, item.totalBytes),
            )
        } else {
            pluralStringResource(
                R.plurals.statistics_protocol_footer,
                item.totalAttempts,
                formatBytes(context, item.totalBytes),
                item.totalAttempts,
            )
        }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = protocolDisplayName(item.protocol),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier.padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedSplitDonutChart(
                    successRate = successRate ?: 0f,
                    errorRate = errorRate ?: 0f,
                    visible = visible,
                    contentDescription = stringResource(
                        R.string.statistics_profile_protocol_metrics,
                        successText,
                        errorText,
                        formatBytes(context, item.totalBytes),
                    ),
                    modifier = Modifier.size(76.dp),
                    animationLabel = "protocol-donut-${item.protocol.name}",
                )
                Text(
                    text = successText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.statistics_errors_percent, errorText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = footerText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ProfileComparisonsSection(items: List<ProfileComparisonUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.SettingsEthernet,
        title = stringResource(R.string.statistics_profile_comparison_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                ProfileComparisonCard(item = item)
            }
        }
    }
}

@Composable
internal fun ProfileComparisonCard(item: ProfileComparisonUiItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = protocolDisplayName(item.protocol),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComparisonSide(
                    side = item.left,
                    modifier = Modifier.weight(1f),
                    stable = item.left.stability >= item.right.stability,
                    problematic = item.left.errorRate > item.right.errorRate,
                )
                HorizontalDivider(modifier = Modifier.width(30.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ComparisonSide(
                    side = item.right,
                    modifier = Modifier.weight(1f),
                    stable = item.right.stability > item.left.stability,
                    problematic = item.right.errorRate >= item.left.errorRate,
                )
            }
        }
    }
}

@Composable
internal fun ComparisonSide(
    side: ProfileComparisonSideUiItem,
    stable: Boolean,
    problematic: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = side.profileName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                if (stable) {
                    stringResource(R.string.statistics_more_stable)
                } else if (problematic) {
                    stringResource(R.string.statistics_more_errors)
                } else {
                    stringResource(R.string.statistics_even)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                pluralStringResource(
                    R.plurals.statistics_comparison_metrics,
                    side.totalAttempts,
                    formatPercent(side.stability),
                    formatPercent(side.errorRate),
                    side.totalAttempts,
                    side.avgLatencyMs.formatLatency(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TransportStatisticsSection(items: List<TransportStatisticsUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.SettingsEthernet,
        title = stringResource(R.string.statistics_transports_title),
    ) {
        if (items.isEmpty()) {
            EmptySectionText(text = stringResource(R.string.statistics_transports_empty))
        } else {
            val totalBytes = items.sumOf(TransportStatisticsUiItem::totalBytes).coerceAtLeast(1L)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    TransportRow(
                        item = item,
                        usageShare = item.totalBytes.toFloat() / totalBytes.toFloat(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TransportRow(
    item: TransportStatisticsUiItem,
    usageShare: Float,
) {
    val context = LocalContext.current
    val usageText = formatPercent(usageShare)
    val barDescription =
        "${transportLabel(item.transport)} $usageText ${formatBytes(context, item.totalBytes)}"
    val barColor =
        when (item.transport) {
            TransportProtocol.TCP -> MaterialTheme.colorScheme.primary
            TransportProtocol.UDP -> MaterialTheme.colorScheme.tertiary
            TransportProtocol.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = transportLabel(item.transport),
            modifier = Modifier.widthIn(min = 74.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        SegmentedLinearBar(
            segments =
            listOf(
                SegmentedBarSegment(
                    color = barColor,
                    ratio = usageShare,
                    minVisibleWidth = 3.dp,
                ),
            ),
            contentDescription = barDescription,
            modifier = Modifier
                .weight(1f)
                .height(10.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = usageText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            Text(
                text = formatBytes(context, item.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

internal fun Float?.orZero(): Float = this ?: 0f

@Composable
internal fun StatisticsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(icon = icon, title = title, trailing = trailing)
            content()
        }
    }
}

@Composable
internal fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        trailing()
    }
}

@Composable
internal fun EmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun StatisticsMetricSwitch(
    metric: StatisticsMetric,
    checked: Boolean,
    title: String,
    enabled: Boolean,
    onMetricChanged: (StatisticsMetric, Boolean) -> Unit,
    summary: String? = null,
) {
    SettingSwitchRow(
        title = title,
        checked = checked,
        summary = summary,
        leadingIcon = Icons.Outlined.Tune,
        enabled = enabled,
        onCheckedChange = { value -> onMetricChanged(metric, value) },
        grouped = true,
        summaryMaxLines = Int.MAX_VALUE,
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun ProfileStatisticsDetail(
    state: SettingsRouteUiState,
    statistics: StatisticsUiState,
    item: ProfileTrafficUiItem,
) {
    val context = LocalContext.current
    val detail = profileStatisticsDetail(state, item)
    val profile = state.profiles.firstOrNull { profile -> profile.id == item.profileId }
    val connectedProtocols =
        remember(detail.protocols) {
            detail.protocols
                .filter { protocol -> protocol.totalAttempts > 0 || protocol.totalBytes > 0L || protocol.lastUsedAt != null }
                .sortedWith(
                    compareByDescending<ProfileProtocolDetail> { protocol -> protocol.totalBytes }
                        .thenByDescending { protocol -> protocol.totalAttempts }
                        .thenBy { protocol -> protocol.label.lowercase(Locale.getDefault()) },
                )
        }
    val protocolSelectionKey =
        remember(connectedProtocols) {
            connectedProtocols.joinToString(
                separator = "|",
                transform = ProfileProtocolDetail::label,
            )
        }
    var selectedDetailKey by rememberSaveable(item.profileId, protocolSelectionKey) {
        mutableStateOf(PROFILE_DETAIL_OVERALL_KEY)
    }
    val selectedProtocol = connectedProtocols.firstOrNull { protocol -> protocol.label == selectedDetailKey }
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val showProtocolSwitcher = shouldShowProfileProtocolSwitcher(profile, connectedProtocols.size)
        if (connectedProtocols.isEmpty() || !showProtocolSwitcher) {
            ProfileOverallDetailGrid(detail = detail)
            if (connectedProtocols.isEmpty() && detail.protocols.isNotEmpty()) {
                EmptySectionText(text = stringResource(R.string.statistics_protocols_empty))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val overallSummaryLines =
                    listOf(
                        stringResource(R.string.statistics_success_rate) to formatPercent(detail.successRate),
                        stringResource(R.string.statistics_error_rate) to formatPercent(detail.errorRate),
                        detail.lastActivityAt?.let {
                            stringResource(R.string.statistics_last_activity) to it.formatLastActivity()
                        },
                    ).filterNotNull()
                if (selectedProtocol != null) {
                    ProfileProtocolDetailPanel(protocol = selectedProtocol)
                } else {
                    ProfileOverallDetailGrid(detail = detail)
                }
                Text(
                    text = stringResource(R.string.statistics_profile_protocols_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfileDetailUsageRow(
                        title = stringResource(R.string.statistics_profile_overall_tab),
                        summaryLines = overallSummaryLines,
                        trailing = formatBytes(context, detail.totalBytes),
                        selected = selectedDetailKey == PROFILE_DETAIL_OVERALL_KEY,
                        onClick = { selectedDetailKey = PROFILE_DETAIL_OVERALL_KEY },
                    )
                    connectedProtocols.forEach { protocol ->
                        ProfileProtocolUsageRow(
                            protocol = protocol,
                            selected = protocol.label == selectedProtocol?.label,
                            onClick = { selectedDetailKey = protocol.label },
                        )
                    }
                }
            }
        }
        val comparisons =
            statistics.profileComparisons.filter { comparison ->
                comparison.left.profileId == item.profileId || comparison.right.profileId == item.profileId
            }
        if (comparisons.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_profile_comparison_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            comparisons.forEach { comparison -> ProfileComparisonCard(item = comparison) }
        }
    }
}

@Composable
private fun ProfileOverallDetailGrid(detail: ProfileStatisticsDetailModel) {
    val context = LocalContext.current
    DetailMetricGrid(
        metrics =
        listOfNotNull(
            metricIfPositive(
                stringResource(R.string.statistics_total_traffic),
                detail.totalBytes,
                formatBytes(context, detail.totalBytes),
            ),
            metricIfPositive(stringResource(R.string.statistics_vpn_sessions), detail.totalAttempts),
            metricIfPositive(stringResource(R.string.statistics_successful_connections), detail.successCount),
            metricIfPositive(stringResource(R.string.statistics_errors), detail.failureCount),
            detail.avgLatencyMs?.let { stringResource(R.string.statistics_average_latency) to it.formatLatency() },
            detail.minLatencyMs?.let { stringResource(R.string.statistics_min_latency) to it.formatLatency() },
            detail.maxLatencyMs?.let { stringResource(R.string.statistics_max_latency) to it.formatLatency() },
            detail.lastActivityAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
        ),
    )
}

@Composable
internal fun ProfileDetailUsageRow(
    title: String,
    summaryLines: List<Pair<String, String>>,
    trailing: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val rowContentColor =
        if (selected) {
            selectedContentColor
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val summaryColor =
        if (selected) {
            selectedContentColor.copy(alpha = 0.74f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border =
        BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            },
        ),
        contentColor = rowContentColor,
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summaryLines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        summaryLines.forEach { (label, value) ->
                            Text(
                                text = "$label: $value",
                                style = MaterialTheme.typography.labelSmall,
                                color = summaryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
internal fun ProfileProtocolUsageRow(
    protocol: ProfileProtocolDetail,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val summaryLines =
        listOfNotNull(
            protocol.lastUsedAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
            stringResource(R.string.statistics_success_rate) to formatPercent(protocol.successRate),
            stringResource(R.string.statistics_error_rate) to formatPercent(protocol.errorRate),
        )
    ProfileDetailUsageRow(
        title = protocol.label,
        summaryLines = summaryLines,
        trailing = formatBytes(context, protocol.totalBytes),
        selected = selected,
        onClick = onClick,
    )
}

@Composable
internal fun ProfileProtocolDetailPanel(protocol: ProfileProtocolDetail) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = protocol.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DetailMetricGrid(
                metrics =
                listOfNotNull(
                    stringResource(R.string.statistics_total_traffic) to formatBytes(context, protocol.totalBytes),
                    stringResource(R.string.statistics_success_rate) to formatPercent(protocol.successRate),
                    stringResource(R.string.statistics_error_rate) to formatPercent(protocol.errorRate),
                    protocol.avgLatencyMs?.let { stringResource(R.string.statistics_average_latency) to it.formatLatency() },
                    protocol.lastUsedAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
                ),
            )
        }
    }
}

@Composable
internal fun AppConnectionRowView(connection: AppConnectionRow) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = connection.countryCode?.countryFlagEmoji().orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = connection.remoteHost,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                listOfNotNull(
                    connection.countryName,
                    connection.city,
                    connection.remotePort?.let { port ->
                        stringResource(R.string.statistics_app_detail_port, port)
                    },
                    connection.protocol,
                    pluralStringResource(
                        R.plurals.statistics_app_detail_connection_count,
                        connection.count,
                        connection.count,
                    ),
                    connection.lastSeenAt.formatLastActivity(),
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatBytes(context, connection.bytes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun DetailMetricGrid(metrics: List<Pair<String, String>>) {
    if (metrics.isEmpty()) {
        EmptySectionText(text = stringResource(R.string.statistics_no_data))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        metrics.forEachIndexed { index, (label, value) ->
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index != metrics.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))
            }
        }
    }
}

internal data class ProtocolAccumulator(
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
    var lastUsedAt: Long? = null,
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { latency -> latency > 0L }?.let(latencies::add)
        lastUsedAt = maxOfNotNull(lastUsedAt, memory.lastSuccessAt, memory.lastFailureAt, memory.lastValidatedAt, memory.lastTrafficAt)
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
        lastUsedAt = maxOfNotNull(lastUsedAt, item.updatedAt.takeIf { it > 0L })
    }

    fun toProtocolItem(protocol: ProtocolHint): ProtocolStatisticsUiItem {
        val quality =
            if (successCount + failureCount == 0 && rxBytes + txBytes > 0L) {
                ProtocolQuality.TRAFFIC_ONLY
            } else {
                ProtocolQuality.MEASURED
            }
        return ProtocolStatisticsUiItem(
            protocol = protocol,
            successCount = successCount,
            failureCount = failureCount,
            rxBytes = rxBytes,
            txBytes = txBytes,
            avgLatencyMs = latencies.averageOrNull(),
            lastUsedAt = lastUsedAt,
            quality = quality,
        )
    }
}

internal data class ComparisonAccumulator(
    val profileId: Long,
    val profileName: String,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { latency -> latency > 0L }?.let(latencies::add)
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
    }

    fun toSide(): ProfileComparisonSideUiItem {
        return ProfileComparisonSideUiItem(
            profileId = profileId,
            profileName = profileName,
            successCount = successCount,
            failureCount = failureCount,
            rxBytes = rxBytes,
            txBytes = txBytes,
            avgLatencyMs = latencies.averageOrNull(),
        )
    }
}

internal fun List<ProfileTrafficUiItem>.singleKnownTransportOrUnknown(): TransportProtocol =
    map(ProfileTrafficUiItem::transport)
        .filterNot { transport -> transport == TransportProtocol.UNKNOWN }
        .distinct()
        .singleOrNull()
        ?: TransportProtocol.UNKNOWN

@Composable
internal fun String?.asPercentText(): String =
    this
        ?.toFloatOrNull()
        ?.let(::formatPercent)
        ?: stringResource(R.string.statistics_no_data)

internal val StatisticsRetention.durationMs: Long?
    get() =
        when (this) {
            StatisticsRetention.WEEK -> 7L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTH -> 31L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTHS_3 -> 93L * 24L * 60L * 60L * 1000L
            StatisticsRetention.FOREVER -> null
        }

internal val StatisticsDisplayRange.durationMs: Long?
    get() =
        when (this) {
            StatisticsDisplayRange.HOURS_24 -> 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.WEEK -> 7L * 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.MONTH -> 31L * 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.ALL -> null
        }

internal fun StatisticsDisplayRange.toStatisticsRetention(): StatisticsRetention =
    when (this) {
        StatisticsDisplayRange.HOURS_24 -> StatisticsRetention.WEEK
        StatisticsDisplayRange.WEEK -> StatisticsRetention.WEEK
        StatisticsDisplayRange.MONTH -> StatisticsRetention.MONTH
        StatisticsDisplayRange.ALL -> StatisticsRetention.FOREVER
    }

internal fun StatisticsDisplayRange.toStatsRange(): com.foxhole.beta.core.statistics.StatsRange =
    when (this) {
        StatisticsDisplayRange.HOURS_24 -> com.foxhole.beta.core.statistics.StatsRange.HOURS_24
        StatisticsDisplayRange.WEEK -> com.foxhole.beta.core.statistics.StatsRange.DAYS_7
        StatisticsDisplayRange.MONTH -> com.foxhole.beta.core.statistics.StatsRange.DAYS_31
        StatisticsDisplayRange.ALL -> com.foxhole.beta.core.statistics.StatsRange.ALL
    }

internal fun StatisticsDisplayRange.policy(
    firstAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): com.foxhole.beta.core.statistics.StatsRangePolicy =
    com.foxhole.beta.core.statistics.statsRangePolicy(
        range = toStatsRange(),
        firstAtMs = firstAtMs,
        nowMs = nowMs,
    )

internal fun <T> List<T>.filterForDisplayRange(
    range: StatisticsDisplayRange,
    nowMs: Long,
    timestamp: (T) -> Long,
): List<T> {
    val cutoff = range.durationMs?.let { duration -> nowMs - duration } ?: return this
    return filter { item -> timestamp(item) >= cutoff }
}

internal fun List<AppTrafficWindow>.durationForAllRange(
    now: Long,
    bucketMs: Long,
    maxBuckets: Int,
): Long {
    val first = minOfOrNull(AppTrafficWindow::startedAtMs) ?: return 24L * 60L * 60L * 1000L
    return (now - first)
        .coerceAtLeast(bucketMs)
        .coerceAtMost(bucketMs * maxBuckets)
}

internal fun String.connectionHost(): String {
    val value = trim().removePrefix("/")
    return if (value.startsWith("[")) {
        value.substringAfter("[").substringBefore("]").ifBlank { value }
    } else {
        val colonCount = value.count { character -> character == ':' }
        when {
            colonCount == 1 -> value.substringBefore(":")
            colonCount > 1 -> value.substringBefore("%")
            else -> value
        }.ifBlank { value }
    }
}

internal fun String.countryFlagEmoji(): String {
    val normalized = normalizedCountryCode(this) ?: return ""
    return normalized
        .map { character -> Character.toChars(0x1F1E6 + (character.code - 'A'.code)).concatToString() }
        .joinToString("")
}

internal fun Profile.statisticsProtocolHints(): List<ProtocolHint> {
    val optionHints = protocolOptions.map(ProfileProtocolOption::protocolHint)
    return (optionHints + protocolHint).distinct()
}

internal fun Profile.runtimeProtocolHint(): ProtocolHint =
    selectedProtocolOptionId
        ?.takeIf(String::isNotBlank)
        ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?.protocolHint
        ?: protocolOptions.firstOrNull { option -> option.isSelected }?.protocolHint
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint

internal fun StatisticsRetention.toStatisticsRange(): StatisticsRange =
    when (this) {
        StatisticsRetention.WEEK -> StatisticsRange.WEEK
        StatisticsRetention.MONTH -> StatisticsRange.MONTH
        StatisticsRetention.MONTHS_3 -> StatisticsRange.MONTHS_3
        StatisticsRetention.FOREVER -> StatisticsRange.FOREVER
    }

@Composable
internal fun Long?.formatLatency(): String =
    this?.let { latency -> stringResource(R.string.statistics_latency_ms, latency) }
        ?: stringResource(R.string.statistics_no_data)

@Composable
internal fun Long?.formatLastActivity(): String =
    this?.takeIf { timestamp -> timestamp > 0L }?.let { timestamp ->
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } ?: stringResource(R.string.statistics_no_data)
