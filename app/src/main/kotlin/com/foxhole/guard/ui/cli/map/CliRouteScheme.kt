package com.foxhole.guard.ui.cli.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMapI2pCarrier
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.packages
import com.foxhole.guard.R
import com.foxhole.guard.ui.TrafficMapAppRouteProjection
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliIcon
import com.foxhole.guard.ui.cli.components.cliMotionPhase
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import com.foxhole.guard.ui.trafficMapAppRouteProjection
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CliRouteScheme(
    state: TrafficMapUiState,
    mode: CliRouteMode,
    deviceCountryCode: String?,
    vpnHopCountryCode: String?,
    vpnExitCountryCode: String?,
    torExitCountryCode: String?,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val labels = CliRouteLabels(
        device = stringResource(R.string.cli_rt_device),
        internet = stringResource(R.string.cli_rt_internet),
        proxy = stringResource(
            if (state.lanProxyEnabled) R.string.cli_rt_proxy_lan else R.string.cli_rt_proxy,
        ),
        direct = stringResource(R.string.traffic_map_route_mode_direct).lowercase(),
        firewall = stringResource(R.string.cli_rt_firewall),
        i2pConnection = state.i2pCarrier?.let { carrier ->
            stringResource(i2pRouteConnectionLabelRes(carrier))
        },
    )
    val parts = CliRouteParts(
        state = state,
        mode = mode,
        labels = labels,
        colors = colors,
        deviceCountryCode = deviceCountryCode,
        vpnHopCountryCode = vpnHopCountryCode,
        vpnExitCountryCode = vpnExitCountryCode,
        torExitCountryCode = torExitCountryCode,
    )
    val lanes = cliRouteLanes(state, parts)
    val driftPhase = cliMotionPhase()
    val drift = remember(driftPhase) { derivedStateOf { driftPhase.value * DRIFT_CYCLE } }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        if (showHeader) {
            Text(text = stringResource(R.string.cli_rt_header), style = CliType.small, color = colors.dim)
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CliRouteNodeBox(
                node = parts.device,
                modifier = Modifier.fillMaxHeight(),
                centerVertically = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            ) {
                lanes.forEach { lane -> CliRouteLane(lane, drift) }
            }
        }
    }
}

@Composable
private fun CliRouteLane(lane: CliRouteLaneModel, drift: State<Float>) {
    val appStrip = cliRouteAppStrip(lane.packages)
    val hasAppStrip = appStrip.visiblePackages.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lane.nodes.forEachIndexed { index, node ->
            lane.segments.getOrNull(index)?.let { segment ->
                CliRouteArrow(
                    segment = segment,
                    drift = drift,
                    appStrip = appStrip.takeIf { hasAppStrip && index == lane.appSegmentIndex },
                    appColor = lane.packageColor,
                    modifier = Modifier.weight(1f),
                )
            }
            CliRouteNodeBox(node)
        }
    }
}

