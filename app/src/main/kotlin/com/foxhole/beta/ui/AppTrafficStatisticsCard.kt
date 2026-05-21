package com.foxhole.beta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import com.foxhole.beta.ui.statistics.charts.chartColor
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
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.statistics_apps_top_title),
                trailing = {
                    StatisticsRangePillDropdown(
                        value = range,
                        expanded = rangeExpanded,
                        onExpandedChange = { rangeExpanded = it },
                        onSelect = onRangeSelected,
                    )
                },
            )
            if (!usageAccessGranted) {
                Text(
                    text = stringResource(R.string.statistics_usage_access_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenUsageAccess) {
                    Text(stringResource(R.string.statistics_usage_access_action))
                }
            } else if (!enabled) {
                Text(
                    text = stringResource(R.string.app_statistics_disabled_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (rows.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.app_statistics_empty))
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
                AppTrafficTimelineChart(samples = samples, range = range, nowMs = nowMs)
            }
        }
    }
}

@Composable
internal fun AppTrafficRowView(
    row: AppTrafficRow,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = row.packageName, modifier = Modifier.size(42.dp))
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatBytes(context, tx),
                style = MaterialTheme.typography.labelMedium,
                color = txColor,
            )
            Text(formatBytes(context, rx), style = MaterialTheme.typography.labelMedium, color = rxColor)
        }
        Text(
            formatBytes(context, tx + rx),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
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
    ) {
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
                        key = { connection -> "${connection.remote}:${connection.protocol}" },
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
        DetailMetricGrid(
            metrics =
            listOfNotNull(
                metricIfPositive(
                    stringResource(R.string.home_total_label),
                    row.totalBytes,
                    formatBytes(context, row.totalBytes),
                ),
                metricIfPositive(
                    stringResource(R.string.traffic_received),
                    row.rxBytes,
                    formatBytes(context, row.rxBytes),
                ),
                metricIfPositive(
                    stringResource(R.string.traffic_sent),
                    row.txBytes,
                    formatBytes(context, row.txBytes),
                ),
                appSamples.maxOfOrNull(AppTrafficWindow::startedAtMs)
                    ?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
            ),
        )
        if (appSamples.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_chart_axes_app_timeline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTrafficMiniChart(samples = appSamples)
            ChartLegend()
        }
        val loadedConnectionRows = connectionRows ?: return@Column
        if (loadedConnectionRows.isNotEmpty()) {
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
        }
    }
}

internal data class AppConnectionRow(
    val remote: String,
    val ipAddress: String,
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val protocol: String,
    val count: Int,
    val bytes: Long,
    val lastSeenAt: Long,
)

internal fun appConnectionRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    resolver: TorGeoIpCountryResolver,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    appConnectionEventRows(packageName, events, resolver, ipInfo)
        .groupBy { row -> row.remote to row.protocol }
        .map { (key, rows) ->
            val latest = rows.maxBy(AppConnectionRow::lastSeenAt)
            AppConnectionRow(
                remote = key.first,
                ipAddress = latest.ipAddress,
                countryCode = latest.countryCode,
                countryName = latest.countryName,
                city = latest.city,
                protocol = key.second,
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
    events
        .asSequence()
        .filter { event -> packageName in event.packageNames }
        .mapNotNull { event ->
            val remoteHost = event.remoteHost.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val remote =
                event.remotePort
                    ?.takeIf { port -> port in 1..65535 }
                    ?.let { port -> "$remoteHost:$port" }
                    ?: remoteHost
            val ipAddress = remoteHost.connectionHost()
            val countryCode =
                event.countryCode
                    ?: resolver.countryCodeForDestination(remoteHost)
                    ?: ipInfo?.takeIf { info ->
                        ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6
                    }?.countryCode
            val countryName = countryCode?.let(::countryDisplayName)
            val city =
                ipInfo
                    ?.takeIf { info -> ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6 }
                    ?.city
            AppConnectionRow(
                remote = remote,
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
