package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.ProfileTrafficUiItem
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Immutable
internal data class CliStatsProfileRow(
    val profileId: Long,
    val profileName: String,
    val rxBytes: Long,
    val txBytes: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/**
 * One row per profile. A smart profile persists one traffic total per protocol option, so profile
 * identity — not option/protocol identity — is the aggregation boundary for this table.
 */
internal fun cliStatsProfileRows(items: List<ProfileTrafficUiItem>): List<CliStatsProfileRow> =
    items
        .groupBy(ProfileTrafficUiItem::profileId)
        .values
        .map { profileItems ->
            val latest = profileItems.maxBy(ProfileTrafficUiItem::updatedAt)
            CliStatsProfileRow(
                profileId = latest.profileId,
                profileName = latest.profileName,
                rxBytes = profileItems.sumOf { item -> item.rxBytes.coerceAtLeast(0L) },
                txBytes = profileItems.sumOf { item -> item.txBytes.coerceAtLeast(0L) },
            )
        }
        .sortedWith(
            compareByDescending<CliStatsProfileRow>(CliStatsProfileRow::totalBytes)
                .thenBy(CliStatsProfileRow::profileName),
        )

@Composable
internal fun CliStatsProfileTablePanel(items: List<ProfileTrafficUiItem>) {
    val rows = remember(items) { cliStatsProfileRows(items).take(PROFILE_ROWS_MAX) }
    if (rows.isEmpty()) return
    val colors = LocalCliColors.current
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliPanel(
        icon = R.drawable.pix_profiles,
        title = stringResource(R.string.cli_stats_profiles_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = PROFILE_TABLE_ROW_PADDING)) {
            CliProfileCell(
                value = stringResource(R.string.cli_stats_table_profile),
                weight = PROFILE_NAME_WEIGHT,
                align = TextAlign.Start,
                color = colors.dim,
            )
            CliProfileCell(
                value = stringResource(R.string.cli_stats_table_received),
                weight = PROFILE_VALUE_WEIGHT,
                align = TextAlign.End,
                color = colors.dim,
            )
            CliProfileCell(
                value = stringResource(R.string.cli_stats_table_sent),
                weight = PROFILE_VALUE_WEIGHT,
                align = TextAlign.End,
                color = colors.dim,
            )
            CliProfileCell(
                value = stringResource(R.string.cli_stats_table_total),
                weight = PROFILE_VALUE_WEIGHT,
                align = TextAlign.End,
                color = colors.dim,
            )
        }
        rows.forEach { row ->
            CliRowDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = PROFILE_TABLE_ROW_PADDING)) {
                CliProfileCell(row.profileName, PROFILE_NAME_WEIGHT, TextAlign.Start)
                CliProfileCell(CliFormat.bytes(row.rxBytes), PROFILE_VALUE_WEIGHT, TextAlign.End)
                CliProfileCell(CliFormat.bytes(row.txBytes), PROFILE_VALUE_WEIGHT, TextAlign.End)
                CliProfileCell(CliFormat.bytes(row.totalBytes), PROFILE_VALUE_WEIGHT, TextAlign.End)
            }
        }
    }
}

@Composable
private fun RowScope.CliProfileCell(
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

private const val PROFILE_ROWS_MAX = 6
private const val PROFILE_NAME_WEIGHT = 1.45f
private const val PROFILE_VALUE_WEIGHT = 1f
private val PROFILE_TABLE_ROW_PADDING = 3.dp
