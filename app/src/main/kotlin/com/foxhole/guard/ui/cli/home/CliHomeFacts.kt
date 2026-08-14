package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorIdentityProbeState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDisclosureGlyph
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSpinner
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker
import com.foxhole.guard.ui.resolveDashboardLatencyOptionId
import java.util.Locale

// The connection facts panel and the TOR consent: describes state, controls nothing.

/**
 * The facts area during cold start, while the profile store is still being decrypted: the
 * ASCII spinner with the loading word instead of a full panel of empty "—" facts.
 *
 * The slot is sized by an INVISIBLE ghost of the real [CliConnectionFactsPanel]: the loading
 * state then has byte-exactly the shape of the loaded one — an eyeballed min-height still let
 * the section grow when decryption finished, and the button rows below visibly jumped.
 */
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
            // Invisible and mute for accessibility; the overlay carries the whole story.
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
internal fun CliConnectionFactsPanel(
    viewModel: com.foxhole.guard.ui.HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: TorIdentityProbeState = TorIdentityProbeState(),
    connected: Boolean,
    onProfileTap: () -> Unit,
    onProfileHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The 1 Hz traffic tick is read here, not at the route root: reading it there recomposed
    // the whole screen every second. homeRouteState deliberately carries an empty snapshot.
    val trafficCard by viewModel.dashboardTrafficCardState.collectAsStateWithLifecycle()
    val trafficMap by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val colors = LocalCliColors.current
    val connection = home.connection
    // A tap anywhere on the panel opens profile selection; there is no separate tall button row.
    CliPanel(
        modifier = modifier.fillMaxWidth().testTag(CLI_HOME_FACTS_TAG),
        onClick = onProfileTap,
        onLongClick = onProfileHold,
    ) {
        // This fact is about the VPN profile. The local-guard snapshot carries its own service
        // name, and without this filter it appeared here when no profile was selected — the
        // firewall then read as a connected VPN profile.
        val profileLabel = home.activeProfile?.name
            ?: connection.profileName?.takeIf { !connection.isLocalGuard() }
            ?: stringResource(R.string.cli_home_profile_none)
        CliKeyValue(
            key = stringResource(R.string.cli_home_key_profile),
            value = profileLabel,
            valueColor = colors.fg,
            icon = R.drawable.pix_profiles,
            valueTrailing = { CliDisclosureGlyph(expanded = false, color = colors.dim) },
        )
        CliRowDivider()
        CliProfileProtocolFact(profile = home.activeProfile)
        CliRowDivider()
        // Status in the compact canon, including whole-device/per-app proxy scope and whether Tor
        // sits beside or inside VPN. The row is a plain fact; STATUS prints the expanded block.
        CliConnectionStatusFact(
            home = home,
            torOnlyLive = isTorOnlyLive(home),
        )
        CliRowDivider()
        val identityLoading = cliHomeIdentityLoading(home)
        CliRouteIdentityFacts(
            home = home,
            runtimes = activeRuntimes(home, isTorOnlyLive(home)),
            loading = identityLoading,
            torIdentityProbePhase = torIdentityProbe.phase,
        )
        CliRowDivider()
        CliDnsServerFact(
            home = home,
            dnsCountryCode = trafficMap.dnsServer?.countryCode,
            loading = identityLoading,
        )
        CliRowDivider()
        val latencyMs = cliHomeLatencyMs(home, connected)
        val latencyUnavailable = cliHomeLatencyUnavailable(home, connected)
        CliKeyValue(
            key = stringResource(R.string.cli_home_key_latency),
            value = when {
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
            valueContent = if (cliHomeLatencyLoading(home, connected)) {
                { CliSpinner(color = colors.info) }
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
}

/**
 * The resolver row under the ip, always present. With a live route (or the firewall) the value is
 * the configured server and its transport; otherwise the device resolves through the system, and
 * the row says so instead of rendering a setting as state.
 */
@Composable
private fun CliDnsServerFact(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    dnsCountryCode: String?,
    loading: Boolean,
) {
    val colors = LocalCliColors.current
    val live = home.connection.isRouteConnection() || home.connection.isLocalGuardLive()
    val server = home.settings.dns.server.trim().takeIf { it.isNotBlank() }
    val protocol = cliSecureDnsLabel(home.settings.dns.secureMode)
    val value = if (live && server != null) {
        cliDnsIdentityValue(protocol = protocol, countryCode = dnsCountryCode, server = server)
    } else {
        stringResource(R.string.cli_home_dns_system)
    }
    CliKeyValue(
        key = stringResource(R.string.cli_home_status_dns_server),
        value = value,
        valueColor = if (live && server != null) colors.info else colors.dim,
        icon = R.drawable.pix_globe,
        valueLeading = dnsCountryCode?.takeIf(String::isNotBlank)?.let { country ->
            {
                CliFlagIcon(countryCode = country)
            }
        },
        valueContent = if (loading) {
            { CliSpinner(color = colors.info) }
        } else {
            null
        },
    )
}

internal fun cliDnsIdentityValue(
    protocol: String,
    countryCode: String?,
    server: String,
): String = listOfNotNull(
    protocol.takeIf(String::isNotBlank),
    countryCode?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US),
    server.trim().takeIf(String::isNotBlank),
).joinToString(" · ").ifEmpty { "—" }

/** Manual geo refresh and an establishing profile share one honest loading presentation. */
internal fun cliHomeIdentityLoading(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.ipInfoLoading ||
        home.connection.state == ConnectionState.CONNECTING ||
        home.connection.state == ConnectionState.RECONNECTING

/** The manual identity refresh also remeasures the active route; keep its latency cell honest. */
internal fun cliHomeLatencyLoading(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
): Boolean =
    connected &&
        !home.hasTorOnlyRoute() &&
        home.dashboardConnectionMetricsLoading

/** The public route probe is the displayed value, including VPN+Tor where it measures Tor egress. */
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

/** Tor-only has no probe; a failed bounded VPN/VPN+Tor public probe is terminal unavailable. */
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

/**
 * The speed row: both directions as ONE string in one trailing-aligned slot.
 *
 * One string, because every seam in this row turned into a gap. Two texts let the arrow drift away
 * from its digits; two fixed slots put the second slot's slack between the two directions, which
 * read as a padding nobody asked for. Written as a single line the only space in it is the one
 * between the directions, the pair ends flush with the row's right edge, and the slot — measured
 * from the widest realistic pair — keeps the row from resizing as the numbers tick.
 */
@Composable
private fun CliSpeedFact(
    available: Boolean,
    rxBytesPerSec: Long,
    txBytesPerSec: Long,
) {
    val colors = LocalCliColors.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = CliType.body
    // Widest realistic sample; measured once so the row holds any value without moving.
    val slotWidth = remember(measurer, density, bodyStyle) {
        with(density) { measurer.measure(SPEED_SLOT_SAMPLE, bodyStyle).size.width.toDp() } + 2.dp
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag(CLI_HOME_TRAFFIC_TAG),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CliPixIcon(id = R.drawable.pix_stats, contentDescription = null, size = 12.dp, tint = colors.dim)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.cli_common_key_traffic),
                style = CliType.body,
                color = colors.dim,
                maxLines = 1,
            )
        }
        val down = if (available) "↓" + CliFormat.rate(rxBytesPerSec) else "↓—"
        val up = if (available) "↑" + CliFormat.rate(txBytesPerSec) else "↑—"
        CliSpeedValue(down = down, up = up, slotWidth = slotWidth)
    }
}

/** Both directions in one line, pushed to the trailing edge of a slot that never resizes. */
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
        style = CliType.body,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.width(slotWidth),
    )
}

