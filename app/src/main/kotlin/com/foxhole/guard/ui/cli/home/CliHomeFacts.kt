package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.rememberNowMsTicker

// The connection facts panel and the TOR consent: describes state, controls nothing.

// TOR consent follows the toggle-modal law: bottom sheet, swipe or scrim cancels.
@Composable
internal fun CliTorConsentPanel(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onCancel,
        title = stringResource(R.string.cli_home_tor_title),
        icon = R.drawable.pix_tor,
    ) {
        Text(
            text = stringResource(R.string.cli_home_tor_consent_body),
            style = CliType.body,
            color = colors.fg,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliChip(
                label = stringResource(R.string.cli_home_tor_consent_yes),
                color = colors.tor,
                onClick = onConfirm,
                modifier = Modifier.testTag(CLI_HOME_TOR_CONSENT_CONFIRM_TAG),
            )
            CliChip(
                label = stringResource(R.string.cli_common_no_cancel),
                onClick = onCancel,
                modifier = Modifier.testTag(CLI_HOME_TOR_CONSENT_CANCEL_TAG),
            )
        }
    }
}

@Composable
internal fun CliConnectionFactsPanel(
    viewModel: com.foxhole.guard.ui.HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    onProfileTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The 1 Hz traffic tick is read here, not at the route root: reading it there recomposed
    // the whole screen every second. homeRouteState deliberately carries an empty snapshot.
    val trafficCard by viewModel.dashboardTrafficCardState.collectAsStateWithLifecycle()
    val colors = LocalCliColors.current
    val connection = home.connection
    // A tap anywhere on the panel opens profile selection; there is no separate tall button row.
    CliPanel(
        modifier = modifier.fillMaxWidth().testTag(CLI_HOME_FACTS_TAG),
        onClick = onProfileTap,
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
        )
        CliProfileProtocolFact(profile = home.activeProfile)
        // Status in the short canon — VPN / TOR / VPN+TOR / TOR-in-VPN rather than a faceless
        // "connected". The row is a plain fact with no tap: the STATUS button prints the block.
        CliConnectionStatusFact(
            home = home,
            torOnlyLive = isTorOnlyLive(home),
        )
        val shownIp = home.torIpInfo ?: home.ipInfo
        CliKeyValue(
            key = stringResource(R.string.cli_home_key_ip),
            value = shownIp?.ip ?: "—",
            valueColor = colors.info,
            icon = R.drawable.pix_link,
            modifier = Modifier.testTag(CLI_HOME_IP_TAG),
        )
        // The row set is constant: a fact without data shows "—" so the panel height never
        // jumps on connect or disconnect.
        CliGeoFact(
            geoCountry = shownIp?.countryCode?.takeIf { it.isNotBlank() },
            city = shownIp?.city,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_home_key_latency),
            value = if (connected) CliFormat.latency(home.selectedProtocolLatencyMs) else "—",
            valueColor = if (connected) latencyColor(home.selectedProtocolLatencyMs) else Color.Unspecified,
            icon = R.drawable.pix_up,
        )
        val trafficValue = if (trafficCard.traffic.available) {
            stringResource(
                R.string.cli_common_updown_value,
                CliFormat.rate(trafficCard.traffic.rxBytesPerSec),
                CliFormat.rate(trafficCard.traffic.txBytesPerSec),
            )
        } else {
            "—"
        }
        CliKeyValue(
            key = stringResource(R.string.cli_common_key_traffic),
            value = trafficValue,
            icon = R.drawable.pix_stats,
            modifier = Modifier.testTag(CLI_HOME_TRAFFIC_TAG),
        )
        CliUptimeFact(startedAtMs = connection.lastChangeAt.takeIf { connected })
    }
}

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
    CliKeyValue(
        key = key,
        value = CliFormat.uptime(startedAtMs, nowMs),
        icon = R.drawable.pix_clock,
    )
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

/**
 * The state as the CONNECTION ui must read it: a local-guard snapshot normalises to IDLE, so every
 * status/label/button branch below stays the plain state machine it always was and the firewall
 * simply never reaches a «connected/connecting» arm.
 */
internal fun ConnectionSnapshot.routeState(): ConnectionState =
    if (isLocalGuard()) ConnectionState.IDLE else state

/** A real route (VPN / proxy / TOR) is carrying traffic — never the firewall. */
internal fun ConnectionSnapshot.isRouteConnection(): Boolean = routeState() == ConnectionState.CONNECTED

/** A real route is coming up or re-establishing — never the firewall. */
internal fun ConnectionSnapshot.isRouteTransition(): Boolean =
    routeState() == ConnectionState.CONNECTING || routeState() == ConnectionState.RECONNECTING

/** TOR alone: no profile runtime is up (snapshot IDLE) while the Tor core reports CONNECTED. */
internal fun isTorOnlyLive(home: com.foxhole.guard.ui.HomeRouteUiState): Boolean =
    home.connection.state == ConnectionState.IDLE &&
        home.torPhase.phase == TorNetworkPhase.CONNECTED

/**
 * The dedicated TOR-only runtime, identified by its sentinel profile id. It raises its OWN
 * VpnService snapshot, so its state is CONNECTED and never IDLE — [isTorOnlyLive] can therefore
 * never match it, and because the TOR preset also sets bypassVpnTunnel the plain torActive arm
 * reported it as "VPN+TOR". There is no VPN in this mode.
 */
private fun ConnectionSnapshot.isTorOnlyRuntime(): Boolean =
    profileId == com.foxhole.core.model.TOR_ONLY_PROFILE_ID

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
        state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING -> colors.warn
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
    return when {
        // Must precede the torActive arm: the TOR-only runtime is CONNECTED with bypass set, which
        // would otherwise read as "VPN+TOR" even though no VPN exists in this mode.
        state == ConnectionState.CONNECTED && connection.isTorOnlyRuntime() ->
            stringResource(R.string.cli_st_tor)
        state == ConnectionState.CONNECTED && connection.torActive -> stringResource(
            if (home.settings.privacyRoute.bypassVpnTunnel) {
                R.string.cli_st_vpn_tor
            } else {
                R.string.cli_st_tor_in_vpn
            },
        )
        state == ConnectionState.CONNECTED -> stringResource(R.string.cli_st_vpn)
        torOnlyLive -> stringResource(R.string.cli_st_tor)
        else -> stateLabel(state)
    }
}

// The geo line under the ip: pixel flag + ISO code + city. The row is constant, showing "—"
// without data, and uses the shared CliKeyValue rather than a hand-rolled Row.
@Composable
private fun CliGeoFact(
    geoCountry: String?,
    city: String?,
) {
    val colors = LocalCliColors.current
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_geo),
        // ISO code plus city when known; never the full country name.
        value = geoCountry?.let { country ->
            listOfNotNull(
                country.uppercase(),
                city?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        } ?: "—",
        valueColor = if (geoCountry != null) colors.fg else colors.dim,
        icon = R.drawable.pix_map,
        valueLeading = geoCountry?.let { country -> { CliFlagIcon(countryCode = country) } },
    )
}
