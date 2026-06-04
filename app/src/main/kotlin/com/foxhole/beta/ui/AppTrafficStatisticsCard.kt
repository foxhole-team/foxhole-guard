package com.foxhole.beta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.core.statistics.OTHER_PACKAGE
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import com.foxhole.beta.ui.statistics.StatisticsDashboardCard
import com.foxhole.beta.ui.statistics.StatisticsEmptyState
import com.foxhole.beta.ui.statistics.charts.chartColor
import com.foxhole.beta.ui.statistics.statisticsVisualTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppTrafficStatisticsCard(
    rows: List<AppTrafficRow>,
    samples: List<AppTrafficWindow>,
    nowMs: Long,
    allRowsCount: Int,
    enabled: Boolean,
    usageAccessGranted: Boolean,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onShowAll: () -> Unit,
    onRowClick: (AppTrafficRow) -> Unit,
) {
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    StatisticsDashboardCard(
        icon = Icons.Outlined.Apps,
        title = stringResource(R.string.statistics_apps_top_title),
        subtitle = stringResource(R.string.app_statistics_enabled_summary),
        trailing = {
            StatisticsRangePillDropdown(
                value = range,
                expanded = rangeExpanded,
                onExpandedChange = { rangeExpanded = it },
                onSelect = onRangeSelected,
            )
        },
    ) {
        if (!usageAccessGranted) {
            StatisticsEmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.statistics_usage_access_summary),
                actionLabel = stringResource(R.string.statistics_usage_access_action),
                onAction = onOpenUsageAccess,
            )
        } else if (!enabled) {
            StatisticsEmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.app_statistics_disabled_body),
            )
        } else if (rows.isEmpty()) {
            StatisticsEmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.app_statistics_empty),
            )
        } else {
            AppTrafficTopStackedChart(
                rows = rows,
                onRowClick = onRowClick,
            )
            if (allRowsCount > rows.size) {
                TextButton(onClick = onShowAll) {
                    Text(stringResource(R.string.show_all_label))
                }
            }
            Text(
                text = stringResource(R.string.statistics_chart_axes_app_timeline),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            AppTrafficTimelineChart(samples = samples, range = range, nowMs = nowMs)
        }
    }
}

@Composable
internal fun AppTrafficRowView(
    row: AppTrafficRow,
    onClick: (() -> Unit)? = null,
) {
    val tokens = statisticsVisualTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = tokens.dimens.rowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatisticsAppIcon(packageName = row.packageName, modifier = Modifier.size(36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                row.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnomalyBadges(row.badges)
        }
        TrafficCells(tx = row.txBytes, rx = row.rxBytes)
    }
}

@Composable
internal fun TrafficCells(tx: Long, rx: Long) {
    val context = LocalContext.current
    val txColor = chartColor(ChartColorToken.TX)
    val rxColor = chartColor(ChartColorToken.RX)
    val mutedText = statisticsVisualTokens().colors.mutedText
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            TrafficCellLine(color = txColor, text = formatBytes(context, tx), mutedText = mutedText)
            TrafficCellLine(color = rxColor, text = formatBytes(context, rx), mutedText = mutedText)
        }
        Text(
            formatBytes(context, tx + rx),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TrafficCellLine(
    color: androidx.compose.ui.graphics.Color,
    text: String,
    mutedText: androidx.compose.ui.graphics.Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(7.dp).clearAndSetSemantics {}) {
            drawCircle(color)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = mutedText,
            maxLines = 1,
        )
    }
}

@Composable
@Suppress("LongMethod")
internal fun AppTrafficDetail(
    row: AppTrafficRow,
    samples: List<AppTrafficWindow>,
    networkActivityEvents: List<NetworkActivityEvent>,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
    networkActivityLoggingEnabled: Boolean,
    showPrivateNetworkDetails: Boolean,
    onOpenNetworkActivityLogSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val resolver = remember(appContext) { TorGeoIpCountryResolver(appContext) }
    var allConnectionsVisible by rememberSaveable { mutableStateOf(false) }
    val appSamples = remember(row.packageName, samples) {
        samples.filter { sample -> sample.packageName == row.packageName }
    }
    val connectionRows by produceState<List<AppConnectionRow>?>(
        initialValue = null,
        row.packageName,
        networkActivityEvents,
        ipInfo,
        resolver,
        showPrivateNetworkDetails,
    ) {
        if (!showPrivateNetworkDetails) {
            value = emptyList()
            return@produceState
        }
        val packageName = row.packageName
        val events = networkActivityEvents
        val currentIpInfo = ipInfo
        value =
            withContext(Dispatchers.Default) {
                appConnectionRows(
                    packageName = packageName,
                    events = events,
                    resolver = resolver,
                    ipInfo = currentIpInfo,
                )
            }
    }
    if (allConnectionsVisible) {
        AlertDialog(
            onDismissRequest = { allConnectionsVisible = false },
            title = { Text(stringResource(R.string.statistics_app_detail_all_destinations)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        connectionRows.orEmpty(),
                        key = { connection -> connection.stableKey },
                    ) { connection ->
                        AppConnectionRowView(connection = connection)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allConnectionsVisible = false })
            },
        )
    }
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTrafficDetailHeader(row = row)
        DetailMetricGrid(
            metrics =
            listOf(
                stringResource(R.string.home_total_label) to formatBytes(context, row.totalBytes),
                appSamples.maxOfOrNull(AppTrafficWindow::startedAtMs)
                    ?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() }
                    ?: (stringResource(R.string.statistics_last_activity) to "0"),
            ),
        )
        if (appSamples.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_chart_axes_app_timeline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTrafficMiniChart(samples = appSamples)
        }
        val loadedConnectionRows = connectionRows
        Text(
            text = stringResource(R.string.statistics_app_detail_network_journal_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!networkActivityLoggingEnabled) {
            Text(
                text = stringResource(R.string.statistics_app_detail_network_log_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenNetworkActivityLogSettings) {
                Text(stringResource(R.string.statistics_app_detail_open_network_log_settings_action))
            }
        } else if (loadedConnectionRows == null) {
            AppConnectionRowsLoadingBlock()
        } else if (!showPrivateNetworkDetails && networkActivityEvents.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_app_detail_private_data_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (loadedConnectionRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_app_detail_top_destinations),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            loadedConnectionRows.take(STATISTICS_TOP_PREVIEW_LIMIT).forEach { connection ->
                AppConnectionRowView(connection = connection)
            }
            if (loadedConnectionRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
                TextButton(onClick = { allConnectionsVisible = true }) {
                    Text(stringResource(R.string.show_all_label))
                }
            }
        } else {
            EmptySectionText(text = stringResource(R.string.statistics_no_data))
        }
    }
}

