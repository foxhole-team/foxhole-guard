package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.IpInfoRefreshReason
import com.foxhole.guard.ui.homeRouteTransitionRunning
import com.foxhole.guard.ui.dashboardVisibleIpInfo
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.cliMotionPhase

@Composable
internal fun CliUserNetworkSummary(
    home: HomeRouteUiState,
    connected: Boolean,
    modifier: Modifier = Modifier,
    traffic: TrafficSnapshot = home.traffic,
) {
    val colors = LocalCliColors.current
    val candidate = home.dashboardVisibleIpInfo(home.ipInfo)
    val reason = home.ipInfoRefreshReason
    val epoch = home.connection.lastChangeAt
    val transition = home.homeRouteTransitionRunning() || home.connection.isRouteTransition() || home.torOperation.active
    val refreshAffectsIdentity = reason != null &&
        (reason != IpInfoRefreshReason.TOR_ROUTE || candidate == home.torIpInfo)
    val refreshBaseline = remember(reason, epoch) { candidate?.fetchedAt ?: 0L }
    var previousReason by remember { mutableStateOf<IpInfoRefreshReason?>(null) }
    var lastInfoAt by remember { mutableLongStateOf(candidate?.fetchedAt ?: 0L) }
    var lastTrafficAt by remember { mutableLongStateOf(traffic.sampledAt) }
    var networkInfoFloor by remember { mutableLongStateOf(0L) }
    var networkTrafficFloor by remember { mutableLongStateOf(0L) }
    val networkChanged = reason == IpInfoRefreshReason.NETWORK_CHANGE && previousReason != reason
    val infoFloor = if (networkChanged) lastInfoAt else networkInfoFloor
    val trafficFloor = if (networkChanged) lastTrafficAt else networkTrafficFloor
    SideEffect {
        if (networkChanged) {
            networkInfoFloor = lastInfoAt
            networkTrafficFloor = lastTrafficAt
        }
        previousReason = reason
        candidate?.let { lastInfoAt = maxOf(lastInfoAt, it.fetchedAt) }
        lastTrafficAt = maxOf(lastTrafficAt, traffic.sampledAt)
    }
    val infoFresh = candidate != null && !transition &&
        (epoch <= 0L || candidate.fetchedAt >= epoch) && candidate.fetchedAt > infoFloor &&
        (!refreshAffectsIdentity || candidate.fetchedAt > refreshBaseline)
    val info = candidate.takeIf { infoFresh }
    val geoPending = transition || home.ipInfoLoading || refreshAffectsIdentity ||
        (connected && !infoFresh && home.dashboardConnectionMetricsLoading) ||
        !home.settingsHydrated || !home.profilesLoaded
    val sampleFresh = traffic.available && traffic.sampledAt >= epoch && traffic.sampledAt > trafficFloor
    val ratesPending = transition || !home.settingsHydrated || !home.profilesLoaded || (connected && !sampleFresh &&
        (traffic.available || home.dashboardConnectionMetricsLoading))
    val incoming = CliFormat.rate(if (sampleFresh) traffic.rxBytesPerSec.coerceAtLeast(0L) else 0L)
    val outgoing = CliFormat.rate(if (sampleFresh) traffic.txBytesPerSec.coerceAtLeast(0L) else 0L)
    val rates = "↓ $incoming  ↑ $outgoing"
    val latency = cliHomeLatencyMs(home, connected)
    val latencyPending = transition || (connected && (home.dashboardConnectionMetricsLoading || home.protocolMetricsRefreshing))
    val ipStyle = CliType.body.copy(fontSize = 18.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        CliUserNetworkRow(
            text = info?.ip?.trim()?.takeIf(String::isNotBlank), sample = "000.000.000.000",
            pending = geoPending, style = ipStyle, textColor = colors.fg,
            modifier = Modifier.fillMaxWidth().testTag(CLI_HOME_IP_TAG),
            reserveSpace = connected,
            leadingContent = { CliFlagIcon(countryCode = info?.countryCode, style = ipStyle) },
        )
        CliUserNetworkRow(
            text = info?.city?.trim()?.takeIf(String::isNotBlank), sample = "XXXXXXXX",
            pending = geoPending, style = CliType.small, textColor = colors.dim,
            reserveSpace = connected,
            leadingContent = { CliIcon(id = R.drawable.lin_city,
                contentDescription = stringResource(R.string.user_home_city), tint = colors.dim, size = 20.dp) },
        )
        CliUserNetworkRow(
            text = info?.countryName?.trim()?.takeIf(String::isNotBlank), sample = "XXXXXXXXXXXX",
            pending = geoPending, style = CliType.small, textColor = colors.dim,
            reserveSpace = connected,
            leadingContent = { CliIcon(id = R.drawable.lin_globe,
                contentDescription = null, tint = colors.dim, size = 20.dp) },
        )
        if (connected) {
            CliUserNetworkRow(
                text = if (!latencyPending)
                    stringResource(R.string.user_home_latency, CliFormat.latency(latency)) else null,
                sample = stringResource(R.string.user_home_latency, "000ms"),
                pending = latencyPending, style = CliType.small, textColor = colors.dim,
                reserveSpace = true,
            )
            CliUserNetworkRow(text = rates.takeUnless { ratesPending }, sample = rates,
                pending = ratesPending, style = CliType.body, textColor = colors.fg,
                modifier = Modifier.fillMaxWidth().testTag(CLI_HOME_TRAFFIC_TAG))
        }
    }
}

@Composable
private fun CliUserNetworkRow(
    text: String?,
    sample: String,
    pending: Boolean,
    style: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    reserveSpace: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    var previousText by remember { mutableStateOf(sample) }
    SideEffect { if (text != null) previousText = text }
    if (text == null && !pending && !reserveSpace) return
    val rowHeight = with(LocalDensity.current) { style.lineHeight.toDp() } + 8.dp
    Box(modifier.heightIn(min = rowHeight), contentAlignment = Alignment.Center) {
        if (text == null && pending) {
            CliUserNetworkSkeleton(previousText, style)
        } else if (text != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                leadingContent?.invoke()
                Text(text, style = style, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun CliUserNetworkSkeleton(sample: String, style: TextStyle) {
    val colors = LocalCliColors.current
    val phase = cliMotionPhase()
    Box(Modifier.drawBehind {
        val start = (phase.value * 2f - 1f) * size.width
        drawRoundRect(brush = Brush.linearGradient(
            listOf(colors.dim.copy(alpha = 0.15f), colors.dim.copy(alpha = 0.4f), colors.dim.copy(alpha = 0.15f)),
            start = Offset(start, 0f), end = Offset(start + size.width, size.height)),
            cornerRadius = CornerRadius(4.dp.toPx()))
    }) {
        Text(sample, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0f).clearAndSetSemantics { })
    }
}
