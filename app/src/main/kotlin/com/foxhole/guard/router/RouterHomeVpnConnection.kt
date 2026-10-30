package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

internal class RouterHomeVpnRequired : IOException("router_home_vpn_required")

internal class RouterHomeVpnConnection private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    val endpoint: RouterEndpoint,
    private val accessCheck: () -> Unit,
) {
    fun requireCurrent() {
        accessCheck()
        if (!isHomeVpn(manager.getNetworkCapabilities(network)) ||
            manager.getLinkProperties(network)?.reachesHomeRouter(endpoint) != true) throw RouterHomeVpnRequired()
    }

    // This socket stays inside the selected VPN. It must never be protected or bound to an underlying network.
    private fun createSocket(): Socket = createRouterHomeVpnSocket(::requireCurrent, network::bindSocket)

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = this@RouterHomeVpnConnection.createSocket()
        override fun createSocket(host: String, port: Int): Socket = connected(host, port)
        override fun createSocket(address: InetAddress, port: Int): Socket = connected(address.hostAddress.orEmpty(), port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw RouterHomeVpnRequired()
        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw RouterHomeVpnRequired()
        private fun connected(host: String, port: Int): Socket {
            if (host != endpoint.address || port != endpoint.port) throw RouterHomeVpnRequired()
            return createSocket().apply {
                try { connect(endpoint.socketAddress(), 15000); requireCurrent() }
                catch (failure: Exception) { close(); throw failure }
            }
        }
    }

    companion object {
        fun select(manager: ConnectivityManager, endpoint: RouterEndpoint, accessCheck: () -> Unit, expectedNetworkHandle: Long? = null): RouterHomeVpnConnection {
            accessCheck()
            val candidates = manager.allNetworks.filter { network ->
                (expectedNetworkHandle == null || network.networkHandle == expectedNetworkHandle) &&
                    isHomeVpn(manager.getNetworkCapabilities(network)) &&
                    manager.getLinkProperties(network)?.reachesHomeRouter(endpoint) == true
            }
            return RouterHomeVpnConnection(manager, candidates.singleOrNull() ?: throw RouterHomeVpnRequired(), endpoint, accessCheck)
        }
    }
}

private fun isHomeVpn(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

private fun LinkProperties.reachesHomeRouter(endpoint: RouterEndpoint): Boolean {
    val destination = endpoint.socketAddress().address
    val matching = routes.filter { it.destination.contains(destination) }.map {
        RouterHomeVpnRoute(it.destination.prefixLength, Build.VERSION.SDK_INT < 33 || it.type == RouteInfo.RTN_UNICAST)
    }
    return routerHomeVpnRouteUsable(matching)
}
