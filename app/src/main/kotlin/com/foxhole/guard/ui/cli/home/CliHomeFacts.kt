package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.guard.R
import com.foxhole.guard.ui.DashboardTrafficCardUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorIdentityProbeState
import com.foxhole.guard.ui.cli.CLI_MODERN_METRIC_SCALE
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTypography
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliMetricScale
import com.foxhole.guard.ui.cli.LocalCliType
import com.foxhole.guard.ui.cli.cliRowTextStyle
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker
import com.foxhole.guard.ui.defaultDnsServerFor
import com.foxhole.guard.ui.resolveDashboardLatencyOptionId
import java.util.Locale

@Composable
internal fun CliHomeBootLoadingPanel(
    viewModel: com.foxhole.guard.ui.HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        CliConnectionFactsPanel(
            viewModel = viewModel,
            home = home,
            connected = false,
            onProfileTap = {},
            onProfileHold = {},
            modifier = Modifier
                .alpha(0f)
                .clearAndSetSemantics {},
        )
        CliPanel(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CliLoadingRow(text = stringResource(R.string.cli_common_loading_data))
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun CliConnectionFactsPanel(
    viewModel: com.foxhole.guard.ui.HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: TorIdentityProbeState = TorIdentityProbeState(),
    connected: Boolean,
    onProfileTap: () -> Unit,
    onProfileHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trafficCard by viewModel.dashboardTrafficCardState.collectAsStateWithLifecycle()
    val trafficMap by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val dnsServerCountry by viewModel.dnsServerCountryCode.collectAsStateWithLifecycle()
    val colors = LocalCliColors.current
    val connection = home.connection
    CliStatusSectionMetrics {
        CliPanel(
            modifier = modifier.fillMaxWidth().testTag(CLI_HOME_FACTS_TAG),
            onClick = onProfileTap,
            onLongClick = onProfileHold,
        ) {
            CliConnectionFactsRows(
                home = home,
                trafficCard = trafficCard,
                trafficMap = trafficMap,
                dnsServerCountry = dnsServerCountry,
                torIdentityProbe = torIdentityProbe,
                connected = connected,
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun ColumnScope.CliConnectionFactsRows(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    trafficCard: DashboardTrafficCardUiState,
    trafficMap: TrafficMapUiState,
    dnsServerCountry: String?,
    torIdentityProbe: TorIdentityProbeState,
    connected: Boolean,
) {
    val colors = LocalCliColors.current
    val connection = home.connection
    val profileLabel = home.activeProfile?.name
        ?: connection.profileName?.takeIf { !connection.isLocalGuard() }
        ?: stringResource(R.string.cli_home_profile_none)
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_profile),
        value = profileLabel,
        valueColor = colors.fg,
        icon = R.drawable.pix_profiles,
        valueTrailing = {
            CliDisclosureGlyph(
                expanded = false,
                color = colors.dim,
                modifier = Modifier.offset(x = CliSpacing.xs),
            )
        },
        valueMaxLines = 2,
    )
    CliRowDivider()
    CliProfileProtocolFact(profile = home.activeProfile)
    CliRowDivider()
    CliConnectionStatusFact(
        home = home,
        torOnlyLive = isTorOnlyLive(home),
    )
    CliRowDivider()
    val identityLoading = cliHomeIdentityLoading(home)
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliRouteIdentityFacts(
            home = home,
            runtimes = activeRuntimes(home, isTorOnlyLive(home)),
            loading = identityLoading,
            torIdentityProbePhase = torIdentityProbe.phase,
        )
        CliRowDivider()
        CliDnsServerFact(
            home = home,
            dnsCountryCode = trafficMap.dnsServer?.countryCode ?: dnsServerCountry,
            loading = identityLoading,
        )
    }
    CliRowDivider()
    val latencyMs = cliHomeLatencyMs(home, connected)
    val latencyUnavailable = cliHomeLatencyUnavailable(home, connected)
    val latencyLoading = cliHomeLatencyLoading(home, connected)
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_latency),
        value = when {
            latencyLoading -> ""
            latencyUnavailable -> stringResource(R.string.cli_lan_proxy_state_unavailable)
            connected -> CliFormat.latency(latencyMs)
            else -> "—"
        },
        valueColor = when {
            latencyUnavailable -> colors.dim
            connected -> latencyColor(latencyMs)
            else -> Color.Unspecified
        },
        icon = R.drawable.pix_up,
        valueContent = cliRightAlignedLoadingContent(latencyLoading),
        valueTrailing = if (latencyLoading) {
            {
                CliRightAlignedRefreshTrailing(loading = true)
            }
        } else {
            null
        },
    )
    CliRowDivider()
    CliSpeedFact(
        available = trafficCard.traffic.available,
        rxBytesPerSec = trafficCard.traffic.rxBytesPerSec,
        txBytesPerSec = trafficCard.traffic.txBytesPerSec,
    )
    CliRowDivider()
    CliUptimeFact(startedAtMs = connection.lastChangeAt.takeIf { connected })
}

/**
 * The status section renders one notch below the rest of the app.
 *
 * These rows carry the longest values on the screen — a country, a city and a full address on one
 * line — and at the shared ladder's size they truncated or set their marquee scrolling. The notch
 * is the existing Modern one, re-provided the way the dock re-provides its own, so the section
 * follows the active style and the app-wide scale instead of pinning a size of its own.
 *
 * [LocalCliType] carries the text sizes and [LocalCliMetricScale] the icon and one-off sizes, so
 * both are stepped together and glyphs stay proportional to the text beside them.
 */
@Composable
private fun CliStatusSectionMetrics(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCliType provides LocalCliType.current.steppedDown(),
        LocalCliMetricScale provides LocalCliMetricScale.current * CLI_MODERN_METRIC_SCALE,
        content = content,
    )
}

