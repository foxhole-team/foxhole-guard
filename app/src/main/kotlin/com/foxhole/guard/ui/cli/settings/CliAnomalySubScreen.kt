package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalyHistoryRetention
import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.userFacingMessage
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliScaledDp
import com.foxhole.guard.ui.cli.components.CLI_DISCLOSURE_GLYPH_SIZE
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.ROW_VALUE_GLYPH_GAP
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.onAnalyzeBackgroundTrafficChanged
import com.foxhole.guard.ui.onAnalyzeDestinationCountriesChanged
import com.foxhole.guard.ui.onAnomalyHistoryRetentionSelected
import com.foxhole.guard.ui.onAnomalySensitivitySelected
import com.foxhole.guard.ui.onNotifyUnusualTrafficChanged
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CliAnomalySubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    val observedPackages = remember(
        state.appTrafficWindows,
        state.networkActivityEvents,
        state.anomalyEvents,
    ) {
        observedSentinelPackages(
            appTrafficWindows = state.appTrafficWindows,
            networkActivityEvents = state.networkActivityEvents,
            anomalyEvents = state.anomalyEvents,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_anomaly), icon = R.drawable.pix_shield)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliPanel(
                title = stringResource(R.string.cli_anomaly_monitoring_title),
                icon = R.drawable.pix_status,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_monitor_installs),
                    icon = R.drawable.pix_journal,
                    checked = settings.statistics.enabled && settings.statistics.appChangesEnabled,
                    onToggle = viewModel::onInstalledAppMonitoringChanged,
                )
                CliRowDivider()
                SentinelObservedAppsTable(
                    packageNames = observedPackages,
                    installedApps = state.installedApps,
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            SentinelDetectionsTable(
                events = state.anomalyEvents.sortedByDescending(AnomalyEvent::createdAtMs),
                installedApps = state.installedApps,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliPanel(
                icon = R.drawable.pix_shield,
                title = stringResource(R.string.cli_anomaly_title),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_notify),
                    icon = R.drawable.pix_info,
                    checked = settings.anomaly.notifyUnusualTraffic,
                    onToggle = viewModel::onNotifyUnusualTrafficChanged,
                )
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_sensitivity),
                    icon = R.drawable.pix_up,
                    value = settings.anomaly.sensitivity.name.lowercase(),
                    options = AnomalySensitivity.entries.map { sensitivity ->
                        CliDropdownOption(id = sensitivity.name, label = sensitivity.name.lowercase())
                    },
                    selectedId = settings.anomaly.sensitivity.name,
                    onSelect = { id ->
                        viewModel.onAnomalySensitivitySelected(AnomalySensitivity.valueOf(id))
                    },
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_background),
                    icon = R.drawable.pix_apps,
                    checked = settings.anomaly.analyzeBackgroundTraffic,
                    onToggle = viewModel::onAnalyzeBackgroundTrafficChanged,
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_countries),
                    icon = R.drawable.pix_map,
                    checked = settings.anomaly.analyzeDestinationCountries,
                    onToggle = viewModel::onAnalyzeDestinationCountriesChanged,
                )
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_retention),
                    icon = R.drawable.pix_clock,
                    value = retentionLabel(settings.anomaly.historyRetention),
                    options = AnomalyHistoryRetention.entries.map { retention ->
                        CliDropdownOption(id = retention.name, label = retentionLabel(retention))
                    },
                    selectedId = settings.anomaly.historyRetention.name,
                    onSelect = { id ->
                        viewModel.onAnomalyHistoryRetentionSelected(AnomalyHistoryRetention.valueOf(id))
                    },
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun SentinelObservedAppsTable(
    packageNames: List<String>,
    installedApps: List<InstalledAppOption>,
) {
    val colors = LocalCliColors.current
    val installed = remember(installedApps) { installedApps.associateBy(InstalledAppOption::packageName) }
    val userInstalled = remember(packageNames, installed) {
        packageNames.filter { name -> installed[name]?.isSystemApp == false }
    }
    val systemCount = packageNames.size - userInstalled.size
    var detail by remember { mutableStateOf<InstalledAppOption?>(null) }
    detail?.let { app ->
        SentinelAppDetailSheet(app = app, onDismiss = { detail = null })
    }
    SentinelObservedAppsHeader()
    CliRowDivider()
    if (userInstalled.isEmpty()) {
        Text(
            text = stringResource(R.string.cli_sentinel_apps_empty),
            style = CliType.small,
            color = colors.faint,
            modifier = Modifier.padding(vertical = CliSpacing.xs),
        )
    } else {
        userInstalled.forEachIndexed { index, packageName ->
            if (index > 0) CliRowDivider()
            SentinelObservedAppRow(
                packageName = packageName,
                app = installed[packageName],
                onSelect = { installed[packageName]?.let { app -> detail = app } },
            )
        }
    }
    if (systemCount > 0) {
        CliElbowLine(text = stringResource(R.string.cli_sentinel_apps_system_note, systemCount))
    }
}

