package com.foxhole.guard.ui.cli.stats

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pTrafficPeriods
import com.foxhole.core.model.I2pTrafficTotals
import com.foxhole.core.model.i2pTrafficPeriods
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.StatisticsRouteUiState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Composable
internal fun CliStatsI2pPanel(
    state: StatisticsRouteUiState,
    home: HomeRouteUiState,
) {
    val phase = home.i2pPhase.phase
    val history = state.i2pTrafficHistory
    val relayOn = home.settings.i2p.relayTransitTraffic
    if (phase == I2pNetworkPhase.OFFLINE && history.lifetime.isEmpty) {
        return
    }
    val colors = LocalCliColors.current
    val periods = remember(history, state.statisticsDashboard.nowMs) {
        i2pTrafficPeriods(
            buckets = history.buckets,
            lifetime = history.lifetime,
            nowMs = state.statisticsDashboard.nowMs,
        )
    }
    CliPanel(
        title = stringResource(R.string.cli_stats_i2p_title),
        icon = R.drawable.pix_incognito,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_i2p_phase),
            value = phase.name.lowercase(),
            valueColor = if (phase.networkUp) colors.ok else colors.warn,
        )
        CliRowDivider()
        CliKeyValue(
            key = stringResource(R.string.cli_stats_key_i2p_relay),
            value = stringResource(
                if (relayOn) R.string.cli_stats_i2p_relay_on else R.string.cli_stats_i2p_relay_off,
            ),
            valueColor = if (relayOn) colors.ok else colors.dim,
        )
        CliRowDivider()
        CliStatsI2pPeriodRows(
            title = stringResource(R.string.cli_stats_key_i2p_traffic),
            periods = periods,
            select = I2pTrafficTotals::ownBytes,
        )
        if (relayOn || periods.allTime.transitBytes > 0L) {
            CliRowDivider()
            CliStatsI2pPeriodRows(
                title = stringResource(R.string.cli_stats_key_i2p_relay_traffic),
                periods = periods,
                select = I2pTrafficTotals::transitBytes,
                valueColor = colors.accent,
            )
        }
        if (relayOn) {
            CliElbowLine(
                text = stringResource(R.string.cli_stats_i2p_relay_thanks),
                color = colors.accent,
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.sm))
}

@Composable
private fun CliStatsI2pPeriodRows(
    title: String,
    periods: I2pTrafficPeriods,
    select: (I2pTrafficTotals) -> Long,
    valueColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    Text(text = title, style = CliType.small, color = colors.faint)
    CliKeyValue(
        key = stringResource(R.string.cli_stats_key_i2p_period_day),
        value = CliFormat.bytes(select(periods.day)),
        valueColor = valueColor,
    )
    CliRowDivider()
    CliKeyValue(
        key = stringResource(R.string.cli_stats_key_i2p_period_week),
        value = CliFormat.bytes(select(periods.week)),
        valueColor = valueColor,
    )
    CliRowDivider()
    CliKeyValue(
        key = stringResource(R.string.cli_stats_key_i2p_period_month),
        value = CliFormat.bytes(select(periods.month)),
        valueColor = valueColor,
    )
    CliRowDivider()
    CliKeyValue(
        key = stringResource(R.string.cli_stats_key_i2p_period_all),
        value = CliFormat.bytes(select(periods.allTime)),
        valueColor = valueColor,
    )
}
