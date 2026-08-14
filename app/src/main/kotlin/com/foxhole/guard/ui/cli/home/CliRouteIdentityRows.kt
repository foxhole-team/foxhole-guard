package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.TorIdentityProbePhase
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSpinner
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import java.util.Locale

internal enum class CliRouteIdentityKind { VPN, TOR }

internal data class CliRouteIdentity(
    val kind: CliRouteIdentityKind,
    val info: IpInfo?,
)

/** One dashboard identity slot: [live] separates runtime truth from a configured idle placeholder. */
internal data class CliRouteIdentityFactState(
    val identity: CliRouteIdentity,
    val live: Boolean,
    val loading: Boolean,
)

internal fun cliRouteIdentities(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliRouteIdentity> = cliRouteIdentitiesForInfo(home.ipInfo, home.torIpInfo, runtimes)

internal fun cliRouteIdentitiesForInfo(
    vpnInfo: IpInfo?,
    torInfo: IpInfo?,
    runtimes: CliActiveRuntimes,
): List<CliRouteIdentity> =
    buildList {
        if (runtimes.vpn) add(CliRouteIdentity(CliRouteIdentityKind.VPN, vpnInfo))
        if (runtimes.tor) {
            add(CliRouteIdentity(CliRouteIdentityKind.TOR, torInfo?.confirmedTorIdentityOrNull()))
        }
    }

internal fun cliRouteIdentityValue(
    info: IpInfo?,
    includeCity: Boolean = true,
): String {
    val ip = info?.ip?.trim().orEmpty().takeIf(String::isNotEmpty)
    val country =
        info?.countryCode
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase(Locale.US)
            ?: info?.countryName?.trim()?.takeIf(String::isNotEmpty)
    val city = info?.city?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(country, city.takeIf { includeCity }, ip).joinToString(" · ").ifEmpty { "—" }
}

/**
 * Dashboard slots follow observed runtime work, not only the final CONNECTED snapshot. In a
 * VPN+TOR start this keeps separate VPN/TOR rows visible while each identity is still pending;
 * the settings toggle may reserve an idle TOR placeholder, but can never animate it by itself.
 */
internal fun cliRouteIdentityFactStates(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
    identityLoading: Boolean,
    torIdentityProbePhase: TorIdentityProbePhase,
): List<CliRouteIdentityFactState> {
    val connection = home.connection
    val primaryRouteObserved =
        connection.state in ROUTE_IDENTITY_ACTIVE_STATES &&
            connection.trafficMode in ROUTE_IDENTITY_TRAFFIC_MODES &&
            connection.profileId != null &&
            connection.profileId != LOCAL_GUARD_PROFILE_ID &&
            connection.profileId != TOR_ONLY_PROFILE_ID
    val torPhaseObserved = home.torPhase.phase in TOR_IDENTITY_ACTIVE_PHASES
    val torSnapshotObserved =
        connection.state in ROUTE_IDENTITY_ACTIVE_STATES &&
            (connection.torActive || connection.profileId == TOR_ONLY_PROFILE_ID)
    val vpnLive = runtimes.vpn || primaryRouteObserved
    val torLive = runtimes.tor || torSnapshotObserved || torPhaseObserved
    val torLoading =
        torLive &&
            cliTorIdentityLoading(
                phase = home.torPhase,
                probePhase = torIdentityProbePhase,
                torInfo = home.torIpInfo,
            )
    return buildList {
        if (vpnLive) {
            add(
                CliRouteIdentityFactState(
                    identity = CliRouteIdentity(CliRouteIdentityKind.VPN, home.ipInfo),
                    live = true,
                    loading = identityLoading,
                ),
            )
        }
        if (torLive) {
            add(
                CliRouteIdentityFactState(
                    identity = CliRouteIdentity(
                        CliRouteIdentityKind.TOR,
                        home.torIpInfo?.confirmedTorIdentityOrNull(),
                    ),
                    live = true,
                    loading = torLoading,
                ),
            )
        } else if (home.settings.privacyRoute.enabled) {
            add(
                CliRouteIdentityFactState(
                    identity = CliRouteIdentity(CliRouteIdentityKind.TOR, null),
                    live = false,
                    loading = false,
                ),
            )
        }
    }
}

@Composable
internal fun CliRouteIdentityFacts(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
    loading: Boolean = false,
    torIdentityProbePhase: TorIdentityProbePhase = TorIdentityProbePhase.IDLE,
) {
    val facts = cliRouteIdentityFactStates(home, runtimes, loading, torIdentityProbePhase)
    val liveFacts = facts.filter(CliRouteIdentityFactState::live)
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (liveFacts.isEmpty()) {
            // No live Tor leg exists here. A retained Tor sample must not replace the
            // device/VPN identity after Stop; when Tor is live it is rendered below through
            // the strict gate.
            val shownIp = home.ipInfo
            CliKeyValue(
                key = stringResource(R.string.cli_home_key_ip),
                value = cliRouteIdentityValue(shownIp),
                valueColor = colors.info,
                icon = R.drawable.pix_link,
                modifier = Modifier.testTag(CLI_HOME_IP_TAG),
                valueLeading = shownIp?.countryCode?.takeIf(String::isNotBlank)?.let { country ->
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
            if (facts.isNotEmpty()) CliRowDivider()
        }
        facts.forEachIndexed { index, fact ->
            if (index > 0) CliRowDivider()
            CliRouteIdentityFactRow(
                identity = fact.identity,
                loading = fact.loading,
                first = liveFacts.isNotEmpty() && index == 0,
            )
        }
    }
}

internal fun cliTorIdentitySlotVisible(
    torModeEnabled: Boolean,
    torLoading: Boolean,
    hasTorIdentityRow: Boolean,
): Boolean = !hasTorIdentityRow && (torModeEnabled || torLoading)

@Composable
private fun CliRouteIdentityFactRow(
    identity: CliRouteIdentity,
    loading: Boolean,
    first: Boolean,
) {
    val colors = LocalCliColors.current
    val vpn = identity.kind == CliRouteIdentityKind.VPN
    CliKeyValue(
        key = stringResource(if (vpn) R.string.cli_home_status_vpn_identity else R.string.cli_home_status_tor_identity),
        value = cliRouteIdentityValue(info = identity.info, includeCity = vpn),
        valueColor = if (vpn) colors.vpn else colors.tor,
        icon = if (vpn) R.drawable.pix_shield else R.drawable.pix_tor,
        modifier = if (first) Modifier.testTag(CLI_HOME_IP_TAG) else Modifier,
        valueLeading = identity.info?.countryCode?.takeIf(String::isNotBlank)?.let { country ->
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

/** Actual Tor activity only: never infer a spinner from the module/settings toggle. */
internal fun cliTorIdentityLoading(
    phase: TorPhaseSnapshot,
    probePhase: TorIdentityProbePhase,
    torInfo: IpInfo?,
): Boolean {
    if (torInfo?.confirmedTorIdentityOrNull() != null) return false
    if (probePhase == TorIdentityProbePhase.FAILED || probePhase == TorIdentityProbePhase.CANCELLED) return false
    return phase.phase == TorNetworkPhase.CONNECTING ||
        phase.phase == TorNetworkPhase.BUILDING_CIRCUITS ||
        phase.phase == TorNetworkPhase.CONNECTED
}

private val ROUTE_IDENTITY_ACTIVE_STATES =
    setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)
private val ROUTE_IDENTITY_TRAFFIC_MODES = setOf(TrafficMode.TUNNEL, TrafficMode.PROXY)
private val TOR_IDENTITY_ACTIVE_PHASES =
    setOf(TorNetworkPhase.CONNECTING, TorNetworkPhase.BUILDING_CIRCUITS, TorNetworkPhase.CONNECTED)

@Composable
internal fun cliStatusRouteIdentityRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> =
    cliRouteIdentities(home = home, runtimes = runtimes).map { identity ->
        CliTerminalRow(
            key = stringResource(
                when (identity.kind) {
                    CliRouteIdentityKind.VPN -> R.string.cli_home_status_vpn_identity
                    CliRouteIdentityKind.TOR -> R.string.cli_home_status_tor_identity
                },
            ),
            value = cliRouteIdentityValue(
                info = identity.info,
                includeCity = identity.kind == CliRouteIdentityKind.VPN,
            ),
            tone = when (identity.kind) {
                CliRouteIdentityKind.VPN -> CliLineTone.VPN
                CliRouteIdentityKind.TOR -> CliLineTone.TOR
            },
            flagCountry = identity.info?.countryCode,
        )
    }