@Composable
private fun CliRouteAppStrip(
    presentation: CliRouteAppStripPresentation,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (presentation.visiblePackages.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().height(ROUTE_APP_STRIP_HEIGHT),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presentation.visiblePackages.forEachIndexed { index, packageName ->
            if (index > 0) Spacer(modifier = Modifier.width(ROUTE_APP_ICON_GAP))
            val icon = rememberCliAppIcon(
                packageName = packageName,
                versionCode = null,
                lastUpdateTime = null,
                bitmapSize = ROUTE_APP_ICON_SIZE,
            )
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = packageName,
                    modifier = Modifier.size(ROUTE_APP_ICON_SIZE),
                )
            } else {
                CliIcon(
                    id = R.drawable.lin_apps,
                    contentDescription = packageName,
                    size = ROUTE_APP_ICON_SIZE,
                    tint = color,
                )
            }
        }
        if (presentation.hiddenCount > 0) {
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            Text(
                text = "+${presentation.hiddenCount}",
                style = CliType.small,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CliRouteNodeBox(
    node: CliRouteNodeModel,
    modifier: Modifier = Modifier,
    centerVertically: Boolean = false,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = modifier
            .width(NODE_WIDTH)
            .background(colors.panelAlt)
            .background(node.color.copy(alpha = 0.08f))
            .border(1.dp, node.color)
            .padding(horizontal = CliSpacing.xs, vertical = CliSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
    ) {
        CliIcon(id = node.icon, contentDescription = node.label, tint = node.color)
        Spacer(modifier = Modifier.height(2.dp))
        if (node.country != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CliFlagIcon(countryCode = node.country, style = CliType.small)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = node.country,
                    style = CliType.small,
                    color = colors.dim,
                    maxLines = 1,
                )
            }
        } else if (node.caption != null) {
            Text(
                text = node.caption,
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(modifier = Modifier.height(FLAG_SLOT_HEIGHT))
        }
        node.note?.let { note ->
            Text(
                text = note,
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CliRouteArrow(
    segment: CliRouteSegmentModel,
    drift: State<Float>,
    appStrip: CliRouteAppStripPresentation? = null,
    appColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.height(ARROW_NOTE_SLOT_HEIGHT), contentAlignment = Alignment.Center) {
            if (appStrip != null) {
                CliRouteAppStrip(presentation = appStrip, color = appColor)
            } else {
                segment.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = CliType.small,
                        color = segment.color,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            val cell = 2.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
            drawRouteArrow(segment, drift.value, cell)
        }
        Box(modifier = Modifier.height(ARROW_NOTE_SLOT_HEIGHT), contentAlignment = Alignment.Center) {
            if (appStrip != null) {
                segment.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = CliType.small,
                        color = segment.color,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawRouteArrow(
    segment: CliRouteSegmentModel,
    driftValue: Float,
    cell: Float,
) {
    val midY = size.height / 2f
    val headW = cell * 3
    val lineEnd = size.width - headW
    val stroke = cell * 0.75f
    if (segment.tunnel) {
        drawLine(
            color = segment.color,
            start = Offset(0f, midY),
            end = Offset(lineEnd.coerceAtLeast(0f), midY),
            strokeWidth = stroke * 1.6f,
            cap = StrokeCap.Round,
        )
    } else {
        drawLine(
            color = segment.color,
            start = Offset(0f, midY),
            end = Offset(lineEnd.coerceAtLeast(0f), midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(cell * 2f, cell * 2f),
                -(driftValue % 4f) * cell,
            ),
        )
    }
    val head = Path().apply {
        moveTo(size.width, midY)
        lineTo(lineEnd, midY - headW * 0.6f)
        lineTo(lineEnd, midY + headW * 0.6f)
        close()
    }
    drawPath(path = head, color = segment.color)
}

private const val DRIFT_CYCLE = 4f

private fun cliRouteLanes(
    state: TrafficMapUiState,
    parts: CliRouteParts,
): List<CliRouteLaneModel> {
    val scenario = parts.mode.routeScenario()
    val lanes = when (scenario) {
        CliRouteScenario.TOR_IN_VPN_CHAIN -> parts.chainedTorLane()
        CliRouteScenario.PROXY -> parts.proxyLanes()
        CliRouteScenario.PROXY_TOR -> parts.proxyTorLanes()
        CliRouteScenario.VPN_TOR -> parts.vpnAndTorLanes()
        CliRouteScenario.TOR_ONLY -> parts.torLane()
        CliRouteScenario.SPLIT -> parts.splitLanes()
        CliRouteScenario.VPN_ONLY -> parts.vpnLane()
        CliRouteScenario.FIREWALL -> parts.firewallLane()
        CliRouteScenario.DIRECT -> parts.directLane()
    }.toMutableList()
    if (parts.mode.split && scenario.hasDetachedDirectBranch()) {
        lanes += parts.directBranchLane(parts.mode.directApps)
    }
    check(lanes.map { lane -> lane.nodes.map(CliRouteNodeModel::role) } == parts.mode.routeNodeRoles()) {
        "route node topology drifted from the applied-mode contract"
    }
    appendLiveI2pLane(lanes, state, parts)
    return lanes
}

private fun appendLiveI2pLane(
    lanes: MutableList<CliRouteLaneModel>,
    state: TrafficMapUiState,
    parts: CliRouteParts,
) {
    val carrier = state.i2pCarrier?.takeIf { parts.mode.engaged } ?: return
    val i2pLane = parts.i2pLane(carrier)
    val topology = cliI2pRouteTopology()
    check(
        i2pLane.nodes.map(CliRouteNodeModel::role) == topology.nodeRoles &&
            i2pLane.segments.size == topology.segmentCount &&
            i2pLane.segments.none { segment -> segment.tunnel },
    ) {
        "I2P route must terminate at its endpoint without an Internet node"
    }
    lanes += i2pLane
}

private fun CliRouteScenario.hasDetachedDirectBranch(): Boolean =
    this == CliRouteScenario.TOR_IN_VPN_CHAIN ||
        this == CliRouteScenario.VPN_TOR ||
        this == CliRouteScenario.TOR_ONLY

@Immutable
internal data class CliRouteMode(
    val engaged: Boolean = false,
    val vpn: Boolean = false,
    val tor: Boolean = false,
    val torAllApps: Boolean = false,
    val torBypassesVpn: Boolean = false,
    val torApps: List<String> = emptyList(),
    val proxy: Boolean = false,
    val firewall: Boolean = false,
    val split: Boolean = false,
    val splitApps: List<String> = emptyList(),
    val directApps: List<String> = emptyList(),
    val torOwnsExitIdentity: Boolean = false,
)

internal fun cliRouteMode(
    settings: Settings,
    connection: ConnectionSnapshot,
): CliRouteMode {
    if (connection.state !in ACTIVE_CONNECTION_STATES) return CliRouteMode()
    val profileId = connection.profileId
    val firewall = profileId == LOCAL_GUARD_PROFILE_ID
    val torOnly = profileId == TOR_ONLY_PROFILE_ID
    val carriesProfile = profileId.isPrimaryRouteProfile()
    val tunnelMode = settings.traffic.mode == TrafficMode.TUNNEL
    val privacyRoute = settings.privacyRoute
    val runtimeTorEngaged = torOnly || connection.torActive
    val appRoute =
        settings.trafficMapAppRouteProjection(
            torRouteActive = runtimeTorEngaged,
            torOnlyRuntime = torOnly,
        )
    val torEngaged = runtimeTorEngaged && appRoute.buildable
    val torApps = settings.appliedRouteTorApps(torEngaged, torOnly, tunnelMode, appRoute)
    val routeOwnsAppSplit = torOnly || (carriesProfile && tunnelMode)
    val torAllApps = appliedTorCoversAllApps(torEngaged, tunnelMode, privacyRoute.scope, appRoute)
    val torOwnsExitIdentity =
        torOwnsPrimaryTunnelExit(torEngaged, carriesProfile, tunnelMode, appRoute)
    return CliRouteMode(
        engaged = true,
        vpn = carriesProfile && tunnelMode,
        tor = torEngaged,
        torAllApps = torAllApps,
        torBypassesVpn = privacyRoute.directTorEnabled,
        torApps = torApps,
        proxy = carriesProfile && !tunnelMode,
        firewall = firewall,
        split = routeOwnsAppSplit && appRoute.directBranch,
        splitApps = appRoute.vpnApps,
        directApps = appRoute.directApps,
        torOwnsExitIdentity = torOwnsExitIdentity,
    )
}

private fun Long?.isPrimaryRouteProfile(): Boolean =
    this != null && this != LOCAL_GUARD_PROFILE_ID && this != TOR_ONLY_PROFILE_ID

private fun Settings.appliedRouteTorApps(
    torEngaged: Boolean,
    torOnly: Boolean,
    tunnelMode: Boolean,
    appRoute: TrafficMapAppRouteProjection,
): List<String> =
    when {
        !torEngaged -> emptyList()
        torOnly || tunnelMode -> appRoute.torApps
        privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS -> cliRouteTorApps()
        else -> emptyList()
    }

private fun appliedTorCoversAllApps(
    torEngaged: Boolean,
    tunnelMode: Boolean,
    scope: PrivacyRouteScope,
    appRoute: TrafficMapAppRouteProjection,
): Boolean =
    torEngaged && (appRoute.torAllApps || !tunnelMode && scope == PrivacyRouteScope.ALL_APPS)

private fun torOwnsPrimaryTunnelExit(
    torEngaged: Boolean,
    carriesProfile: Boolean,
    tunnelMode: Boolean,
    appRoute: TrafficMapAppRouteProjection,
): Boolean = torEngaged && carriesProfile && tunnelMode && appRoute.torAllApps

private fun Settings.cliRouteTorApps(): List<String> =
    if (privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
        expert.packages(AppTunnelLane.TOR)
    } else {
        emptyList()
    }

internal fun CliRouteMode.routeScenario(): CliRouteScenario = when {
    vpn && tor && torAllApps && !torBypassesVpn -> CliRouteScenario.TOR_IN_VPN_CHAIN
    proxy && tor -> CliRouteScenario.PROXY_TOR
    proxy -> CliRouteScenario.PROXY
    vpn && tor -> CliRouteScenario.VPN_TOR
    tor -> CliRouteScenario.TOR_ONLY
    vpn && split -> CliRouteScenario.SPLIT
    vpn -> CliRouteScenario.VPN_ONLY
    firewall -> CliRouteScenario.FIREWALL
    else -> CliRouteScenario.DIRECT
}

internal fun CliRouteMode.routeNodeRoles(): List<List<CliRouteNodeRole>> {
    val scenario = routeScenario()
    val roles = when (scenario) {
        CliRouteScenario.TOR_IN_VPN_CHAIN -> listOf(listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.TOR))
        CliRouteScenario.PROXY -> listOf(
            listOf(CliRouteNodeRole.INTERNET),
            listOf(CliRouteNodeRole.PROXY, CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET),
        )
        CliRouteScenario.PROXY_TOR -> listOf(listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.TOR))
        CliRouteScenario.VPN_TOR ->
            if (torBypassesVpn) {
                listOf(
                    listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET),
                    listOf(CliRouteNodeRole.TOR),
                )
            } else {
                listOf(
                    listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET),
                    listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.TOR),
                )
            }
        CliRouteScenario.TOR_ONLY -> listOf(listOf(CliRouteNodeRole.TOR))
        CliRouteScenario.SPLIT -> listOf(
            listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET),
            listOf(CliRouteNodeRole.INTERNET),
        )
        CliRouteScenario.VPN_ONLY -> listOf(listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET))
        CliRouteScenario.FIREWALL -> listOf(listOf(CliRouteNodeRole.FIREWALL, CliRouteNodeRole.INTERNET))
        CliRouteScenario.DIRECT -> listOf(listOf(CliRouteNodeRole.INTERNET))
    }.toMutableList()
    if (split && scenario.hasDetachedDirectBranch()) {
        roles += listOf(CliRouteNodeRole.INTERNET)
    }
    return roles
}

private fun CliRouteParts.chainedTorLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        nodes = listOf(vpn, tor),
        segments = listOf(vpnSegment, torSegment),
        packages = mode.splitApps,
        packageColor = colors.vpn,
        appSegmentIndex = 1,
    ),
)

