package com.foxhole.guard.router.ui

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.foxhole.guard.ui.cli.components.cliFlagIconSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterWifiConnection
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate

@Composable
internal fun rememberRouterWifiConnected(): State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return produceState(false, context) {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        value = RouterWifiConnection.hasPhysicalWifi(manager)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        routerNetworkEvents(manager, request).collect { value = RouterWifiConnection.hasPhysicalWifi(manager) }
    }
}

@Composable
internal fun rememberRouterNetworkGeneration(): State<Long> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return produceState(0L, context) {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        routerNetworkEvents(manager, request).collect { value++ }
    }
}

private fun routerNetworkEvents(manager: ConnectivityManager, request: NetworkRequest): Flow<Unit> = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
    }
    manager.registerNetworkCallback(request, callback)
    trySend(Unit)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.conflate()

@Composable
internal fun RouterWifiIndicator(connected: Boolean) {
    val style = MaterialTheme.typography.bodyMedium
    val iconSize = cliFlagIconSize(style, 1, 1).height
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.guard_router_wifi_status_label), style = style)
            RouterWifiGlyph(connected, Modifier.size(iconSize))
        }
        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(stringResource(R.string.guard_router_beta_badge), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RouterWifiGlyph(connected: Boolean, modifier: Modifier) {
    val label = stringResource(if (connected) R.string.guard_router_router_review_wifi_connected
        else R.string.guard_router_vpn_router_error_wifi)
    val color = if (connected) routerConnectColor() else guardRouterDeleteColor()
    Canvas(modifier.semantics { contentDescription = label }) {
        // Keep layout fixed while the 16-unit optical ink fills the capital-letter height.
        withTransform({
            translate(-size.width / 4f, -size.height / 4f)
            scale(size.width / 16f, size.height / 16f, pivot = Offset.Zero)
        }) {
            val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
            val arcs = Path().apply {
                moveTo(2f, 8f); cubicTo(7f, 3f, 17f, 3f, 22f, 8f)
                moveTo(5f, 11f); cubicTo(9f, 7.5f, 15f, 7.5f, 19f, 11f)
                moveTo(8.5f, 14.5f); cubicTo(10.5f, 12.5f, 13.5f, 12.5f, 15.5f, 14.5f)
            }
            drawPath(arcs, color, style = stroke)
            drawCircle(color, radius = 1f, center = Offset(12f, 18f))
            if (!connected) drawLine(color, Offset(3f, 3f), Offset(21f, 21f), strokeWidth = 1.7f, cap = StrokeCap.Round)
        }
    }
}
