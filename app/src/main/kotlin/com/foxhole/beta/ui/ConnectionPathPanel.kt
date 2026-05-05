package com.foxhole.beta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors

enum class RouteNodeState {
    INACTIVE,
    CONNECTING,
    CHECKING,
    OK,
    ERROR,
}

enum class RouteSegment {
    DEVICE_TO_VPN,
    VPN_TO_TOR,
    VPN_TO_INTERNET,
    TOR_TO_INTERNET,
}

enum class RouteNode {
    DEVICE,
    VPN,
    TOR,
    INTERNET,
}

data class ConnectionPathUiState(
    val device: RouteNodeState = RouteNodeState.OK,
    val vpn: RouteNodeState = RouteNodeState.INACTIVE,
    val tor: RouteNodeState = RouteNodeState.INACTIVE,
    val internet: RouteNodeState = RouteNodeState.INACTIVE,
    val torEnabled: Boolean = false,
    val activeSegment: RouteSegment? = null,
)

internal fun connectionRouteNodes(torEnabled: Boolean): List<RouteNode> =
    if (torEnabled) {
        listOf(RouteNode.DEVICE, RouteNode.VPN, RouteNode.TOR, RouteNode.INTERNET)
    } else {
        listOf(RouteNode.DEVICE, RouteNode.VPN, RouteNode.INTERNET)
    }

internal fun connectionPathUiState(state: HomeRouteUiState): ConnectionPathUiState {
    val torConfigured = state.hasActiveTorPrivacyRoute()
    val connectionState = state.connection.state
    val deviceState = connectionState.deviceRouteNodeState()
    val vpnState = connectionState.vpnRouteNodeState()
    val vpnInternetChecked = state.hasFreshConnectedIpInfo()
    val torVerified = state.hasVerifiedTorRoute(torConfigured, vpnInternetChecked)
    val torState = torRouteNodeState(torConfigured, connectionState, torVerified)
    val internetState = internetRouteNodeState(torConfigured, connectionState, torVerified, vpnInternetChecked)
    val activeSegment = activeRouteSegment(torConfigured, connectionState, torState, internetState)
    return ConnectionPathUiState(
        device = deviceState,
        vpn = vpnState,
        tor = torState,
        internet = internetState,
        torEnabled = torConfigured,
        activeSegment = activeSegment,
    )
}

private fun HomeRouteUiState.hasFreshConnectedIpInfo(): Boolean =
    connection.state == ConnectionState.CONNECTED &&
        ipInfo?.fetchedAt?.let { fetchedAt -> fetchedAt >= connection.lastChangeAt } == true

private fun HomeRouteUiState.hasActiveTorPrivacyRoute(): Boolean =
    settings.privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        settings.traffic.mode == TrafficMode.TUNNEL &&
        connection.protocolHint?.isUdpTransport() != true

private fun ConnectionState.deviceRouteNodeState(): RouteNodeState =
    when (this) {
        ConnectionState.CONNECTED -> RouteNodeState.OK
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        -> RouteNodeState.CONNECTING
        ConnectionState.ERROR,
        ConnectionState.IDLE,
        -> RouteNodeState.INACTIVE
    }

private fun ConnectionState.vpnRouteNodeState(): RouteNodeState =
    when (this) {
        ConnectionState.CONNECTED -> RouteNodeState.OK
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        -> RouteNodeState.CONNECTING
        ConnectionState.ERROR -> RouteNodeState.ERROR
        ConnectionState.IDLE -> RouteNodeState.INACTIVE
    }

private fun HomeRouteUiState.hasVerifiedTorRoute(
    torConfigured: Boolean,
    vpnInternetChecked: Boolean,
): Boolean =
    torConfigured &&
        settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS &&
        vpnInternetChecked

private fun torRouteNodeState(
    torConfigured: Boolean,
    connectionState: ConnectionState,
    torVerified: Boolean,
): RouteNodeState =
    when {
        !torConfigured -> RouteNodeState.INACTIVE
        connectionState == ConnectionState.ERROR -> RouteNodeState.INACTIVE
        connectionState.isConnectingRouteState() -> RouteNodeState.INACTIVE
        torVerified -> RouteNodeState.OK
        connectionState == ConnectionState.CONNECTED -> RouteNodeState.CHECKING
        else -> RouteNodeState.INACTIVE
    }

private fun internetRouteNodeState(
    torConfigured: Boolean,
    connectionState: ConnectionState,
    torVerified: Boolean,
    vpnInternetChecked: Boolean,
): RouteNodeState =
    when {
        connectionState == ConnectionState.ERROR -> RouteNodeState.INACTIVE
        !torConfigured && connectionState == ConnectionState.CONNECTED && vpnInternetChecked -> RouteNodeState.OK
        !torConfigured && connectionState == ConnectionState.CONNECTED -> RouteNodeState.CHECKING
        torConfigured && torVerified -> RouteNodeState.OK
        torConfigured && connectionState == ConnectionState.CONNECTED -> RouteNodeState.CHECKING
        else -> RouteNodeState.INACTIVE
    }