private fun CliRouteParts.vpnAndTorLanes(): List<CliRouteLaneModel> {
    val vpnOnlyLane = CliRouteLaneModel(
        nodes = listOf(vpn, internetVpn),
        segments = listOf(vpnSegment, vpnExit),
        packages = mode.splitApps,
        packageColor = colors.vpn,
        appSegmentIndex = 1,
    )
    val torLane =
        if (mode.torBypassesVpn) {
            CliRouteLaneModel(
                listOf(tor),
                listOf(torSegment),
                packages = mode.torApps,
                packageColor = colors.tor,
                appSegmentIndex = 0,
            )
        } else {
            CliRouteLaneModel(
                listOf(vpn, tor),
                listOf(vpnSegment, torSegment),
                packages = mode.torApps,
                packageColor = colors.tor,
                appSegmentIndex = 1,
            )
        }
    return listOf(vpnOnlyLane, torLane)
}

private fun CliRouteParts.torLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        listOf(tor),
        listOf(torSegment),
        packages = mode.torApps,
        packageColor = colors.tor,
        appSegmentIndex = 0,
    ),
)

private fun CliRouteParts.vpnLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(vpn, internetVpn), listOf(vpnSegment, vpnExit)),
)

private fun CliRouteParts.firewallLane(): List<CliRouteLaneModel> {
    val tone = if (state.firewallTransparent) colors.dim else colors.firewall
    return listOf(
        CliRouteLaneModel(
            listOf(
                CliRouteNodeModel(
                    labels.firewall,
                    tone,
                    icon = R.drawable.lin_forbidden,
                    role = CliRouteNodeRole.FIREWALL,
                    caption = FIREWALL_CAPTION,
                ),
                internetOwn,
            ),
            listOf(CliRouteSegmentModel(tone, tunnel = true), CliRouteSegmentModel(colors.dim)),
        ),
    )
}

