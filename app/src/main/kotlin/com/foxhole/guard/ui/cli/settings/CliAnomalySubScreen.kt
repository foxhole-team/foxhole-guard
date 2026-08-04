package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AnomalyHistoryRetention
import com.foxhole.core.model.AnomalySensitivity
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onAnalyzeBackgroundTrafficChanged
import com.foxhole.guard.ui.onAnalyzeDestinationCountriesChanged
import com.foxhole.guard.ui.onAnomalyEnabledChanged
import com.foxhole.guard.ui.onAnomalyHistoryRetentionSelected
import com.foxhole.guard.ui.onAnomalySensitivitySelected
import com.foxhole.guard.ui.onNewAppQuarantineChanged
import com.foxhole.guard.ui.onNotifyUnusualTrafficChanged

/** Anomaly detection + app-install monitoring, mirroring the classic security grouping. */
@Composable
internal fun CliAnomalySubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val settings = state.settings

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_anomaly), icon = R.drawable.pix_shield)
        CliPanel(
            title = stringResource(R.string.cli_anomaly_monitoring_title),
            icon = R.drawable.pix_status,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Derived checked-state, donor contract: monitoring rides the statistics store.
            CliToggleRow(
                label = stringResource(R.string.cli_anomaly_monitor_installs),
                checked = settings.statistics.enabled && settings.statistics.appChangesEnabled,
                onToggle = viewModel::onInstalledAppMonitoringChanged,
            )
            CliToggleRow(
                label = stringResource(R.string.cli_anomaly_quarantine),
                checked = settings.expert.newAppQuarantineEnabled,
                onToggle = viewModel::onNewAppQuarantineChanged,
                note = stringResource(R.string.cli_anomaly_quarantine_note),
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(
            icon = R.drawable.pix_shield,
            title = stringResource(R.string.cli_anomaly_title),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_anomaly_detection),
                checked = settings.anomaly.enabled,
                onToggle = viewModel::onAnomalyEnabledChanged,
            )
            if (settings.anomaly.enabled) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_notify),
                    checked = settings.anomaly.notifyUnusualTraffic,
                    onToggle = viewModel::onNotifyUnusualTrafficChanged,
                )
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_sensitivity),
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
                    checked = settings.anomaly.analyzeBackgroundTraffic,
                    onToggle = viewModel::onAnalyzeBackgroundTrafficChanged,
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_countries),
                    checked = settings.anomaly.analyzeDestinationCountries,
                    onToggle = viewModel::onAnalyzeDestinationCountriesChanged,
                )
                CliDropdownRow(
                    label = stringResource(R.string.cli_anomaly_retention),
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
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

private fun retentionLabel(retention: AnomalyHistoryRetention): String = when (retention) {
    AnomalyHistoryRetention.HOURS_24 -> "24h"
    AnomalyHistoryRetention.DAYS_7 -> "7d"
    AnomalyHistoryRetention.DAYS_30 -> "30d"
}
