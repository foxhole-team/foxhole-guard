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
import com.foxhole.guard.R
import com.foxhole.guard.ui.DashboardTrafficCardUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.PublicDnsIdentityPhase
import com.foxhole.guard.ui.TorIdentityProbeState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTypography
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliType
import com.foxhole.guard.ui.cli.cliRowTextStyle
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliMetricSpinner
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliShimmerText
import com.foxhole.guard.ui.cli.components.cliFlagCode
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker
import com.foxhole.guard.ui.resolveDashboardLatencyOptionId
import java.util.Locale

@Composable
internal fun CliHomeBootLoadingPanel(
    viewModel: com.foxhole.guard.ui.HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
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
        CliHomeSectionTypography {
            CliPanel(
                modifier = Modifier.matchParentSize(),
                title = stringResource(R.string.cli_home_section_current_info),
                titleModifier = Modifier.cliHomeStatusHeaderPlacement(),
                titleColor = colors.accent,
                icon = R.drawable.lin_status,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CliLoadingRow(text = stringResource(R.string.cli_common_loading_data))
                }
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
    val colors = LocalCliColors.current
    CliHomeSectionTypography {
        CliPanel(
            modifier = modifier.fillMaxWidth().testTag(CLI_HOME_FACTS_TAG),
            title = stringResource(R.string.cli_home_section_current_info),
            titleModifier = Modifier.cliHomeStatusHeaderPlacement(),
            titleColor = colors.accent,
            icon = R.drawable.lin_status,
            onClick = onProfileTap,
            onLongClick = onProfileHold,
        ) {
            CliHomeFactsMetrics {
                CliConnectionFactsRows(
                    home = home,
                    trafficCard = trafficCard,
                    torIdentityProbe = torIdentityProbe,
                    connected = connected,
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun ColumnScope.CliConnectionFactsRows(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    trafficCard: DashboardTrafficCardUiState,
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
        icon = R.drawable.lin_profiles,
        valueTrailing = {
            CliDisclosureGlyph(
                expanded = false,
                color = colors.dim,
                modifier = Modifier.offset(
                    x = CliSpacing.xs,
                    y = CLI_HOME_VPN_DISCLOSURE_LIFT,
                ),
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
        CliDnsServerFact(home = home)
    }
    CliRowDivider()
    val latencyMs = cliHomeLatencyMs(home, connected)
    val latencySpinnerVisible = cliHomeLatencyLoading(home)
    val latencyDetermining = cliHomeLatencyDetermining(home, connected, latencyMs)
    val latencyValueContent: (@Composable () -> Unit)? = when {
        latencySpinnerVisible -> {
            { CliMetricSpinner() }
        }
        latencyDetermining -> {
            {
                CliShimmerText(
                    text = stringResource(R.string.cli_home_latency_determining),
                    style = cliRowTextStyle(),
                    baseColor = colors.dim,
                )
            }
        }
        else -> null
    }
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_latency),
        value = when {
            latencySpinnerVisible || latencyDetermining -> ""
            connected -> CliFormat.latency(latencyMs)
            else -> "—"
        },
        valueColor = when {
            latencyDetermining -> colors.dim
            connected -> latencyColor(latencyMs)
            else -> Color.Unspecified
        },
        icon = R.drawable.lin_up,
        valueContent = latencyValueContent,
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

@Composable
private fun CliHomeFactsMetrics(content: @Composable () -> Unit) {
    val typography = LocalCliType.current
    val factsTypography = remember(typography) {
        cliHomeFactsTypographyFor(typography)
    }
    CompositionLocalProvider(
        LocalCliType provides factsTypography,
        content = content,
    )
}

internal fun cliHomeFactsTypographyFor(
    typography: CliTypography,
): CliTypography {
    val consoleStyle = cliHomeConsoleTextStyleFor(typography)
    return typography.copy(body = consoleStyle, small = consoleStyle)
}

internal val CLI_HOME_VPN_DISCLOSURE_LIFT = (-1).dp

@Composable
private fun CliDnsServerFact(
    home: com.foxhole.guard.ui.HomeRouteUiState,
) {
    val colors = LocalCliColors.current
    val identity = home.publicDnsIdentity
    val loading = cliHomeDnsUpdating(home.connection.state, identity.phase)
    val resolved = !loading && identity.phase == PublicDnsIdentityPhase.RESOLVED
    val dnsCountryCode = identity.countryCode.takeIf { resolved }
    CliKeyValue(
        key = stringResource(R.string.cli_home_status_dns_server),
        value = when {
            loading -> ""
            resolved -> cliPublicDnsIdentityValue(identity.serverAddress, dnsCountryCode)
            else -> stringResource(R.string.cli_home_dns_not_determined)
        },
        valueColor = if (loading) colors.dim else Color.Unspecified,
        icon = R.drawable.lin_globe,
        valueContent = if (loading) {
            {
                CliShimmerText(
                    text = stringResource(R.string.cli_common_updating),
                    style = cliRowTextStyle(),
                    baseColor = colors.dim,
                )
            }
        } else {
            null
        },
        valueTrailing = if (cliFlagCode(dnsCountryCode) != null) {
            {
                CliRightAlignedRefreshTrailing(
                    loading = false,
                    countryCode = dnsCountryCode,
                )
            }
        } else {
            null
        },
    )
}

internal fun cliHomeDnsUpdating(
    connectionState: ConnectionState,
    identityPhase: PublicDnsIdentityPhase,
): Boolean =
    connectionState == ConnectionState.CONNECTING ||
        connectionState == ConnectionState.RECONNECTING ||
        identityPhase == PublicDnsIdentityPhase.IDLE ||
        identityPhase == PublicDnsIdentityPhase.LOADING

internal fun cliPublicDnsIdentityValue(
    serverAddress: String?,
    countryCode: String?,
): String = serverAddress?.takeIf(String::isNotBlank)?.let { server ->
    cliDnsIdentityValue(
        protocol = "",
        countryCode = countryCode,
        server = server,
    )
} ?: "—"

internal fun cliDnsIdentityValue(
    protocol: String,
    countryCode: String?,
    server: String,
): String = listOfNotNull(
    server.trim().takeIf(String::isNotBlank),
    protocol.takeIf(String::isNotBlank),
    countryCode?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US),
).joinToString(CLI_IDENTITY_SEGMENT_SEPARATOR).ifEmpty { "—" }

internal fun cliHomeIdentityLoading(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.ipInfoLoading ||
        home.connection.state == ConnectionState.CONNECTING ||
        home.connection.state == ConnectionState.RECONNECTING

internal fun cliHomeLatencyLoading(
    home: com.foxhole.guard.ui.HomeRouteUiState,
): Boolean {
    if (home.hasTorOnlyRoute()) return false
    return home.connection.routeState() == ConnectionState.CONNECTING ||
        home.connection.routeState() == ConnectionState.RECONNECTING
}

internal fun cliHomeLatencyDetermining(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    latencyMs: Long?,
): Boolean = connected && (
    home.ipInfoLoading ||
        home.dashboardConnectionMetricsLoading ||
        cliHomeLatencyUnavailable(home, connected) ||
        latencyMs == null
    )

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
        icon = R.drawable.lin_stats,
        modifier = Modifier.testTag(CLI_HOME_TRAFFIC_TAG),
        valueContent = {
            val down = if (available) "↓" + CliFormat.rate(rxBytesPerSec) else "↓ —"
            val up = if (available) "↑" + CliFormat.rate(txBytesPerSec) else "↑ —"
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
            withStyle(SpanStyle(color = colors.info)) { append(down) }
            append(' ')
            withStyle(SpanStyle(color = colors.ok)) { append(up) }
        },
        style = cliRowTextStyle(),
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.width(slotWidth),
    )
}

private const val SPEED_SLOT_SAMPLE = "↓ 000.0MB/s ↑ 000.0MB/s"

@Composable
private fun CliUptimeFact(startedAtMs: Long?) {
    val key = stringResource(R.string.cli_home_key_uptime)
    if (startedAtMs == null) {
        CliKeyValue(key = key, value = "—", icon = R.drawable.lin_clock)
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
        icon = R.drawable.lin_clock,
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
        icon = R.drawable.lin_status,
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