private fun CliRouteParts.directLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(internetOwn), listOf(CliRouteSegmentModel(colors.dim))),
)

private fun CliRouteParts.i2pLane(carrier: TrafficMapI2pCarrier): CliRouteLaneModel = CliRouteLaneModel(
    nodes = listOf(
        CliRouteNodeModel(
            "I2P",
            carrier.i2pRouteColor(colors),
            icon = R.drawable.lin_incognito,
            role = CliRouteNodeRole.I2P,
            caption = "i2p",
        ),
    ),
    segments = listOf(
        CliRouteSegmentModel(
            color = carrier.i2pRouteColor(colors),
            tunnel = false,
            detail = labels.i2pConnection,
        ),
    ),
)

private fun TrafficMapI2pCarrier.i2pRouteColor(colors: CliColors): Color =
    when (i2pRouteTone()) {
        CliI2pRouteTone.I2P -> colors.i2p
        CliI2pRouteTone.VPN -> colors.vpn
        CliI2pRouteTone.TOR -> colors.tor
    }

private fun CliRouteParts.proxyLanes(): List<CliRouteLaneModel> = listOf(
    directBranchLane(),
    CliRouteLaneModel(
        listOf(
            CliRouteNodeModel(labels.proxy, colors.info, icon = R.drawable.lin_link),
            vpn,
            internetVpn,
        ),
        listOf(CliRouteSegmentModel(colors.info, tunnel = true), vpnSegment, vpnExit),
    ),
)

