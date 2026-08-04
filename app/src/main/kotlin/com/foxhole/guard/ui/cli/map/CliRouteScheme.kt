package com.foxhole.guard.ui.cli.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliPixIcon
import kotlin.math.roundToInt

/**
 * Route scheme: ONE device node left, arrows fanning out to route nodes. Uniform cells (fixed
 * square, pixel icon, flag+ISO with reserved slots so lanes align by column). Solid arrow =
 * tunnel segment, dashed = exit traffic, drifting in hard pixel steps while the route lives;
 * lane notes (apps:N), latency and protocol ride as small text over the arrow.
 */
@Composable
internal fun CliRouteScheme(
    state: TrafficMapUiState,
    mode: CliRouteMode,
    exitCountryCode: String?,
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
    )
    val parts = CliRouteParts(state, mode.orRuntime(state), labels, colors, exitCountryCode)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            CliRouteNodeBox(parts.device)
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        lane.nodes.forEachIndexed { index, node ->
            lane.segments.getOrNull(index)?.let { segment ->
                CliRouteArrow(segment, drift, Modifier.weight(1f))
            }
            CliRouteNodeBox(node)
        }
    }
}

/**
 * Uniform route cell: role-colored bordered square with a pixel icon, flag+ISO below (slot
 * reserved even without a country so all cells share height), then an optional note.
 */
@Composable
private fun CliRouteNodeBox(node: CliRouteNodeModel, modifier: Modifier = Modifier) {
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
 */
@Composable
private fun CliRouteArrow(
    segment: CliRouteSegmentModel,
    drift: State<Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    }
}

private fun cliRouteLanes(
    state: TrafficMapUiState,
    parts: CliRouteParts,
): List<CliRouteLaneModel> {
    val lanes = when (parts.mode.routeScenario()) {
        CliRouteScenario.TOR_IN_VPN_CHAIN -> parts.chainedTorLane()
        CliRouteScenario.PROXY -> parts.proxyLanes()
        CliRouteScenario.VPN_TOR -> parts.vpnAndTorLanes()
        CliRouteScenario.TOR_ONLY -> parts.torLane()
        CliRouteScenario.SPLIT -> parts.splitLanes()
        CliRouteScenario.VPN_ONLY -> parts.vpnLane()
        CliRouteScenario.FIREWALL -> parts.firewallLane()
        CliRouteScenario.DIRECT -> parts.directLane()
    }.toMutableList()
    if (state.i2pActive) lanes += parts.i2pLane()
    return lanes
}

/**
 * The CHOSEN route mode — what the user just switched, read straight from settings + connection
 * snapshot. [TrafficMapUiState] runtime fields describe the APPLIED route (torActive arms only
 * after the config applies, vpnRoute/torExit wait for exit geo, proxy/firewall need a live
 * session), so classifying from them lagged a full reconnect behind every mode switch.
 * Countries, latency and protocol badges still come from the map's runtime state.
 */
