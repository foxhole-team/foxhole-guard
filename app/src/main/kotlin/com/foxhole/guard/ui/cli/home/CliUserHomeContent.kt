package com.foxhole.guard.ui.cli.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.cli.CliRadius
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliProtocolBadge
import com.foxhole.guard.ui.cli.map.CliMapOverviewPanel
import com.foxhole.guard.ui.cli.map.rememberCliMapOverviewState

@Composable
@Suppress("LongParameterList")
internal fun CliUserHomeContent(
    home: HomeRouteUiState,
    map: TrafficMapUiState,
    subscriptionRefreshInProgress: Boolean,
    onOpenMap: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleI2p: () -> Unit,
    onStopProxy: () -> Unit,
    header: @Composable () -> Unit,
    confirmation: @Composable () -> Unit,
    profileContent: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
    traffic: TrafficSnapshot = home.traffic,
) {
    val ready = home.settingsHydrated && home.profilesLoaded
    val density = LocalDensity.current
    var contentTopPx by remember { mutableFloatStateOf(0f) }
    var profileTopPx by remember { mutableFloatStateOf(0f) }
    var profileHeightPx by remember { mutableIntStateOf(0) }
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val safeHeight = (with(density) { contentHeightPx.toDp() } - LocalCliBottomChromeClearance.current).coerceAtLeast(0.dp)
    val aboveAnchor = with(density) { (profileTopPx - contentTopPx + profileHeightPx).toDp() } - 8.dp
    val selectorHeight = minOf(aboveAnchor, safeHeight * 0.75f).coerceAtLeast(0.dp)
    Column(
        modifier = modifier.fillMaxSize()
            .onGloballyPositioned { contentTopPx = it.positionInWindow().y; contentHeightPx = it.size.height }
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header()
            confirmation()
            if (!ready) {
                CliLoadingRow(text = stringResource(R.string.cli_common_loading_data))
            }
            CliUserHomeOverview(home, map, onOpenMap)
            CliUserNetworkSummary(
                home = home,
                traffic = traffic,
                connected = home.connection.isRouteConnection(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CliUserConnectionControls(
            home = home,
            subscriptionRefreshInProgress = subscriptionRefreshInProgress,
            onPrimaryAction = onPrimaryAction,
            onCycleMode = onCycleMode,
            onToggleI2p = onToggleI2p,
            onStopProxy = onStopProxy,
            profileContent = {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { profileTopPx = it.positionInWindow().y; profileHeightPx = it.size.height }) {
                    profileContent(selectorHeight)
                }
            },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun CliUserConnectionControls(
    home: HomeRouteUiState,
    subscriptionRefreshInProgress: Boolean,
    onPrimaryAction: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleI2p: () -> Unit,
    onStopProxy: () -> Unit,
    profileContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(CLI_USER_HOME_BOTTOM_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        profileContent()
        CliUserModuleActions(home, onCycleMode, onToggleI2p)
        CliUserPrimaryAction(home, subscriptionRefreshInProgress, onPrimaryAction)
        if (home.lanProxy.phase != LanProxyPhase.OFF) {
            CliButton(
                label = stringResource(R.string.user_home_stop_proxy),
                onClick = onStopProxy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CliChromeTailSpacer()
    }
}

@Composable
internal fun CliUserProfileCard(
    home: HomeRouteUiState,
    hasProfiles: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    profilesLoaded: Boolean = home.profilesLoaded,
    expanded: Boolean = false,
    showFrame: Boolean = true,
) {
    val colors = LocalCliColors.current
    val ready = home.settingsHydrated && profilesLoaded
    OutlinedButton(
        onClick = onClick,
        enabled = ready,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.fg),
        border = if (showFrame) BorderStroke(1.dp, colors.border) else null,
        shape = RoundedCornerShape(CliRadius.control),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(CLI_HOME_FACTS_TAG),
    ) {
        CliIcon(
            id = R.drawable.lin_profiles,
            contentDescription = null,
            tint = colors.dim,
            size = 22.dp,
            modifier = Modifier.testTag(CLI_USER_HOME_PROFILE_ICON_TAG),
        )
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    !ready -> stringResource(R.string.cli_common_loading_data)
                    !hasProfiles -> stringResource(R.string.user_home_add_profile)
                    else -> home.activeProfile?.name ?: stringResource(R.string.user_home_choose_profile)
                },
                style = CliType.body.copy(fontSize = 18.sp),
                color = colors.fg,
                maxLines = if (hasProfiles) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f, fill = false).testTag(CLI_USER_HOME_PROFILE_TEXT_TAG),
            )
            if (ready && hasProfiles && configuredConnectMode(home.settings) != CliConnectMode.TOR) {
                cliProfileProtocolLabel(home.activeProfile)?.let { protocol ->
                    CliProtocolBadge(protocol, Modifier.padding(start = 6.dp).align(Alignment.Top).offset(y = (-5).dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (hasProfiles) CliUserProfileChevron(expanded = expanded, tint = colors.dim)
        else CliIcon(id = R.drawable.lin_arrow_right, contentDescription = null, tint = colors.dim, size = 20.dp)
    }
}

@Composable
private fun CliUserModuleActions(
    home: HomeRouteUiState,
    onCycleMode: () -> Unit,
    onToggleI2p: () -> Unit,
) {
    val settings = home.settings
    if (!settings.privacyRoute.permitted && !settings.i2p.enabled) return
    val colors = LocalCliColors.current
    val ready = home.settingsHydrated && home.profilesLoaded
    val mode = configuredConnectMode(settings)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.privacyRoute.permitted) {
            CliButton(
                label = mode.label,
                icon = if (mode == CliConnectMode.VPN) R.drawable.lin_shield else R.drawable.lin_tor,
                color = if (mode == CliConnectMode.VPN) colors.accent else colors.tor,
                enabled = ready,
                onClick = onCycleMode,
                modifier = Modifier.weight(1f)
                    .testTag(CLI_HOME_MODE_ACTION_TAG)
                    .semantics { stateDescription = mode.label },
            )
        }
        if (settings.i2p.enabled) {
            val state = stringResource(if (settings.i2p.engaged) R.string.cli_common_on else R.string.cli_common_off)
            CliButton(
                label = if (settings.i2p.relayTransitTraffic) "I2P -R" else "I2P",
                icon = R.drawable.lin_globe,
                color = if (settings.i2p.engaged) colors.i2p else colors.dim,
                enabled = ready,
                onClick = onToggleI2p,
                modifier = Modifier.weight(1f)
                    .testTag(CLI_HOME_I2P_ACTION_TAG)
                    .semantics { stateDescription = state },
            )
        }
    }
}

@Composable
private fun CliUserPrimaryAction(
    home: HomeRouteUiState,
    subscriptionRefreshInProgress: Boolean,
    onPrimaryAction: () -> Unit,
) {
    val colors = LocalCliColors.current
    val ready = home.settingsHydrated && home.profilesLoaded
    val connected = home.connection.isRouteConnection()
    val busy = home.connection.isRouteTransition()
    CliButton(
        label = stringResource(mainButtonAction(connected, busy).labelRes),
        onClick = onPrimaryAction,
        enabled = connected || busy || (ready && home.activeProfile != null && !subscriptionRefreshInProgress),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(CLI_HOME_PRIMARY_ACTION_TAG),
        color = if (connected || busy) colors.err else colors.ok,
    )
}

@Composable
private fun CliUserHomeOverview(home: HomeRouteUiState, map: TrafficMapUiState, onOpenMap: () -> Unit) {
    val colors = LocalCliColors.current
    val category = home.settings.ui.homeAdditionalInfoCategory
    val showMap = category == HomeAdditionalInfoCategory.MAP
    if (!showMap || home.settings.ui.trafficMapEnabled || home.settings.expert.firewallEnabled) {
        CliMapOverviewPanel(
            map = map,
            home = home,
            overview = rememberCliMapOverviewState(map, home),
            content = category.cliMapOverviewContent(),
            onClick = onOpenMap.takeIf { showMap },
            modifier = Modifier.fillMaxWidth().then(
                if (showMap) {
                    Modifier.heightIn(min = 220.dp).testTag(CLI_USER_HOME_MAP_TAG)
                } else {
                    Modifier.testTag(CLI_USER_HOME_ROUTE_TAG)
                },
            ),
        )
    } else {
        Card(
            onClick = onOpenMap,
            colors = CardDefaults.cardColors(containerColor = colors.panel),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.testTag(CLI_USER_HOME_MAP_TAG),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.user_home_map_disabled), style = CliType.body, color = colors.dim,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                CliButton(label = stringResource(R.string.user_home_map_setup), onClick = onOpenMap)
            }
        }
    }
}

internal const val CLI_USER_HOME_MAP_TAG = "cli_user_home_map"
internal const val CLI_USER_HOME_ROUTE_TAG = "cli_user_home_route"
internal const val CLI_USER_HOME_BOTTOM_TAG = "cli_user_home_bottom"
internal const val CLI_USER_HOME_PROFILE_ICON_TAG = "cli_user_home_profile_icon"
internal const val CLI_USER_HOME_PROFILE_TEXT_TAG = "cli_user_home_profile_text"

@Composable
internal fun CliUserProfileChevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "userProfileChevron")
    CliIcon(id = R.drawable.lin_arrow_down, contentDescription = null, tint = tint, size = size,
        modifier = modifier.rotate(angle))
}