private fun activeRouteSegment(
    torConfigured: Boolean,
    connectionState: ConnectionState,
    torState: RouteNodeState,
    internetState: RouteNodeState,
): RouteSegment? =
    when {
        connectionState.isConnectingRouteState() -> RouteSegment.DEVICE_TO_VPN
        connectionState != ConnectionState.CONNECTED -> null
        torConfigured && torState == RouteNodeState.CHECKING -> RouteSegment.VPN_TO_TOR
        torConfigured && torState == RouteNodeState.OK && internetState == RouteNodeState.CHECKING -> RouteSegment.TOR_TO_INTERNET
        !torConfigured && internetState == RouteNodeState.CHECKING -> RouteSegment.VPN_TO_INTERNET
        else -> null
    }

private fun ConnectionState.isConnectingRouteState(): Boolean =
    this == ConnectionState.CONNECTING || this == ConnectionState.RECONNECTING

@Composable
internal fun ConnectionPathPanel(
    state: ConnectionPathUiState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(240)) + expandVertically(animationSpec = tween(260)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("connection_path_panel"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ConnectionRouteGraph(state)
        }
    }
}

@Composable
internal fun ConnectionRouteGraph(
    state: ConnectionPathUiState,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(state.torEnabled) { connectionRouteNodes(state.torEnabled) }
    val pulseProgress = rememberRoutePulseProgress(state.activeSegment)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
        ) {
            RouteArrowsCanvas(
                nodes = nodes,
                state = state,
                pulseProgress = pulseProgress,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                nodes.forEach { node ->
                    RouteNodeIcon(
                        node = node,
                        state = state.nodeState(node),
                        modifier = Modifier.width(RouteNodeSlotWidth),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            nodes.forEach { node ->
                Text(
                    text = stringResource(routeNodeLabelRes(node)),
                    modifier = Modifier.width(RouteNodeSlotWidth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberRoutePulseProgress(activeSegment: RouteSegment?): Float =
    key(activeSegment) {
        val pulse = rememberInfiniteTransition(label = "route-pulse")
        val pulseProgress by pulse.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "pulse-progress",
        )
        pulseProgress
    }

@Composable
private fun RouteNodeIcon(
    node: RouteNode,
    state: RouteNodeState,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val targetColor = routeNodeColor(state)
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(260),
        label = "route-node-color",
    )
    val alpha by animateFloatAsState(
        targetValue = if (state == RouteNodeState.INACTIVE) 0.58f else 1f,
        animationSpec = tween(220),
        label = "route-node-alpha",
    )
    val contentDescription = stringResource(routeNodeLabelRes(node))
    val stateDescription = stringResource(routeNodeStateLabelRes(state))
    Box(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(RouteNodeTouchSize),
            contentAlignment = Alignment.Center,
        ) {
            if (state == RouteNodeState.ERROR) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(RouteErrorIconSize),
                    tint = animatedColor.copy(alpha = alpha),
                )
            } else if (node == RouteNode.TOR) {
                Icon(
                    painter = painterResource(R.drawable.ic_tor_route),
                    contentDescription = null,
                    modifier = Modifier.size(RouteNodeIconSize),
                    tint = animatedColor.copy(alpha = alpha),
                )
            } else if (node == RouteNode.VPN) {
                Icon(
                    painter = painterResource(R.drawable.ic_route_vpn_shield_key),
                    contentDescription = null,
                    modifier = Modifier.size(RouteNodeIconSize),
                    tint = animatedColor.copy(alpha = alpha),
                )
            } else {
                Icon(
                    imageVector = routeNodeIcon(node),
                    contentDescription = null,
                    modifier = Modifier.size(RouteNodeIconSize),
                    tint = animatedColor.copy(alpha = alpha),
                )
            }
        }
        if (state == RouteNodeState.OK) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-1).dp, y = (-1).dp)
                        .size(RouteCheckSize),
                tint = semanticColors.success,
            )
        }
    }
}

@Composable
private fun RouteArrowsCanvas(
    nodes: List<RouteNode>,
    state: ConnectionPathUiState,
    pulseProgress: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val inactive = semanticColors.inactive
    val active = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    val success = semanticColors.success
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        val slotWidth = RouteNodeSlotWidth.toPx()
        val step = (size.width - slotWidth) / (nodes.size - 1)
        val firstNodeCenter = slotWidth / 2f
        val iconRadius = RouteNodeTouchSize.toPx() / 2f
        for (index in 0 until nodes.lastIndex) {
            val fromCenter = firstNodeCenter + index * step
            val toCenter = firstNodeCenter + (index + 1) * step
            val from = Offset(fromCenter + iconRadius + RouteDotSideGap.toPx(), y)
            val to = Offset(toCenter - iconRadius - RouteDotSideGap.toPx(), y)
            val segment = nodes.segmentAt(index)
            val segmentState = state.segmentState(segment)
            val isActive = state.activeSegment == segment
            val baseColor =
                when {
                    segmentState == RouteNodeState.ERROR -> error
                    segmentState == RouteNodeState.OK -> success
                    isActive -> active
                    else -> inactive
                }
            val dotColor =
                baseColor.copy(
                    alpha =
                        when {
                            segmentState == RouteNodeState.OK -> 0.86f
                            isActive -> 0.88f
                            else -> 0.34f
                        },
                )
            drawRouteDots(
                start = from,
                end = to,
                color = dotColor,
                phase = pulseProgress,
                animated = isActive && segmentState != RouteNodeState.ERROR,
            )
        }
    }
}