private fun CliRouteParts.proxyTorLanes(): List<CliRouteLaneModel> =
    listOf(
        CliRouteLaneModel(
            listOf(vpn, tor),
            listOf(vpnSegment, torSegment),
            packages = mode.torApps,
            packageColor = colors.tor,
            appSegmentIndex = 1,
        ),
    )

private fun CliRouteParts.splitLanes(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        listOf(vpn, internetVpn),
        listOf(vpnSegment, vpnExit),
        packages = mode.splitApps,
        packageColor = colors.vpn,
        appSegmentIndex = 1,
    ),
    directBranchLane(mode.directApps),
)

private fun CliRouteParts.directBranchLane(packages: List<String> = emptyList()): CliRouteLaneModel = CliRouteLaneModel(
    listOf(internetDirect),
    listOf(
        CliRouteSegmentModel(
            colors.dim,
            detail = labels.direct,
        ),
    ),
    packages = packages,
    packageColor = colors.dim,
    appSegmentIndex = 0,
)

private class CliRouteParts(
    val state: TrafficMapUiState,
    val mode: CliRouteMode,
    val labels: CliRouteLabels,
    val colors: CliColors,
    deviceCountryCode: String?,
    vpnHopCountryCode: String?,
    vpnExitCountryCode: String?,
    torExitCountryCode: String?,
) {
    private val physicalCountry = cliDeviceRouteCountry(deviceCountryCode, state.originCountryCode)

    val device = CliRouteNodeModel(
        labels.device,
        colors.fg,
        icon = R.drawable.lin_device,
        role = CliRouteNodeRole.DEVICE,
        country = physicalCountry,
    )

    private fun internetNode(country: String?) = CliRouteNodeModel(
        labels.internet,
        colors.info,
        icon = R.drawable.lin_globe,
        role = CliRouteNodeRole.INTERNET,
        country = country?.uppercase(),
    )

    val internetVpn = internetNode(
        (vpnExitCountryCode ?: state.vpnRoute?.countryCode)
            ?.takeIf { !mode.torOwnsExitIdentity },
    )

    val internetDirect = internetNode(physicalCountry)

    val internetOwn = internetNode(vpnExitCountryCode ?: physicalCountry)

    val vpn = CliRouteNodeModel(
        "VPN",
        colors.vpn,
        icon = R.drawable.lin_shield,
        role = CliRouteNodeRole.VPN,
        country = cliVpnHopCountry(vpnHopCountryCode, state.vpnRoute?.countryCode),
    )

    val tor = CliRouteNodeModel(
        "TOR",
        colors.tor,
        icon = R.drawable.lin_tor,
        role = CliRouteNodeRole.TOR,
        country = torExitCountryCode?.uppercase(),
        caption = if (torExitCountryCode == null) "—" else null,
    )
    val vpnSegment = CliRouteSegmentModel(colors.vpn, tunnel = true, detail = state.vpnRoute?.protocolBadge)
    val vpnExit = CliRouteSegmentModel(colors.vpn, detail = state.exitLatencyMs?.let { "${it}ms" })

    val torSegment = CliRouteSegmentModel(colors.tor, detail = state.torExitLatencyMs?.let { "${it}ms" })
}