// The widest pair the row is expected to hold ('0' is LanaPixel's widest decimal glyph).
private const val SPEED_SLOT_SAMPLE = "↓000.0MB/s ↑000.0MB/s"

// The ticker lives here rather than in the panel body: reading a 1 Hz tick inside CliPanel's
// content lambda rebuilt all eight rows every second.
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

/** Fixed numeric slots keep every unit label anchored while only the digits change. */
@Composable
private fun CliUptimeValue(
    days: Long,
    hours: Long,
    minutes: Long,
    seconds: Long,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = CliType.body
    val twoDigitWidth = remember(measurer, density, style) {
        with(density) { measurer.measure("00", style).size.width.toDp() }
    }
    val dayWidth = remember(measurer, density, style) {
        with(density) { measurer.measure("000", style).size.width.toDp() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CliUptimeSegment(days.toString(), stringResource(R.string.cli_uptime_days_short), dayWidth)
        CliUptimeSegment(
            hours.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_hours_short),
            twoDigitWidth,
        )
        CliUptimeSegment(
            minutes.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_minutes_short),
            twoDigitWidth,
        )
        CliUptimeSegment(
            seconds.toString().padStart(2, '0'),
            stringResource(R.string.cli_uptime_seconds_short),
            twoDigitWidth,
        )
    }
}

