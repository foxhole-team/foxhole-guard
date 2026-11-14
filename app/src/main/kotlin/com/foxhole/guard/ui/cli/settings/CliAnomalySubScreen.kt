package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalyHistoryRetention
import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.sentinel.SentinelObservationCards
import com.foxhole.guard.ui.cli.sentinel.InstalledAppAssessmentContent
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_anomaly), icon = R.drawable.lin_shield)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliPanel(
                title = stringResource(R.string.cli_anomaly_monitoring_title),
                icon = R.drawable.lin_status,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_monitor_installs),
                    icon = R.drawable.lin_journal,
                    checked = settings.statistics.enabled && settings.statistics.appChangesEnabled,
                    onToggle = viewModel::onInstalledAppMonitoringChanged,
                )
                CliElbowLine(text = stringResource(R.string.cli_sentinel_module_history_note))
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliPanel(
                icon = R.drawable.lin_shield,
                title = stringResource(R.string.cli_sentinel_network_security),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_notify),
                    icon = R.drawable.lin_info,
                    checked = settings.anomaly.notifyUnusualTraffic,
                    onToggle = viewModel::onNotifyUnusualTrafficChanged,
                )
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_sensitivity),
                    icon = R.drawable.lin_up,
                    value = stringResource(settings.anomaly.sensitivity.sensitivityResource()),
                    options = AnomalySensitivity.entries.map { sensitivity ->
                        CliDropdownOption(id = sensitivity.name, label = stringResource(sensitivity.sensitivityResource()))
                    },
                    selectedId = settings.anomaly.sensitivity.name,
                    onSelect = { id ->
                        viewModel.onAnomalySensitivitySelected(AnomalySensitivity.valueOf(id))
                    },
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_background),
                    icon = R.drawable.lin_apps,
                    checked = settings.anomaly.analyzeBackgroundTraffic,
                    onToggle = viewModel::onAnalyzeBackgroundTrafficChanged,
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_countries),
                    icon = R.drawable.lin_map,
                    checked = settings.anomaly.analyzeDestinationCountries,
                    onToggle = viewModel::onAnalyzeDestinationCountriesChanged,
                )
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliPanel(
                icon = R.drawable.lin_stats,
                title = stringResource(R.string.cli_sentinel_statistics),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_retention),
                    icon = R.drawable.lin_clock,
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


            CliSentinelScanSettings(viewModel)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
internal fun SentinelObservedAppsTable(
    packageNames: List<String>,
    installedApps: List<InstalledAppOption>,
) {
    val colors = LocalCliColors.current
    val installed = remember(installedApps) { installedApps.associateBy(InstalledAppOption::packageName) }
    val userInstalled = remember(packageNames, installed) {
        packageNames.filter { name -> installed[name]?.isSystemApp != true }
    }
    val systemCount = packageNames.count { installed[it]?.isSystemApp == true }
    var detail by remember { mutableStateOf<InstalledAppOption?>(null) }
    detail?.let { app ->
        SentinelAppDetailSheet(app = app, onDismiss = { detail = null })
    }
    Text(stringResource(R.string.cli_sentinel_apps), style = CliType.body)
    CliRowDivider()
    if (userInstalled.isEmpty() && systemCount == 0) {
        Text(
            text = stringResource(R.string.sentinel_assessment_activity_empty),
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
internal fun SentinelObservedAppRow(packageName: String, app: InstalledAppOption?, onSelect: () -> Unit) {
    val colors = LocalCliColors.current
    Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = CLI_MENU_ROW_MIN_HEIGHT)
        .cliPressable(enabled = app != null, onClick = onSelect).padding(vertical = CliSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SentinelAppIcon(packageName = packageName, app = app, size = SENTINEL_TABLE_ICON_SIZE)
            Text(app?.label ?: packageName, style = CliType.body, modifier = Modifier.padding(start = CliSpacing.sm))
        }
        Text(packageName, style = CliType.small, color = colors.dim)
        Text(sentinelShortDate(app?.firstInstallTime), style = CliType.small, color = colors.dim)
    }
}

@Composable
internal fun SentinelAppDetailSheet(
    app: InstalledAppOption,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        title = app.label,
        icon = R.drawable.lin_apps,
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps),
            value = app.label,
            icon = R.drawable.lin_apps,
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
            icon = R.drawable.lin_link,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps_installed),
            value = sentinelLongDate(app.firstInstallTime),
            valueColor = colors.fg,
            icon = R.drawable.lin_clock,
        )
        CliRowDivider()
        if (app.lastUpdateTime != null && app.lastUpdateTime != app.firstInstallTime) {
            CliKeyValue(
                key = stringResource(R.string.cli_sentinel_apps_updated),
                value = sentinelLongDate(app.lastUpdateTime),
                icon = R.drawable.lin_clock,
            )
            CliRowDivider()
        }
        CliKeyValue(
            key = stringResource(R.string.cli_sentinel_apps_source),
            value = app.installerPackageName
                ?: stringResource(R.string.sentinel_assessment_installer_unknown),
            icon = R.drawable.lin_import,
        )
        CliRowDivider()
        InstalledAppAssessmentContent(app.assessment)
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
internal fun SentinelDetectionsTable(events: List<AnomalyEvent>, installedApps: List<InstalledAppOption>, onOpenReport: ((String) -> Unit)? = null) {
    SentinelObservationCards(events, installedApps, onOpenReport)
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
        CliIcon(
            id = R.drawable.lin_apps,
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

private fun MutableMap<String, Long>.mergeLatest(packageName: String, timestampMs: Long) {
    if (packageName.isBlank()) return
    this[packageName] = maxOf(this[packageName] ?: Long.MIN_VALUE, timestampMs)
}

private fun retentionLabel(retention: AnomalyHistoryRetention): String = when (retention) {
    AnomalyHistoryRetention.HOURS_24 -> "24h"
    AnomalyHistoryRetention.DAYS_7 -> "7d"
    AnomalyHistoryRetention.DAYS_30 -> "30d"
}

private val SENTINEL_TABLE_ICON_SIZE = 20.dp

private fun AnomalySensitivity.sensitivityResource(): Int = when (this) {
    AnomalySensitivity.NORMAL -> R.string.sentinel_assessment_sensitivity_normal
    AnomalySensitivity.STRICT -> R.string.sentinel_assessment_sensitivity_strict
}