@Composable
private fun AppTrafficDetailHeader(row: AppTrafficRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatisticsAppIcon(packageName = row.packageName, modifier = Modifier.size(38.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                row.packageName.takeUnless { it == OTHER_PACKAGE }
                    ?: stringResource(R.string.app_statistics_other_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppConnectionRowsLoadingBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxholeSkeletonBlock(modifier = Modifier.size(28.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    FoxholeSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(if (index == 0) 0.78f else 0.62f)
                            .height(10.dp),
                    )
                    FoxholeSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(if (index == 1) 0.88f else 0.72f)
                            .height(8.dp),
                    )
                }
                FoxholeSkeletonBlock(
                    modifier = Modifier
                        .width(48.dp)
                        .height(10.dp),
                )
            }
        }
    }
}

internal data class AppConnectionRow(
    val remoteHost: String,
    val remotePort: Int?,
    val ipAddress: String,
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val protocol: String,
    val count: Int,
    val bytes: Long,
    val lastSeenAt: Long,
) {
    val stableKey: String get() = listOf(remoteHost, remotePort.orEmptyKey(), protocol).joinToString("|")
}

internal fun appConnectionRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    resolver: TorGeoIpCountryResolver,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    appConnectionRows(
        packageName = packageName,
        events = events,
        countryCodeForDestination = resolver::countryCodeForDestination,
        ipInfo = ipInfo,
    )

internal fun appConnectionRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    countryCodeForDestination: (String) -> String?,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    appConnectionEventRows(
        packageName = packageName,
        events = events,
        countryCodeForDestination = countryCodeForDestination,
        ipInfo = ipInfo,
    )
        .filter { row -> row.bytes > 0L }
        .groupBy { row -> AppConnectionGroupKey(row.remoteHost, row.remotePort, row.protocol) }
        .map { (key, rows) ->
            val latest = rows.maxBy(AppConnectionRow::lastSeenAt)
            AppConnectionRow(
                remoteHost = key.remoteHost,
                remotePort = key.remotePort,
                ipAddress = latest.ipAddress,
                countryCode = latest.countryCode,
                countryName = latest.countryName,
                city = latest.city,
                protocol = key.protocol,
                count = rows.size,
                bytes = rows.sumOf(AppConnectionRow::bytes),
                lastSeenAt = rows.maxOf(AppConnectionRow::lastSeenAt),
            )
        }
        .sortedWith(
            compareByDescending<AppConnectionRow> { row -> row.bytes }
                .thenByDescending { row -> row.count }
                .thenByDescending { row -> row.lastSeenAt },
        )

internal fun appConnectionEventRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    resolver: TorGeoIpCountryResolver,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    appConnectionEventRows(
        packageName = packageName,
        events = events,
        countryCodeForDestination = resolver::countryCodeForDestination,
        ipInfo = ipInfo,
    )

internal fun appConnectionEventRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    countryCodeForDestination: (String) -> String?,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    events
        .asSequence()
        .filter { event -> packageName in event.packageNames }
        .mapNotNull { event ->
            val remoteHost = event.remoteHost.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val remotePort = event.remotePort?.takeIf { port -> port in 1..65535 }
            val ipAddress = remoteHost.connectionHost()
            val countryCode =
                event.countryCode
                    ?: countryCodeForDestination(remoteHost)
                    ?: ipInfo?.takeIf { info ->
                        ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6
                    }?.countryCode
            val countryName = countryCode?.let(::countryDisplayName)
            val city =
                ipInfo
                    ?.takeIf { info -> ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6 }
                    ?.city
            AppConnectionRow(
                remoteHost = remoteHost,
                remotePort = remotePort,
                ipAddress = ipAddress,
                countryCode = countryCode,
                countryName = countryName,
                city = city,
                protocol = event.protocol.ifBlank { "?" },
                count = 1,
                bytes = event.totalBytes.coerceAtLeast(0L),
                lastSeenAt = event.timestampMs,
            )
        }
        .sortedByDescending(AppConnectionRow::lastSeenAt)
        .toList()

private data class AppConnectionGroupKey(
    val remoteHost: String,
    val remotePort: Int?,
    val protocol: String,
)

private fun Int?.orEmptyKey(): String = this?.toString().orEmpty()