@Composable
private fun SentinelObservedAppsHeader() {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(SENTINEL_ICON_COLUMN_WIDTH))
        Text(
            text = stringResource(R.string.cli_sentinel_apps),
            style = CliType.small,
            color = colors.faint,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cli_sentinel_apps_installed),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SENTINEL_INSTALL_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(ROW_VALUE_GLYPH_GAP))
        Spacer(modifier = Modifier.width(CLI_DISCLOSURE_GLYPH_SIZE))
    }
}

@Composable
private fun SentinelObservedAppRow(
    packageName: String,
    app: InstalledAppOption?,
    onSelect: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .cliPressable(enabled = app != null, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(SENTINEL_ICON_COLUMN_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            SentinelAppIcon(packageName = packageName, app = app, size = SENTINEL_TABLE_ICON_SIZE)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app?.label ?: packageName,
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packageName,
                style = CliType.small,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = sentinelShortDate(app?.firstInstallTime),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SENTINEL_INSTALL_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(ROW_VALUE_GLYPH_GAP))
        CliDisclosureGlyph(expanded = false, color = colors.accent)
    }
}

@Composable
private fun SentinelAppDetailSheet(
    app: InstalledAppOption,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        title = app.label,
        icon = R.drawable.pix_apps,
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps),
            value = app.label,
            icon = R.drawable.pix_apps,
            valueLeading = {
                SentinelAppIcon(
                    packageName = app.packageName,
                    app = app,
                    size = SENTINEL_TABLE_ICON_SIZE,
                )
            },
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps_package),
            value = app.packageName,
            icon = R.drawable.pix_link,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps_installed),
            value = sentinelLongDate(app.firstInstallTime),
            valueColor = colors.fg,
            icon = R.drawable.pix_clock,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps_source),
            value = app.installerPackageName
                ?: stringResource(R.string.installed_app_source_unknown),
            icon = R.drawable.pix_import,
        )
    }
}

private fun sentinelShortDate(timestamp: Long?): String =
    timestamp?.takeIf { value -> value > 0L }
        ?.let { value -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(value)) }
        ?: "\u2014"

private fun sentinelLongDate(timestamp: Long?): String =
    timestamp?.takeIf { value -> value > 0L }
        ?.let { value -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value)) }
        ?: "\u2014"

@Composable
private fun SentinelDetectionsTable(
    events: List<AnomalyEvent>,
    installedApps: List<InstalledAppOption>,
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    val installed = remember(installedApps) { installedApps.associateBy(InstalledAppOption::packageName) }
    CliPanel(
        title = stringResource(R.string.cli_sentinel_detections_title),
        icon = R.drawable.pix_status,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_stats_anomalies_empty),
                style = CliType.small,
                color = colors.dim,
            )
            return@CliPanel
        }
        SentinelDetectionHeader()
        CliRowDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(SENTINEL_EVENT_ROW_HEIGHT * sentinelVisibleDetectionRowCount(events.size)),
        ) {
            itemsIndexed(events) { index, event ->
                if (index > 0) CliRowDivider()
                val packageName = event.packageName
                val app = packageName?.let(installed::get)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SENTINEL_EVENT_ROW_HEIGHT)
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(event.type.sentinelLabelRes()),
                        style = CliType.small,
                        color = colors.dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(SENTINEL_TYPE_WEIGHT),
                    )
                    Box(
                        modifier = Modifier.width(SENTINEL_ICON_COLUMN_WIDTH),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (packageName != null) {
                            SentinelAppIcon(
                                packageName = packageName,
                                app = app,
                                size = SENTINEL_TABLE_ICON_SIZE,
                            )
                        } else {
                            Text(text = "—", style = CliType.small, color = colors.faint)
                        }
                    }
                    Text(
                        text = app?.label ?: packageName ?: "—",
                        style = CliType.small,
                        color = colors.fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(SENTINEL_APP_WEIGHT),
                    )
                    Text(
                        text = event.userFacingMessage(context),
                        style = CliType.small,
                        color = colors.fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(SENTINEL_EVENT_WEIGHT),
                    )
                }
            }
        }
    }
}