@Composable
private fun CliUptimeSegment(
    value: String,
    unit: String,
    valueWidth: Dp,
) {
    val colors = LocalCliColors.current
    Text(
        text = value,
        style = CliType.body,
        color = colors.fg,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = valueWidth),
    )
    Text(text = unit, style = CliType.body, color = colors.faint)
    Spacer(modifier = Modifier.width(2.dp))
}

// The one-line connection status (VPN / TOR / VPN+TOR / TOR-in-VPN / transitional), extracted so
// the facts panel body stays flat — its colour and label share the same state discrimination.
@Composable
private fun CliConnectionStatusFact(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
) {
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_status),
        value = connectionStatusLabel(home, torOnlyLive),
        valueColor = connectionStatusColor(home.connection, torOnlyLive),
        icon = R.drawable.pix_status,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The FIREWALL (local guard) raises a VpnService transport, so the engine publishes it as an
 * ordinary snapshot under [LOCAL_GUARD_PROFILE_ID]. It filters traffic without tunnelling it
 * anywhere, so it is NOT a connection: the whole connection UI — status dot/word, the status fact
 * and the START/mode/STOP row — must read a local-guard snapshot as offline. It is turned on and
 * off in app settings only; home never carries a control for it.
 */
private fun ConnectionSnapshot.isLocalGuard(): Boolean = profileId == LOCAL_GUARD_PROFILE_ID

/** The firewall/DNS guard is up: filtering, carrying nothing — not the same thing as offline. */
internal fun ConnectionSnapshot.isLocalGuardLive(): Boolean =
    isLocalGuard() && state == ConnectionState.CONNECTED

/**
 * The state as the CONNECTION ui must read it: a local-guard snapshot normalises to IDLE, so every
 * status/label/button branch below stays the plain state machine it always was and the firewall
 * simply never reaches a «connected/connecting» arm. DISCONNECTING is preserved because a clean
 * profile STOP may be handing ownership to the guard and must not look finished before its TUN is
 * reachable.
 */
internal fun ConnectionSnapshot.routeState(): ConnectionState =
    if (isLocalGuard() && state != ConnectionState.DISCONNECTING) ConnectionState.IDLE else state

/** A real route (VPN / proxy / TOR) is carrying traffic — never the firewall. */
internal fun ConnectionSnapshot.isRouteConnection(): Boolean = routeState() == ConnectionState.CONNECTED

/** A real route is coming up or re-establishing — never the firewall. */
internal fun ConnectionSnapshot.isRouteTransition(): Boolean =
    routeState() == ConnectionState.CONNECTING ||
        routeState() == ConnectionState.RECONNECTING ||
        routeState() == ConnectionState.DISCONNECTING

/** TOR alone: no profile runtime is up (snapshot IDLE) while the Tor core reports CONNECTED. */
internal fun isTorOnlyLive(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.connection.state == ConnectionState.IDLE &&
        home.torPhase.phase == TorNetworkPhase.CONNECTED

@Composable
internal fun connectionStatusColor(
    connection: ConnectionSnapshot,
    torOnlyLive: Boolean,
): Color {
    val colors = LocalCliColors.current
    // The firewall does not colour status: its snapshot normalises to IDLE and dims.
    val state = connection.routeState()
    return when {
        state == ConnectionState.CONNECTED && connection.torActive -> colors.tor
        state == ConnectionState.CONNECTED -> colors.vpn
        torOnlyLive -> colors.tor
        state == ConnectionState.CONNECTING ||
            state == ConnectionState.RECONNECTING ||
            state == ConnectionState.DISCONNECTING -> colors.warn
        state == ConnectionState.ERROR -> colors.err
        else -> colors.dim
    }
}

@Composable
private fun connectionStatusLabel(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torOnlyLive: Boolean,
): String {
    val connection = home.connection
    // Normalised to IDLE, so it reads as "no connection" rather than as VPN.
    val state = connection.routeState()
    val routeLive = state == ConnectionState.CONNECTED || torOnlyLive
    if (!routeLive) return stateLabel(state)
    val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val compactStatus = cliCompactRouteStatus(settings = home.settings, runtimes = runtimes)
        ?: return stateLabel(state)
    return stringResource(compactStatus.labelRes)
}
