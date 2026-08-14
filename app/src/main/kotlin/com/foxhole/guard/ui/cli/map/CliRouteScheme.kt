package com.foxhole.guard.ui.cli.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.settings.rememberCliAppIcon
import com.foxhole.guard.ui.trafficMapAppRouteProjection
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Route scheme: ONE device node left, arrows fanning out to route nodes. Uniform cells (fixed
 * square, pixel icon, flag+ISO with reserved slots so lanes align by column). Solid arrow =
 * tunnel segment, dashed = exit traffic, drifting in hard pixel steps while the route lives;
 * latency and protocol ride as small text over the arrow. Per-app lanes use that same centred
 * arrow anchor for launcher icons instead of letting a free strip drift below the route.
 *
 * The two exit identities arrive SEPARATELY ([vpnExitCountryCode] = the published tunnel IP,
 * [torExitCountryCode] = the Tor circuit's own IP) and every lane ends in its own exit node:
 * one shared "internet" cell used to wear a single country for all lanes, so a VPN lane showed
 * the Tor exit flag and a direct lane the VPN's.
 */
@Composable
internal fun CliRouteScheme(
    state: TrafficMapUiState,
    mode: CliRouteMode,
    deviceCountryCode: String?,
    vpnHopCountryCode: String?,
    vpnExitCountryCode: String?,
    torExitCountryCode: String?,
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
        // A stopped snapshot can retain the last sampled geo points for history. Those samples are
        // not a live route, so only the connection-derived mode may classify the current scheme.
        mode = mode,
        labels = labels,
        colors = colors,
        deviceCountryCode = deviceCountryCode,
        vpnHopCountryCode = vpnHopCountryCode,
        vpnExitCountryCode = vpnExitCountryCode,
        torExitCountryCode = torExitCountryCode,
    )
    val lanes = cliRouteLanes(state, parts)
    // Exit-dash drift: discrete phase 0..3, one step per 300 ms — same law as the map's dashes.
    val drift = rememberInfiniteTransition(label = "routeDrift").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "routeDriftPhase",
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        Text(text = stringResource(R.string.cli_rt_header), style = CliType.small, color = colors.dim)
        Row(
            // The device is the shared source of every lane. Stretching its white node to the
            // measured lane stack makes every arrow meet that source instead of starting in empty
            // space above or below a fixed square. Intrinsic height follows 1, 2, 3... lanes.
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

/** Fan lane: an arrow precedes each node — segment i leads INTO node i. */
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

/** One canonical per-app marker shared by VPN-proxy, Tor-proxy and direct-exception lanes. */
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
                CliPixIcon(
                    id = R.drawable.pix_apps,
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

/**
 * Uniform route cell: role-colored bordered square with a pixel icon, flag+ISO below (slot
 * reserved even without a country so all cells share height), then an optional note.
 */
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
            // Faint neon role tint over the panel.
            .background(node.color.copy(alpha = 0.08f))
            .border(1.dp, node.color)
            .padding(horizontal = CliSpacing.xs, vertical = CliSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
    ) {
        CliPixIcon(id = node.icon, contentDescription = node.label, tint = node.color)
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
            // Caption (e.g. "i2p") in the flag-row slot: countryless nodes stay identifiable
            // without breaking the shared cell height.
            Text(
                text = node.caption,
                style = CliType.small,
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // Empty slot of flag-row height keeps countryless cells as tall as the rest.
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

/**
 * Segment arrow stretched between cells: solid 2px bar = tunnel, 4-on-4 dashes = exit, drifting
 * right by whole grid pixels; head is a three-stroke pixel chevron. Everything draws in whole
 * 2px cells — no anti-aliasing or halftones.
 *
 * The note above the line rides a RESERVED slot, mirrored by an empty one below, so the line
 * always sits at the arrow's own centre — and therefore at its lane's centre, since the lane row
 * centres the arrow against the cells. Without the mirror a labelled lane drew its line half a
 * text-height lower than an unlabelled one, and the fan leaving the device cell came out crooked:
 * in the split scenario the "unprotected traffic" branch visibly sagged below the VPN branch.
 */
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
                // Launcher icons occupy the exact centred anchor used by ping/protocol text. This
                // keeps the strip above its arrow at 320dp and wider screens without affecting
                // node, line or lane measurement.
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
            val midY = ((size.height / 2f / cell).roundToInt() * cell)
            val headW = cell * 3
            val lineEnd = size.width - headW
            if (segment.tunnel) {
                drawRect(
                    color = segment.color,
                    topLeft = Offset(0f, midY - cell / 2f),
                    size = Size(lineEnd.coerceAtLeast(0f), cell),
                )
            } else {
                // Drifting dashes: 2 cells on, 2 off, phase 0..3.
                val phase = (drift.value.toInt() % 4) * cell
                var x = -4f * cell + phase
                while (x < lineEnd) {
                    val w = (2f * cell).coerceAtMost(lineEnd - x)
                    if (x + w > 0f) {
                        drawRect(
                            color = segment.color,
                            topLeft = Offset(x.coerceAtLeast(0f), midY - cell / 2f),
                            size = Size(w - (0f - x).coerceAtLeast(0f), cell),
                        )
                    }
                    x += 4f * cell
                }
            }
            // Head: three vertical strokes stepping 3-2-1 cells.
            for (i in 0 until 3) {
                val h = (3 - i) * cell
                drawRect(
                    color = segment.color,
                    topLeft = Offset(lineEnd + i * cell, midY - h + cell / 2f),
                    size = Size(cell, h * 2 - cell),
                )
            }
        }
        Box(modifier = Modifier.height(ARROW_NOTE_SLOT_HEIGHT), contentAlignment = Alignment.Center) {
            // Preserve latency/protocol information when the top anchor belongs to app icons. The
            // mirrored fixed slot keeps the arrow centred exactly as before.
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
    // A per-app split keeps its direct branch in the Tor scenarios too: those lanes replaced the
    // SPLIT classification, and with them the "part of the device stays direct" fact vanished.
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

/**
 * The applied route mode. The connection snapshot is authoritative for whether VPN/Tor is really
 * carrying packets; settings contribute only its package policy. That keeps the map, terminal
 * status and Home facts on one truth during a hot reload or a failed scenario switch.
 * Countries, latency and protocol badges come from the map's runtime state.
 */
@Immutable
internal data class CliRouteMode(
    // Session alive (CONNECTING/CONNECTED/RECONNECTING); armed-but-idle routes draw as direct.
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
    // Tor owns the tunnel's exit identity: all device traffic leaves through Tor inside the VPN,
    // so the published exit-IP is the Tor chain, NOT the VPN server. Mirrors the runtime policy
    // shouldHoldRuntimeProxyIpInfoForTorOverVpn (core/runtime/TunnelIpRefreshPolicy.kt).
    val torOwnsExitIdentity: Boolean = false,
)

/** Projects settings + connection snapshot onto the applied route. Pure — tested directly. */
internal fun cliRouteMode(
    settings: Settings,
    connection: ConnectionSnapshot,
): CliRouteMode {
    // An idle runtime has no route: settings only arm it (see [CliRouteMode.engaged]).
    if (connection.state !in ACTIVE_CONNECTION_STATES) return CliRouteMode()
    val profileId = connection.profileId
    val firewall = profileId == LOCAL_GUARD_PROFILE_ID
    val torOnly = profileId == TOR_ONLY_PROFILE_ID
    val carriesProfile = profileId.isPrimaryRouteProfile()
    val tunnelMode = settings.traffic.mode == TrafficMode.TUNNEL
    val privacyRoute = settings.privacyRoute
    // The map and Home status both describe the APPLIED route. A stored Tor switch cannot light a
    // Tor lane while the service is still applying it or after a failed reload.
    val runtimeTorEngaged = torOnly || connection.torActive
    val appRoute =
        settings.trafficMapAppRouteProjection(
            torRouteActive = runtimeTorEngaged,
            torOnlyRuntime = torOnly,
        )
    // INCLUDE×ALL is rejected by the runtime assembler. Never turn that refused selection into a
    // success-shaped Tor chain on the map; the still-running VPN split remains visible instead.
    val torEngaged = runtimeTorEngaged && appRoute.buildable
    val torApps = settings.appliedRouteTorApps(torEngaged, torOnly, tunnelMode, appRoute)
    val routeOwnsAppSplit = torOnly || (carriesProfile && tunnelMode)
    val torAllApps = appliedTorCoversAllApps(torEngaged, tunnelMode, privacyRoute.scope, appRoute)
    val torOwnsExitIdentity =
        torOwnsPrimaryTunnelExit(torEngaged, carriesProfile, tunnelMode, appRoute)
    return CliRouteMode(
        engaged = true,
        vpn = carriesProfile && tunnelMode,
        // Tor on the route: a Tor-only runtime, or a profile snapshot whose applied config says
        // Tor is active. A setting alone never claims a live circuit.
        tor = torEngaged,
        torAllApps = torAllApps,
        torBypassesVpn = privacyRoute.directTorEnabled,
        torApps = torApps,
        proxy = carriesProfile && !tunnelMode,
        firewall = firewall,
        split = routeOwnsAppSplit && appRoute.directBranch,
        splitApps = appRoute.vpnApps,
        directApps = appRoute.directApps,
        // ALL_APPS scope: everything, runtime probes included, leaves via Tor — the tunnel
        // identity becomes the Tor exit. With SELECTED_APPS probes use the plain VPN exit and
        // exit-IP stays the VPN server's honest country (see TunnelIpRefreshPolicy.kt).
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

/** Apps pinned to the Tor lane; nothing to list for whole-device scope. */
private fun Settings.cliRouteTorApps(): List<String> =
    if (privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
        expert.packages(AppTunnelLane.TOR)
    } else {
        emptyList()
    }

// Branch order is the original classification; only the flag source changed (mode, not map).
internal fun CliRouteMode.routeScenario(): CliRouteScenario = when {
    vpn && tor && torAllApps && !torBypassesVpn -> CliRouteScenario.TOR_IN_VPN_CHAIN
    // Tor beside a proxy-mode profile: without this branch the classification fell through to
    // TOR_ONLY and the proxy chain vanished from the scheme entirely.
    proxy && tor -> CliRouteScenario.PROXY_TOR
    proxy -> CliRouteScenario.PROXY
    vpn && tor -> CliRouteScenario.VPN_TOR
    tor -> CliRouteScenario.TOR_ONLY
    vpn && split -> CliRouteScenario.SPLIT
    vpn -> CliRouteScenario.VPN_ONLY
    firewall -> CliRouteScenario.FIREWALL
    else -> CliRouteScenario.DIRECT
}

/** Pure node-count/order contract. The shared device node is intentionally not repeated here. */
internal fun CliRouteMode.routeNodeRoles(): List<List<CliRouteNodeRole>> {
    val scenario = routeScenario()
    val roles = when (scenario) {
        CliRouteScenario.TOR_IN_VPN_CHAIN -> listOf(listOf(CliRouteNodeRole.VPN, CliRouteNodeRole.TOR))
        CliRouteScenario.PROXY -> listOf(
            listOf(CliRouteNodeRole.INTERNET),
            listOf(CliRouteNodeRole.PROXY, CliRouteNodeRole.VPN, CliRouteNodeRole.INTERNET),
        )
        // Proxy+Tor is one logical route: device (shared) -> VPN -> Tor. Tor is the egress.
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

// Lanes carry no device node (one shared source spans the stack at left). Per-app membership uses
// one launcher-icon strip above the corresponding route arrow.

// All-apps Tor inside the tunnel. The middle hop is the TOR tunnel entered through the VPN, so it
// must be `torSegment`: `vpnExit` drew it as the VPN leaving for the internet — VPN-coloured, no
// tunnel, carrying the wrong latency — which read as "VPN skipped, Tor hanging off the device".
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
            // Tor genuinely leaves BEFORE the VPN (bypass on): a parallel device→TOR→internet lane.
            CliRouteLaneModel(
                listOf(tor),
                listOf(torSegment),
                packages = mode.torApps,
                packageColor = colors.tor,
                appSegmentIndex = 0,
            )
        } else {
            // Tor-over-VPN scoped to selected apps: those apps ride device→VPN→TOR→internet, so the
            // Tor hop nests INSIDE the VPN instead of hanging off the device as a bare parallel Tor.
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
    // The transparent guard (raised only to carry I2P) filters nothing by the user's choice —
    // a battle-orange FW would claim an armed firewall that is not armed. It dims instead.
    val tone = if (state.firewallTransparent) colors.dim else colors.firewall
    return listOf(
        CliRouteLaneModel(
            listOf(
                // "FW" caption in the flag-row slot (same mechanism as "i2p"): no country, and the
                // brick glyph alone is not recognizable.
                CliRouteNodeModel(
                    labels.firewall,
                    tone,
                    icon = R.drawable.pix_forbidden,
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
            icon = R.drawable.pix_incognito,
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
    // The device's own traffic exits directly; the arrow carries the "other traffic is
    // unprotected" label.
    directBranchLane(),
    CliRouteLaneModel(
        listOf(
            CliRouteNodeModel(labels.proxy, colors.info, icon = R.drawable.pix_link),
            vpn,
            internetVpn,
        ),
        listOf(CliRouteSegmentModel(colors.info, tunnel = true), vpnSegment, vpnExit),
    ),
)

// Proxy+Tor has one egress chain. The shared device node plus these two nodes is exactly
// device -> VPN -> Tor; the Tor node itself replaces the old fourth Internet/globe cell.
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

// The direct branch leaves from the device's own network: origin geo, not the tunnel's.
// One arrow straight to the exit cell; the "not protected" label rides the arrow itself,
// so the lane needs no intermediate node.
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
    // Already the effective live mode; historical map samples never classify the current route.
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
        icon = R.drawable.pix_device,
        role = CliRouteNodeRole.DEVICE,
        country = physicalCountry,
    )

    private fun internetNode(country: String?) = CliRouteNodeModel(
        labels.internet,
        colors.info,
        icon = R.drawable.pix_globe,
        role = CliRouteNodeRole.INTERNET,
        country = country?.uppercase(),
    )

    // Exit of a lane leaving through the VPN. The published tunnel IP is trusted only while it
    // honestly belongs to the VPN — under Tor-owned exit identity it names the Tor chain (or is
    // held stale), and the VPN route point shares that channel, so the slot stays empty then.
    val internetVpn = internetNode(
        (vpnExitCountryCode ?: state.vpnRoute?.countryCode)
            ?.takeIf { !mode.torOwnsExitIdentity },
    )

    // Exit of a direct branch beside a live tunnel (split/proxy lanes): that traffic leaves from
    // the device's own network, never with the tunnel's published identity.
    val internetDirect = internetNode(physicalCountry)

    // Exit with no tunnel identity at all (direct scheme, firewall): the published IP is the
    // device's own and beats the origin geo when known.
    val internetOwn = internetNode(vpnExitCountryCode ?: physicalCountry)

    // The VPN hop is the provider server, not the final published exit. Its independently sampled
    // route point therefore keeps its own flag even when an all-apps Tor chain owns the public
    // exit identity. Hiding it in that scenario erased the VPN hop the user was actually using.
    val vpn = CliRouteNodeModel(
        "VPN",
        colors.vpn,
        icon = R.drawable.pix_shield,
        role = CliRouteNodeRole.VPN,
        country = cliVpnHopCountry(vpnHopCountryCode, state.vpnRoute?.countryCode),
    )

    // The Tor hop is the terminal egress. Only the confirmed Tor identity may decorate it: no
    // state.vpnRoute/state.torExit fallback can lend it the VPN country while its own probe waits.
    val tor = CliRouteNodeModel(
        "TOR",
        colors.tor,
        icon = R.drawable.pix_tor,
        role = CliRouteNodeRole.TOR,
        country = torExitCountryCode?.uppercase(),
        caption = if (torExitCountryCode == null) "—" else null,
    )
    val vpnSegment = CliRouteSegmentModel(colors.vpn, tunnel = true, detail = state.vpnRoute?.protocolBadge)
    val vpnExit = CliRouteSegmentModel(colors.vpn, detail = state.exitLatencyMs?.let { "${it}ms" })

    // Tor is the route's egress node. Its segment follows the same moving dotted exit grammar as
    // VPN→Internet, and selected-app icons sit above this segment rather than beside a protocol
    // label on the solid device→VPN tunnel.
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

/** Pure carrier-to-palette role; Compose color lookup stays a rendering concern. */
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

/** One dotted arrow into one terminal I2P endpoint; there is no trailing globe or segment. */
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
    // Small text under the glyph (e.g. "i2p") shown in the flag-row slot for nodes without a
    // country — labels glyphs (like the shared globe) that would otherwise be ambiguous.
    val caption: String? = null,
    val note: String? = null,
)

private data class CliRouteSegmentModel(val color: Color, val tunnel: Boolean = false, val detail: String? = null)

@Immutable
internal data class CliRouteAppStripPresentation(
    val visiblePackages: List<String>,
    val hiddenCount: Int,
)

/** Stable display policy: unique launcher icons in assignment order, six at most, then `+N`. */
internal fun cliRouteAppStrip(packages: List<String>): CliRouteAppStripPresentation {
    val normalized = packages.map(String::trim).filter(String::isNotEmpty).distinct()
    return CliRouteAppStripPresentation(
        visiblePackages = normalized.take(MAX_ROUTE_APP_ICONS),
        hiddenCount = (normalized.size - MAX_ROUTE_APP_ICONS).coerceAtLeast(0),
    )
}

/** VPN-hop geo is independent of whichever downstream leg owns the final public exit. */
internal fun cliVpnHopCountry(vararg countryCodes: String?): String? =
    countryCodes.firstNotNullOfOrNull { countryCode ->
        countryCode?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.US)
    }

/** Physical-network flag shared by the device and every branch that bypasses the tunnel. */
internal fun cliDeviceRouteCountry(
    deviceIpCountryCode: String?,
    sampledOriginCountryCode: String?,
): String? = cliVpnHopCountry(deviceIpCountryCode, sampledOriginCountryCode)

/** Localized label above the first I2P arrow, selected from live carrier ownership. */
internal fun i2pRouteConnectionLabelRes(carrier: TrafficMapI2pCarrier): Int =
    when (carrier) {
        TrafficMapI2pCarrier.DEVICE -> R.string.cli_rt_i2p_device_connection
        TrafficMapI2pCarrier.VPN, TrafficMapI2pCarrier.TOR -> R.string.cli_rt_i2p_tunnel_connection
    }

// Technical node tag like "i2p" — never localized (nor are protocol names).
private const val FIREWALL_CAPTION = "FW"

private val NODE_WIDTH = 56.dp
private val FLAG_SLOT_HEIGHT = 14.dp

// One `CliType.small` line: the note slot above the arrow and its mirror below, so a labelled
// segment and a bare one put their line at the same height.
private val ARROW_NOTE_SLOT_HEIGHT = 14.dp
private val ROUTE_APP_ICON_SIZE = 14.dp
private val ROUTE_APP_ICON_GAP = 2.dp
private val ROUTE_APP_STRIP_HEIGHT = 14.dp
private const val MAX_ROUTE_APP_ICONS = 6