@Immutable
internal data class CliRouteMode(
    // Session alive (CONNECTING/CONNECTED/RECONNECTING); armed-but-idle routes draw as direct.
    val engaged: Boolean = false,
    val vpn: Boolean = false,
    val tor: Boolean = false,
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

/** Projects settings + connection snapshot onto the chosen route. Pure — tested directly. */
internal fun cliRouteMode(
    settings: Settings,
    connection: ConnectionSnapshot,
): CliRouteMode {
    // An idle runtime has no route: settings only arm it (see [CliRouteMode.engaged]).
    if (connection.state !in ACTIVE_CONNECTION_STATES) return CliRouteMode()
    val profileId = connection.profileId
    val firewall = profileId == LOCAL_GUARD_PROFILE_ID
    val torOnly = profileId == TOR_ONLY_PROFILE_ID
    val carriesProfile = profileId != null && !firewall && !torOnly
    val tunnelMode = settings.traffic.mode == TrafficMode.TUNNEL
    val privacyRoute = settings.privacyRoute
    val split = settings.cliRouteSplit(tunnelMode)
    val torEngaged = torOnly || (privacyRoute.permitted && privacyRoute.enabled)
    return CliRouteMode(
        engaged = true,
        vpn = carriesProfile && tunnelMode,
        // Tor on the route: a Tor-only runtime, or permitted+enabled Tor-over-VPN. Must clear
        // with the setting — a disabled Tor otherwise hung on the scheme until reconnect.
        tor = torEngaged,
        torBypassesVpn = privacyRoute.directTorEnabled,
        torApps = settings.cliRouteTorApps(),
        proxy = carriesProfile && !tunnelMode,
        firewall = firewall,
        split = split.active,
        splitApps = split.tunnelApps,
        directApps = split.directApps,
        // ALL_APPS scope: everything, runtime probes included, leaves via Tor — the tunnel
        // identity becomes the Tor exit. With SELECTED_APPS probes use the plain VPN exit and
        // exit-IP stays the VPN server's honest country (see TunnelIpRefreshPolicy.kt).
        torOwnsExitIdentity =
        torEngaged &&
            carriesProfile &&
            tunnelMode &&
            privacyRoute.scope == PrivacyRouteScope.ALL_APPS,
    )
}

/** Apps pinned to the Tor lane; nothing to list for whole-device scope. */
private fun Settings.cliRouteTorApps(): List<String> =
    if (privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS) {
        expert.packages(AppTunnelLane.TOR)
    } else {
        emptyList()
    }

private class CliRouteSplit(
    val active: Boolean,
    val tunnelApps: List<String>,
    val directApps: List<String>,
)

/**
 * Per-app tunnel split. Only include-selection rides the VPN lane; in exclude mode the selected
 * apps ARE the direct branch — the sets are mutually exclusive.
 */
private fun Settings.cliRouteSplit(tunnelMode: Boolean): CliRouteSplit {
    val perAppMode = expert.perAppRoutingMode
    val apps = expert.tunnelSelectedPackages().filter(String::isNotBlank)
    val active = tunnelMode && perAppMode != PerAppRoutingMode.FULL_TUNNEL && apps.isNotEmpty()
    if (!active) return CliRouteSplit(active = false, tunnelApps = emptyList(), directApps = emptyList())
    val include = perAppMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS
    return CliRouteSplit(
        active = true,
        tunnelApps = if (include) apps else emptyList(),
        directApps = if (include) emptyList() else apps,
    )
}

/**
 * Effective scheme mode: while the session lives — the CHOSEN mode (redraws immediately, no
 * reconnect wait); idle — whatever the runtime actually left in the map state.
 */
private fun CliRouteMode.orRuntime(state: TrafficMapUiState): CliRouteMode =
    if (engaged) {
        this
    } else {
        CliRouteMode(
            engaged = false,
            vpn = state.vpnRoute != null,
            tor = state.torRouteActive || state.torExit != null,
            torBypassesVpn = state.torBypassesVpn,
            torApps = state.torAppPackages,
            proxy = state.proxyModeActive,
            firewall = state.firewallActive,
            split = state.splitTunnelActive,
            splitApps = state.splitAppPackages,
            directApps = state.directAppPackages,
        )
    }

// Branch order is the original classification; only the flag source changed (mode, not map).
internal fun CliRouteMode.routeScenario(): CliRouteScenario = when {
    vpn && tor && !torBypassesVpn && torApps.isEmpty() -> CliRouteScenario.TOR_IN_VPN_CHAIN
    proxy && !tor -> CliRouteScenario.PROXY
    vpn && tor -> CliRouteScenario.VPN_TOR
    tor -> CliRouteScenario.TOR_ONLY
    vpn && split -> CliRouteScenario.SPLIT
    vpn -> CliRouteScenario.VPN_ONLY
    firewall -> CliRouteScenario.FIREWALL
    else -> CliRouteScenario.DIRECT
}

// Lanes carry no device node (one per scheme, left); the lane note (apps:N) rides small text
// over the lane's FIRST arrow.

// All-apps Tor inside the tunnel. The middle hop is the TOR tunnel entered through the VPN, so it
// must be `torSegment`: `vpnExit` drew it as the VPN leaving for the internet — VPN-coloured, no
// tunnel, carrying the wrong latency — which read as "VPN skipped, Tor hanging off the device".
private fun CliRouteParts.chainedTorLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(vpn, tor, internet), listOf(vpnSegment, torSegment, torExit)),
)

private fun CliRouteParts.vpnAndTorLanes(): List<CliRouteLaneModel> {
    val torApps = appCount(mode.torApps)
    val vpnOnlyLane = CliRouteLaneModel(listOf(vpn, internet), listOf(vpnSegment, vpnExit))
    val torLane =
        if (mode.torBypassesVpn) {
            // Tor genuinely leaves BEFORE the VPN (bypass on): a parallel device→TOR→internet lane.
            CliRouteLaneModel(
                listOf(tor, internet),
                listOf(torSegment.withLaneNote(torApps), torExit),
            )
        } else {
            // Tor-over-VPN scoped to selected apps: those apps ride device→VPN→TOR→internet, so the
            // Tor hop nests INSIDE the VPN instead of hanging off the device as a bare parallel Tor.
            CliRouteLaneModel(
                listOf(vpn, tor, internet),
                listOf(vpnSegment, torSegment.withLaneNote(torApps), torExit),
            )
        }
    return listOf(vpnOnlyLane, torLane)
}

private fun CliRouteParts.torLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        listOf(tor, internet),
        listOf(torSegment.withLaneNote(appCount(mode.torApps)), torExit),
    ),
)

private fun CliRouteParts.vpnLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(vpn, internet), listOf(vpnSegment, vpnExit)),
)

private fun CliRouteParts.firewallLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        listOf(
            // "FW" caption in the flag-row slot (same mechanism as "i2p"): no country, and the
            // brick glyph alone is not recognizable.
            CliRouteNodeModel(
                labels.firewall,
                colors.warn,
                icon = R.drawable.pix_forbidden,
                caption = FIREWALL_CAPTION,
            ),
            internet,
        ),
        listOf(CliRouteSegmentModel(colors.warn, tunnel = true), CliRouteSegmentModel(colors.dim)),
    ),
)