private fun DrawScope.drawRouteDots(
    start: Offset,
    end: Offset,
    color: Color,
    phase: Float,
    animated: Boolean,
) {
    val length = end.x - start.x
    val spacing = RouteDotSpacing.toPx()
    val radius = RouteDotRadius.toPx()
    val count = (length / spacing).toInt().coerceAtLeast(2)
    val shift = if (animated) spacing * phase else 0f
    repeat(count + 2) { index ->
        val x = start.x + (index * spacing + shift) % (length + spacing)
        if (x in start.x..end.x) {
            val progress = ((x - start.x) / length).coerceIn(0f, 1f)
            val edgeFade = (1f - kotlin.math.abs(progress - 0.5f) * 0.88f).coerceIn(0.5f, 1f)
            drawCircle(
                color = if (animated) color.copy(alpha = color.alpha * edgeFade) else color,
                radius = if (animated) radius * (0.9f + 0.22f * edgeFade) else radius,
                center = Offset(x, start.y),
            )
        }
    }
}

private fun ConnectionPathUiState.nodeState(node: RouteNode): RouteNodeState =
    when (node) {
        RouteNode.DEVICE -> device
        RouteNode.VPN -> vpn
        RouteNode.TOR -> tor
        RouteNode.INTERNET -> internet
    }

private fun ConnectionPathUiState.segmentState(segment: RouteSegment): RouteNodeState =
    when (segment) {
        RouteSegment.DEVICE_TO_VPN -> vpn
        RouteSegment.VPN_TO_TOR -> tor
        RouteSegment.VPN_TO_INTERNET -> internet
        RouteSegment.TOR_TO_INTERNET -> internet
    }

private fun List<RouteNode>.segmentAt(index: Int): RouteSegment =
    when (this[index] to this[index + 1]) {
        RouteNode.DEVICE to RouteNode.VPN -> RouteSegment.DEVICE_TO_VPN
        RouteNode.VPN to RouteNode.TOR -> RouteSegment.VPN_TO_TOR
        RouteNode.VPN to RouteNode.INTERNET -> RouteSegment.VPN_TO_INTERNET
        RouteNode.TOR to RouteNode.INTERNET -> RouteSegment.TOR_TO_INTERNET
        else -> RouteSegment.VPN_TO_INTERNET
    }

@Composable
private fun routeNodeColor(
    state: RouteNodeState,
): Color {
    val semanticColors = LocalFoxholeSemanticColors.current
    return when {
        state == RouteNodeState.ERROR -> MaterialTheme.colorScheme.error
        state == RouteNodeState.OK -> semanticColors.success
        state in setOf(RouteNodeState.CONNECTING, RouteNodeState.CHECKING) -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> semanticColors.inactive
    }
}

private fun routeNodeIcon(node: RouteNode): ImageVector =
    when (node) {
        RouteNode.DEVICE -> Icons.Outlined.PhoneAndroid
        RouteNode.VPN -> Icons.Outlined.Public
        RouteNode.TOR -> Icons.Outlined.Public
        RouteNode.INTERNET -> Icons.Outlined.Public
    }

private fun routeNodeLabelRes(node: RouteNode): Int =
    when (node) {
        RouteNode.DEVICE -> R.string.privacy_route_node_device
        RouteNode.VPN -> R.string.privacy_route_node_vpn
        RouteNode.TOR -> R.string.tor_badge
        RouteNode.INTERNET -> R.string.privacy_route_node_internet
    }

private fun routeNodeStateLabelRes(state: RouteNodeState): Int =
    when (state) {
        RouteNodeState.INACTIVE -> R.string.route_node_state_inactive
        RouteNodeState.CONNECTING -> R.string.route_node_state_connecting
        RouteNodeState.CHECKING -> R.string.route_node_state_checking
        RouteNodeState.OK -> R.string.route_node_state_ok
        RouteNodeState.ERROR -> R.string.route_node_state_error
    }

private val RouteNodeSlotWidth = 62.dp
private val RouteNodeTouchSize = 34.dp
private val RouteNodeIconSize = 25.dp
private val RouteErrorIconSize = 23.dp
private val RouteCheckSize = 13.dp
private val RouteDotRadius = 2.15.dp
private val RouteDotSpacing = 11.dp
private val RouteDotSideGap = 3.dp