internal enum class CliRouteScenario {
    TOR_IN_VPN_CHAIN,
    PROXY,
    PROXY_TOR,
    VPN_TOR,
    TOR_ONLY,
    SPLIT,
    VPN_ONLY,
    FIREWALL,
    DIRECT,
}

private data class CliRouteLabels(
    val device: String,
    val internet: String,
    val proxy: String,
    val direct: String,
    val firewall: String,
    val i2pConnection: String?,
)

private data class CliRouteLaneModel(
    val nodes: List<CliRouteNodeModel>,
    val segments: List<CliRouteSegmentModel>,
    val packages: List<String> = emptyList(),
    val packageColor: Color = Color.Unspecified,
    val appSegmentIndex: Int = 0,
)

internal enum class CliRouteNodeRole { DEVICE, VPN, TOR, INTERNET, PROXY, FIREWALL, I2P }

internal enum class CliI2pRouteTone { I2P, VPN, TOR }

internal fun TrafficMapI2pCarrier.i2pRouteTone(): CliI2pRouteTone =
    when (this) {
        TrafficMapI2pCarrier.DEVICE -> CliI2pRouteTone.I2P
        TrafficMapI2pCarrier.VPN -> CliI2pRouteTone.VPN
        TrafficMapI2pCarrier.TOR -> CliI2pRouteTone.TOR
    }

@Immutable
internal data class CliI2pRouteTopology(
    val nodeRoles: List<CliRouteNodeRole>,
    val segmentCount: Int,
    val dotted: Boolean,
)

internal fun cliI2pRouteTopology(): CliI2pRouteTopology =
    CliI2pRouteTopology(
        nodeRoles = listOf(CliRouteNodeRole.I2P),
        segmentCount = 1,
        dotted = true,
    )

private data class CliRouteNodeModel(
    val label: String,
    val color: Color,
    val icon: Int,
    val role: CliRouteNodeRole = CliRouteNodeRole.PROXY,
    val country: String? = null,
    val caption: String? = null,
    val note: String? = null,
)

private data class CliRouteSegmentModel(val color: Color, val tunnel: Boolean = false, val detail: String? = null)

@Immutable
internal data class CliRouteAppStripPresentation(
    val visiblePackages: List<String>,
    val hiddenCount: Int,
)

internal fun cliRouteAppStrip(packages: List<String>): CliRouteAppStripPresentation {
    val normalized = packages.map(String::trim).filter(String::isNotEmpty).distinct()
    return CliRouteAppStripPresentation(
        visiblePackages = normalized.take(MAX_ROUTE_APP_ICONS),
        hiddenCount = (normalized.size - MAX_ROUTE_APP_ICONS).coerceAtLeast(0),
    )
}

internal fun cliVpnHopCountry(vararg countryCodes: String?): String? =
    countryCodes.firstNotNullOfOrNull { countryCode ->
        countryCode?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.US)
    }

internal fun cliDeviceRouteCountry(
    deviceIpCountryCode: String?,
    sampledOriginCountryCode: String?,
): String? = cliVpnHopCountry(deviceIpCountryCode, sampledOriginCountryCode)

internal fun i2pRouteConnectionLabelRes(carrier: TrafficMapI2pCarrier): Int =
    when (carrier) {
        TrafficMapI2pCarrier.DEVICE -> R.string.cli_rt_i2p_device_connection
        TrafficMapI2pCarrier.VPN, TrafficMapI2pCarrier.TOR -> R.string.cli_rt_i2p_tunnel_connection
    }

private const val FIREWALL_CAPTION = "FW"

private val NODE_WIDTH = 56.dp
private val FLAG_SLOT_HEIGHT = 14.dp

private val ARROW_NOTE_SLOT_HEIGHT = 14.dp
private val ROUTE_APP_ICON_SIZE = 14.dp
private val ROUTE_APP_ICON_GAP = 2.dp
private val ROUTE_APP_STRIP_HEIGHT = 14.dp
private const val MAX_ROUTE_APP_ICONS = 6