private fun CliRouteParts.directLane(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(internet), listOf(CliRouteSegmentModel(colors.dim))),
)

private fun CliRouteParts.i2pLane(): CliRouteLaneModel = CliRouteLaneModel(
    nodes = listOf(
        CliRouteNodeModel("I2P", colors.accentBright, icon = R.drawable.pix_incognito, caption = "i2p"),
        CliRouteNodeModel(".i2p", colors.accentBright, icon = R.drawable.pix_globe, caption = "i2p"),
    ),
    segments = listOf(
        CliRouteSegmentModel(colors.accentBright, tunnel = true),
        CliRouteSegmentModel(colors.accentBright),
    ),
)

private fun CliRouteParts.proxyLanes(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(listOf(internet), listOf(CliRouteSegmentModel(colors.dim))),
    CliRouteLaneModel(
        listOf(
            CliRouteNodeModel(labels.proxy, colors.info, icon = R.drawable.pix_link),
            vpn,
            internet,
        ),
        listOf(CliRouteSegmentModel(colors.info, tunnel = true), vpnSegment, vpnExit),
    ),
)

private fun CliRouteParts.splitLanes(): List<CliRouteLaneModel> = listOf(
    CliRouteLaneModel(
        listOf(vpn, internet),
        listOf(vpnSegment.withLaneNote(appCount(mode.splitApps)), vpnExit),
    ),
    CliRouteLaneModel(
        listOf(
            CliRouteNodeModel(labels.direct, colors.dim, icon = R.drawable.pix_globe),
            internet,
        ),
        listOf(
            CliRouteSegmentModel(colors.dim, detail = appCount(mode.directApps)),
            CliRouteSegmentModel(colors.dim),
        ),
    ),
)

private fun appCount(packages: List<String>): String? = packages.takeIf(List<String>::isNotEmpty)?.let {
    "apps:${it.size}"
}

private fun CliRouteSegmentModel.withLaneNote(note: String?): CliRouteSegmentModel =
    if (note == null) this else copy(detail = listOfNotNull(note, detail).joinToString(" · "))

private class CliRouteParts(
    val state: TrafficMapUiState,
    // Already the effective mode (see [orRuntime]); classification and lane labels read from it.
    val mode: CliRouteMode,
    val labels: CliRouteLabels,
    val colors: CliColors,
    exitCountryCode: String?,
) {
    val device = CliRouteNodeModel(
        labels.device,
        colors.fg,
        icon = R.drawable.pix_device,
        country = state.originCountryCode?.uppercase(),
    )

    // Internet-exit country = exit-IP geo as the network sees it; falls back to route-node countries.
    val internet = CliRouteNodeModel(
        labels.internet,
        colors.accent,
        icon = R.drawable.pix_globe,
        country = (
            exitCountryCode
                ?: state.torExit?.countryCode
                ?: state.vpnRoute?.countryCode
                ?: state.originCountryCode
            )?.uppercase(),
    )

    // The VPN hop wears ONLY the VPN server's own country. While Tor owns the exit identity the
    // published exit-IP describes the Tor chain (or is stale) — an empty slot beats a wrong flag.
    val vpn = CliRouteNodeModel(
        "VPN",
        colors.vpn,
        icon = R.drawable.pix_shield,
        country = state.vpnRoute?.countryCode
            ?.takeIf { !mode.torOwnsExitIdentity }
            ?.uppercase(),
    )

    // The Tor hop always wears its own exit geo (separate torIpInfo channel).
    val tor = CliRouteNodeModel(
        "TOR",
        colors.tor,
        icon = R.drawable.pix_tor,
        country = state.torExit?.countryCode?.uppercase(),
    )
    val vpnSegment = CliRouteSegmentModel(colors.vpn, tunnel = true, detail = state.vpnRoute?.protocolBadge)
    val vpnExit = CliRouteSegmentModel(colors.vpn, detail = state.exitLatencyMs?.let { "${it}ms" })
    val torSegment = CliRouteSegmentModel(colors.tor, tunnel = true, detail = state.torExitLatencyMs?.let { "${it}ms" })
    val torExit = CliRouteSegmentModel(colors.tor, detail = state.exitLatencyMs?.let { "${it}ms" })
}

internal enum class CliRouteScenario {
    TOR_IN_VPN_CHAIN,
    PROXY,
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
)

private data class CliRouteLaneModel(
    val nodes: List<CliRouteNodeModel>,
    val segments: List<CliRouteSegmentModel>,
)

private data class CliRouteNodeModel(
    val label: String,
    val color: Color,
    val icon: Int,
    val country: String? = null,
    // Small text under the glyph (e.g. "i2p") shown in the flag-row slot for nodes without a
    // country — labels glyphs (like the shared globe) that would otherwise be ambiguous.
    val caption: String? = null,
    val note: String? = null,
)

private data class CliRouteSegmentModel(val color: Color, val tunnel: Boolean = false, val detail: String? = null)

// Technical node tag like "i2p" — never localized (nor are protocol names).
private const val FIREWALL_CAPTION = "FW"

private val NODE_WIDTH = 56.dp
private val FLAG_SLOT_HEIGHT = 14.dp
