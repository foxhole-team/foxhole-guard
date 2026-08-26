package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Composable
internal fun CliStatsVpnProtocolTablePanel(
    window: StatisticsWindow,
    rows: List<CliStatsVpnProtocolRow>,
    onSelectWindow: (StatisticsWindow) -> Unit,
) {
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        icon = R.drawable.lin_shield,
        title = stringResource(R.string.cli_stats_protocols_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliStatsWindowSelector(window = window, onSelectWindow = onSelectWindow)
        CliRowDivider()
        CliStatsTableHeader(latency = true)
        if (rows.isEmpty()) {
            CliStatsEmptyTableRow()
        }
        rows.take(VPN_TABLE_ROWS_MAX).forEach { row ->
            CliRowDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                CliStatsCell(row.protocol, PROTOCOL_WEIGHT, TextAlign.Start)
                CliStatsCell(CliFormat.latency(row.avgLatencyMs), VALUE_WEIGHT, TextAlign.End)
                CliStatsCell(CliFormat.bytes(row.txBytes), VALUE_WEIGHT, TextAlign.End)
                CliStatsCell(CliFormat.bytes(row.rxBytes), VALUE_WEIGHT, TextAlign.End)
                CliStatsCell(CliFormat.bytes(row.rxBytes + row.txBytes), VALUE_WEIGHT, TextAlign.End)
            }
        }
    }
}

@Composable
internal fun CliStatsVpnTransportTablePanel(
    window: StatisticsWindow,
    rows: List<CliStatsVpnTransportRow>,
    onSelectWindow: (StatisticsWindow) -> Unit,
) {
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        icon = R.drawable.lin_link,
        title = stringResource(R.string.cli_stats_transports_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliStatsWindowSelector(window = window, onSelectWindow = onSelectWindow)
        CliRowDivider()
        CliStatsTableHeader(latency = false)
        if (rows.isEmpty()) {
            CliStatsEmptyTableRow()
        }
        rows.forEach { row ->
            CliRowDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                CliStatsCell(row.transport.name, PROTOCOL_WEIGHT, TextAlign.Start)
                CliStatsCell(
                    row.errorRateOrNull?.let(CliFormat::percent) ?: CLI_STATS_MISSING,
                    VALUE_WEIGHT,
                    TextAlign.End,
                )
                CliStatsCell(CliFormat.bytes(row.txBytes), VALUE_WEIGHT, TextAlign.End)
                CliStatsCell(CliFormat.bytes(row.rxBytes), VALUE_WEIGHT, TextAlign.End)
                CliStatsCell(CliFormat.bytes(row.rxBytes + row.txBytes), VALUE_WEIGHT, TextAlign.End)
            }
        }
    }
}

@Composable
private fun CliStatsWindowSelector(
    window: StatisticsWindow,
    onSelectWindow: (StatisticsWindow) -> Unit,
) {
    val day = stringResource(R.string.cli_stats_range_day)
    val week = stringResource(R.string.cli_stats_range_week)
    val month = stringResource(R.string.cli_stats_range_month)
    CliDropdownRow(
        label = stringResource(R.string.cli_stats_key_range),
        value = statisticsWindowLabel(window, day, week, month),
        options = StatisticsWindow.entries.map { item ->
            CliDropdownOption(
                id = item.name,
                label = statisticsWindowLabel(item, day, week, month),
            )
        },
        selectedId = window.name,
        onSelect = { value -> onSelectWindow(StatisticsWindow.valueOf(value)) },
    )
}

@Composable
private fun CliStatsTableHeader(latency: Boolean) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        CliStatsCell(stringResource(R.string.cli_stats_table_protocol), PROTOCOL_WEIGHT, TextAlign.Start, colors.dim)
        CliStatsCell(
            stringResource(if (latency) R.string.cli_stats_table_latency else R.string.cli_stats_table_errors),
            VALUE_WEIGHT,
            TextAlign.End,
            colors.dim,
        )
        CliStatsCell(stringResource(R.string.cli_stats_table_sent), VALUE_WEIGHT, TextAlign.End, colors.dim)
        CliStatsCell(stringResource(R.string.cli_stats_table_received), VALUE_WEIGHT, TextAlign.End, colors.dim)
        CliStatsCell(stringResource(R.string.cli_stats_table_total), VALUE_WEIGHT, TextAlign.End, colors.dim)
    }
}

@Composable
private fun CliStatsEmptyTableRow() {
    val colors = LocalCliColors.current
    Text(
        text = stringResource(R.string.cli_stats_source_none),
        style = CliType.small,
        color = colors.dim,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun RowScope.CliStatsCell(
    value: String,
    weight: Float,
    align: TextAlign,
    color: androidx.compose.ui.graphics.Color = LocalCliColors.current.fg,
) {
    Text(
        text = value,
        style = CliType.small,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

private const val VPN_TABLE_ROWS_MAX = 8
private const val PROTOCOL_WEIGHT = 1.35f
private const val VALUE_WEIGHT = 1f
private const val CLI_STATS_MISSING = "—"