private fun CliTypography.steppedDown(): CliTypography =
    copy(
        body = body.steppedDown(),
        small = small.steppedDown(),
        title = title.steppedDown(),
        display = display.steppedDown(),
        button = button.steppedDown(),
    )

private fun TextStyle.steppedDown(): TextStyle =
    copy(
        fontSize = fontSize * CLI_MODERN_METRIC_SCALE,
        lineHeight = lineHeight * CLI_MODERN_METRIC_SCALE,
    )

@Composable
private fun CliDnsServerFact(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    dnsCountryCode: String?,
    loading: Boolean,
) {
    val server = home.settings.dns.server.trim().takeIf { it.isNotBlank() }
        ?: defaultDnsServerFor(home.settings.dns.secureMode)
    val protocol = cliSecureDnsLabel(home.settings.dns.secureMode)
    val value = cliDnsIdentityValue(
        protocol = protocol,
        countryCode = dnsCountryCode,
        server = server,
    )
    CliKeyValue(
        key = stringResource(R.string.cli_home_status_dns_server),
        value = if (loading) "" else value,
        valueColor = Color.Unspecified,
        icon = R.drawable.pix_globe,
        valueContent = cliRightAlignedLoadingContent(loading),
        valueTrailing = {
            CliRightAlignedRefreshTrailing(
                loading = loading,
                countryCode = dnsCountryCode,
            )
        },
    )
}

internal fun cliDnsIdentityValue(
    protocol: String,
    countryCode: String?,
    server: String,
): String = listOfNotNull(
    server.trim().takeIf(String::isNotBlank),
    protocol.takeIf(String::isNotBlank),
    countryCode?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US),
).joinToString(" · ").ifEmpty { "—" }

internal fun cliHomeIdentityLoading(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.ipInfoLoading ||
        home.connection.state == ConnectionState.CONNECTING ||
        home.connection.state == ConnectionState.RECONNECTING

internal fun cliHomeLatencyLoading(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
): Boolean =
    connected &&
        !home.hasTorOnlyRoute() &&
        home.dashboardConnectionMetricsLoading

internal fun cliHomeLatencyMs(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
): Long? {
    if (!connected || home.hasTorOnlyRoute()) {
        return null
    }
    val optionId = resolveDashboardLatencyOptionId(home.activeProfile, home.connection)
    return optionId?.let(home.protocolTunnelPingsByOptionId::get) ?: home.selectedProtocolLatencyMs
}

internal fun cliHomeLatencyUnavailable(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
): Boolean {
    if (isTorOnlyLive(home) || (connected && home.hasTorOnlyRoute())) {
        return true
    }
    if (!connected || home.dashboardConnectionMetricsLoading) {
        return false
    }
    val optionId = resolveDashboardLatencyOptionId(home.activeProfile, home.connection)
    return optionId != null && optionId in home.protocolTunnelPingUnavailableOptionIds
}

private fun com.foxhole.guard.ui.HomeRouteUiState.hasTorOnlyRoute(): Boolean =
    connection.profileId == TOR_ONLY_PROFILE_ID

@Composable
private fun CliSpeedFact(
    available: Boolean,
    rxBytesPerSec: Long,
    txBytesPerSec: Long,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rowStyle = cliRowTextStyle()
    val slotWidth = remember(measurer, density, rowStyle) {
        with(density) { measurer.measure(SPEED_SLOT_SAMPLE, rowStyle).size.width.toDp() } + 2.dp
    }
    CliKeyValue(
        key = stringResource(R.string.cli_common_key_traffic),
        value = "",
        icon = R.drawable.pix_stats,
        modifier = Modifier.testTag(CLI_HOME_TRAFFIC_TAG),
        valueContent = {
            val down = if (available) "↓" + CliFormat.rate(rxBytesPerSec) else "↓—"
            val up = if (available) "↑" + CliFormat.rate(txBytesPerSec) else "↑—"
            CliSpeedValue(down = down, up = up, slotWidth = slotWidth)
        },
    )
}

@Composable
private fun CliSpeedValue(
    down: String,
    up: String,
    slotWidth: Dp,
) {
    val colors = LocalCliColors.current
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.note)) { append(down) }
            append(' ')
            withStyle(SpanStyle(color = colors.ok)) { append(up) }
        },
        style = cliRowTextStyle(),
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.width(slotWidth),
    )
}

