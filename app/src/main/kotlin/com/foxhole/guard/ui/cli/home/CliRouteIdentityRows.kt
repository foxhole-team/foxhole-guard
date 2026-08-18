package com.foxhole.guard.ui.cli.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.TorIdentityProbePhase
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSpinner
import com.foxhole.guard.ui.cli.components.CliUpdatingText
import com.foxhole.guard.ui.cli.components.cliSpinnerSlotSize
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import java.util.Locale

internal enum class CliRouteIdentityKind { VPN, TOR }

internal data class CliRouteIdentity(
    val kind: CliRouteIdentityKind,
    val info: IpInfo?,
)

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
    val city = info?.city?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(ip, city.takeIf { includeCity }, cliRouteIdentityCountryLabel(info))
        .joinToString(" · ")
        .ifEmpty { "—" }
}

internal fun cliRouteIdentityCountryLabel(info: IpInfo?): String? =
    info?.countryCode
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.uppercase(Locale.US)
        ?: info?.countryName?.trim()?.takeIf(String::isNotEmpty)

@Suppress("CyclomaticComplexMethod")
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
    val torRouteExpected =
        connection.state in ROUTE_IDENTITY_ACTIVE_STATES &&
            connection.profileId != LOCAL_GUARD_PROFILE_ID &&
            home.settings.privacyRoute.enabled
    val vpnLive = runtimes.vpn || primaryRouteObserved
    val torLive = runtimes.tor || torSnapshotObserved || torPhaseObserved || torRouteExpected
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
                    loading = identityLoading || torLoading,
                ),
            )
        } else if (home.settings.privacyRoute.permitted) {
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
    Column(modifier = Modifier.fillMaxWidth()) {
        if (liveFacts.isEmpty()) {
            val shownIp = home.ipInfo
            CliRouteIdentityRow(
                key = stringResource(R.string.cli_home_key_ip),
                value = cliRouteIdentityValue(shownIp, includeCity = false),
                animateValue = true,
                icon = R.drawable.pix_link,
                countryCode = shownIp?.countryCode,
                loading = loading,
                modifier = Modifier.testTag(CLI_HOME_IP_TAG),
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

@Composable
@Suppress("LongParameterList")
private fun CliRouteIdentityRow(
    key: String,
    value: String,
    animateValue: Boolean,
    @DrawableRes icon: Int,
    countryCode: String?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    CliKeyValue(
        key = key,
        value = if (loading) "" else value,
        modifier = modifier,
        valueColor = colors.fg,
        icon = icon,
        valueContent = cliRightAlignedLoadingContent(loading),
        valueTrailing = if (loading || !countryCode.isNullOrBlank()) {
            {
                CliRightAlignedRefreshTrailing(
                    loading = loading,
                    countryCode = countryCode,
                )
            }
        } else {
            null
        },
        animateValue = animateValue,
        valueMaxLines = IDENTITY_VALUE_MAX_LINES,
    )
}

@Composable
internal fun cliRightAlignedLoadingContent(loading: Boolean): (@Composable () -> Unit)? =
    if (cliRightAlignedLoadingUsesText(loading, LocalCliVisualStyle.current)) {
        { CliUpdatingText() }
    } else {
        null
    }

internal fun cliRightAlignedLoadingUsesText(
    loading: Boolean,
    style: VisualStyle,
): Boolean = loading && style == VisualStyle.PLAIN

@Composable
internal fun CliRightAlignedRefreshTrailing(
    loading: Boolean,
    countryCode: String? = null,
) {
    Spacer(modifier = Modifier.width(CliSpacing.xs))
    CliRefreshTrailing(
        loading = loading,
        countryCode = countryCode,
        plainLoadingIndicator = false,
    )
}

@Composable
internal fun CliRefreshTrailing(
    loading: Boolean,
    countryCode: String?,
    plainLoadingIndicator: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val plainStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            (fadeIn(CliMotion.enter()) + scaleIn(initialScale = 0.8f, animationSpec = CliMotion.enter())) togetherWith
                (fadeOut(CliMotion.exit()) + scaleOut(targetScale = 0.8f, animationSpec = CliMotion.exit()))
        },
        contentAlignment = Alignment.CenterEnd,
        modifier = modifier.width(cliSpinnerSlotSize),
        label = "identityRefresh",
    ) { refreshing ->
        if (refreshing && (!plainStyle || plainLoadingIndicator)) {
            CliSpinner()
        } else if (!refreshing) {
            CliFlagIcon(countryCode = countryCode)
        }
    }
}

private const val IDENTITY_VALUE_MAX_LINES = 2

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
    val vpn = identity.kind == CliRouteIdentityKind.VPN
    CliRouteIdentityRow(
        key = stringResource(if (vpn) R.string.cli_home_status_vpn_identity else R.string.cli_home_status_tor_identity),
        value = cliRouteIdentityValue(info = identity.info, includeCity = vpn),
        animateValue = true,
        icon = if (vpn) R.drawable.pix_shield else R.drawable.pix_tor,
        countryCode = identity.info?.countryCode,
        loading = loading,
        modifier = if (first) Modifier.testTag(CLI_HOME_IP_TAG) else Modifier,
    )
}

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
