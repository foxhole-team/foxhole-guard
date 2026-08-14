package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.ui.StatisticsRouteUiState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon

/** Group (5): the same fixed app / received / sent / total table grammar as other statistics. */
@Composable
internal fun CliStatsAppPanel(state: StatisticsRouteUiState) {
    val rows = remember(state.statisticsDashboard.appRows) {
        state.statisticsDashboard.appRows
            .filter { row -> row.totalBytes > 0 }
            .sortedByDescending { row -> row.totalBytes }
            .take(APP_ROWS_MAX)
    }
    if (rows.isEmpty()) return
    val colors = LocalCliColors.current
    val installedIndex = remember(state.installedApps) {
        state.installedApps.associateBy { app -> app.packageName }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        icon = R.drawable.pix_apps,
        title = stringResource(R.string.cli_stats_apps_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = APP_TABLE_ROW_PADDING)) {
            CliAppTrafficCell(stringResource(R.string.cli_sentinel_column_app), APP_NAME_WEIGHT, TextAlign.Start)
            CliAppTrafficCell(stringResource(R.string.cli_stats_table_received), APP_VALUE_WEIGHT, TextAlign.End)
            CliAppTrafficCell(stringResource(R.string.cli_stats_table_sent), APP_VALUE_WEIGHT, TextAlign.End)
            CliAppTrafficCell(stringResource(R.string.cli_stats_table_total), APP_VALUE_WEIGHT, TextAlign.End)
        }
        rows.forEach { row ->
            CliRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = APP_TABLE_ROW_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val installed = installedIndex[row.packageName]
                val icon = rememberCliAppIcon(
                    packageName = row.packageName,
                    versionCode = installed?.versionCode,
                    lastUpdateTime = installed?.lastUpdateTime,
                    bitmapSize = APP_ICON_SIZE,
                )
                Row(
                    modifier = Modifier.weight(APP_NAME_WEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(APP_ICON_SIZE))
                    } else {
                        Box(modifier = Modifier.size(APP_ICON_SIZE))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = row.label.ifEmpty { row.packageName },
                        style = CliType.small,
                        color = if (row.badges.isEmpty()) colors.fg else colors.warn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                CliAppTrafficCell(CliFormat.bytes(row.rxBytes), APP_VALUE_WEIGHT, TextAlign.End)
                CliAppTrafficCell(CliFormat.bytes(row.txBytes), APP_VALUE_WEIGHT, TextAlign.End)
                CliAppTrafficCell(CliFormat.bytes(row.totalBytes), APP_VALUE_WEIGHT, TextAlign.End)
            }
        }
    }
}

@Composable
private fun RowScope.CliAppTrafficCell(
    value: String,
    weight: Float,
    alignment: TextAlign,
) {
    Text(
        text = value,
        style = CliType.small,
        color = LocalCliColors.current.dim,
        textAlign = alignment,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

private const val APP_ROWS_MAX = 6
private const val APP_NAME_WEIGHT = 1.45f
private const val APP_VALUE_WEIGHT = 1f
private val APP_ICON_SIZE = 16.dp
private val APP_TABLE_ROW_PADDING = 3.dp