private const val SPEED_SLOT_SAMPLE = "↓000.0MB/s ↑000.0MB/s"

@Composable
private fun CliUptimeFact(startedAtMs: Long?) {
    val key = stringResource(R.string.cli_home_key_uptime)
    if (startedAtMs == null) {
        CliKeyValue(key = key, value = "—", icon = R.drawable.pix_clock)
        return
    }
    val nowMs by rememberNowMsTicker(startedAtMs)
    val total = (nowMs - startedAtMs).coerceAtLeast(0L)
    val days = total / 86_400_000L
    val hours = total / 3_600_000L % 24
    val minutes = total / 60_000L % 60
    val seconds = total / 1_000L % 60
    CliKeyValue(
        key = key,
        value = "",
        icon = R.drawable.pix_clock,
        valueContent = {
            CliUptimeValue(
                days = days,
                hours = hours,
                minutes = minutes,
                seconds = seconds,
            )
        },
    )
}

@Composable
private fun CliUptimeValue(
    days: Long,
    hours: Long,
    minutes: Long,
    seconds: Long,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = cliRowTextStyle()
    val twoDigitWidth = remember(measurer, density, style) {
        with(density) { measurer.measure("00", style).size.width.toDp() }
    }
    val dayWidth = remember(measurer, density, style) {
        with(density) { measurer.measure("000", style).size.width.toDp() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CliUptimeSegment(days.toString(), stringResource(R.string.cli_uptime_days_short), dayWidth, style)
        CliUptimeSegment(
            hours.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_hours_short),
            twoDigitWidth,
            style,
        )
        CliUptimeSegment(
            minutes.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_minutes_short),
            twoDigitWidth,
            style,
        )
        CliUptimeSegment(
            seconds.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_seconds_short),
            twoDigitWidth,
            style,
            last = true,
        )
    }
}

@Composable
private fun CliUptimeSegment(
    value: String,
    unit: String,
    valueWidth: Dp,
    style: TextStyle,
    last: Boolean = false,
) {
    val colors = LocalCliColors.current
    Text(
        text = value,
        style = style,
        color = colors.fg,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = valueWidth),
    )
    Spacer(modifier = Modifier.width(1.5.dp))
    Text(text = unit, style = style, color = colors.faint)
    if (!last) {
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@Composable
private fun CliConnectionStatusFact(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
) {
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_status),
        value = connectionStatusLabel(home, torOnlyLive),
        valueColor = LocalCliColors.current.vpn,
        icon = R.drawable.pix_status,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun ConnectionSnapshot.isLocalGuard(): Boolean = profileId == LOCAL_GUARD_PROFILE_ID

internal fun ConnectionSnapshot.isLocalGuardLive(): Boolean =
    isLocalGuard() && state == ConnectionState.CONNECTED

internal fun ConnectionSnapshot.routeState(): ConnectionState =
    if (isLocalGuard() && state != ConnectionState.DISCONNECTING) ConnectionState.IDLE else state

internal fun ConnectionSnapshot.isRouteConnection(): Boolean = routeState() == ConnectionState.CONNECTED

internal fun ConnectionSnapshot.isRouteTransition(): Boolean =
    routeState() == ConnectionState.CONNECTING ||
        routeState() == ConnectionState.RECONNECTING ||
        routeState() == ConnectionState.DISCONNECTING

internal fun isTorOnlyLive(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.connection.state == ConnectionState.IDLE &&
        home.torPhase.phase == TorNetworkPhase.CONNECTED

@Composable
private fun connectionStatusLabel(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
): String {
    val connection = home.connection
    val state = connection.routeState()
    val routeLive = state == ConnectionState.CONNECTED || torOnlyLive
    if (!routeLive) return stateLabel(state)
    val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val compactStatus = cliCompactRouteStatus(settings = home.settings, runtimes = runtimes)
        ?: return stateLabel(state)
    return stringResource(compactStatus.labelRes)
}