@Composable
private fun SentinelDetectionHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        SentinelHeaderCell(R.string.cli_sentinel_column_type, Modifier.weight(SENTINEL_TYPE_WEIGHT))
        SentinelHeaderCell(R.string.cli_sentinel_column_icon, Modifier.width(SENTINEL_ICON_COLUMN_WIDTH))
        SentinelHeaderCell(R.string.cli_sentinel_column_app, Modifier.weight(SENTINEL_APP_WEIGHT))
        SentinelHeaderCell(R.string.cli_sentinel_column_event, Modifier.weight(SENTINEL_EVENT_WEIGHT))
    }
}

@Composable
private fun SentinelHeaderCell(labelRes: Int, modifier: Modifier) {
    val colors = LocalCliColors.current
    Text(
        text = stringResource(labelRes),
        style = CliType.small,
        color = colors.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun SentinelAppIcon(
    packageName: String,
    app: InstalledAppOption?,
    size: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalCliColors.current
    val icon = rememberCliAppIcon(
        packageName = packageName,
        versionCode = app?.versionCode,
        lastUpdateTime = app?.lastUpdateTime,
        bitmapSize = size,
    )
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = app?.label ?: packageName,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        CliPixIcon(
            id = R.drawable.pix_apps,
            contentDescription = app?.label ?: packageName,
            size = size,
            tint = colors.dim,
        )
    }
}

internal fun observedSentinelPackages(
    appTrafficWindows: List<AppTrafficWindow>,
    networkActivityEvents: List<NetworkActivityEvent>,
    anomalyEvents: List<AnomalyEvent>,
): List<String> {
    val lastSeen = mutableMapOf<String, Long>()
    appTrafficWindows
        .filter { window -> window.totalBytes > 0L }
        .forEach { window -> lastSeen.mergeLatest(window.packageName, window.startedAtMs) }
    networkActivityEvents.forEach { event ->
        event.packageNames.forEach { packageName -> lastSeen.mergeLatest(packageName, event.timestampMs) }
    }
    anomalyEvents.forEach { event ->
        event.packageName?.let { packageName -> lastSeen.mergeLatest(packageName, event.createdAtMs) }
    }
    return lastSeen.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map(Map.Entry<String, Long>::key)
}

internal fun sentinelVisibleDetectionRowCount(eventCount: Int): Int =
    eventCount.coerceIn(0, SENTINEL_VISIBLE_EVENT_ROWS)

private fun MutableMap<String, Long>.mergeLatest(packageName: String, timestampMs: Long) {
    if (packageName.isBlank()) return
    this[packageName] = maxOf(this[packageName] ?: Long.MIN_VALUE, timestampMs)
}

private fun AnomalyType.sentinelLabelRes(): Int = when (this) {
    AnomalyType.APP_UPLOAD_SPIKE -> R.string.cli_sentinel_type_upload
    AnomalyType.APP_BACKGROUND_TRAFFIC -> R.string.cli_sentinel_type_background
    AnomalyType.TOTAL_TRAFFIC_SPIKE -> R.string.cli_sentinel_type_total
    AnomalyType.NEW_DESTINATION_COUNTRY -> R.string.cli_sentinel_type_country
    AnomalyType.DNS_BLOCK_RATIO_SPIKE -> R.string.cli_sentinel_type_dns
    AnomalyType.RECONNECT_STORM -> R.string.cli_sentinel_type_reconnect
    AnomalyType.LATENCY_SHIFT -> R.string.cli_sentinel_type_latency
    AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH -> R.string.cli_sentinel_type_legacy_route
    AnomalyType.DORMANT_APP_NETWORK_ACTIVITY -> R.string.cli_sentinel_type_dormant
    AnomalyType.KNOWN_THREAT_DESTINATION -> R.string.cli_sentinel_type_threat
}

private fun retentionLabel(retention: AnomalyHistoryRetention): String = when (retention) {
    AnomalyHistoryRetention.HOURS_24 -> "24h"
    AnomalyHistoryRetention.DAYS_7 -> "7d"
    AnomalyHistoryRetention.DAYS_30 -> "30d"
}

private val SENTINEL_INSTALL_COLUMN_WIDTH = cliScaledDp(64f)
private val SENTINEL_TABLE_ICON_SIZE = 20.dp
private val SENTINEL_ICON_COLUMN_WIDTH = 28.dp
private val SENTINEL_EVENT_ROW_HEIGHT = 52.dp
private const val SENTINEL_VISIBLE_EVENT_ROWS = 10
private const val SENTINEL_TYPE_WEIGHT = 0.8f
private const val SENTINEL_APP_WEIGHT = 0.9f
private const val SENTINEL_EVENT_WEIGHT = 1.6f
