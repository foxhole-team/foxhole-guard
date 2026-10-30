package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

internal class RouterWifiRequired : IOException("router_wifi_required")

internal class RouterWifiConnection private constructor(
    private val manager: ConnectivityManager,
    val network: Network,
    val endpoint: RouterEndpoint,
    val localAddress: InetAddress,
    private val socketProtection: RouterSocketProtection?,
    private val accessCheck: () -> Unit,
) : RouterSocketProvider {
    override fun createSocket(): Socket = createSocketForOperation(null)

    internal fun createSocketForOperation(operation: RouterIoOperation?): Socket {
        requireCurrent()
        return createRouterWifiSocket(vpnActive = { routerVpnActive(manager) },
            protection = socketProtection, bindWifi = { network.bindSocket(it) },
            create = { Socket().also { operation?.register(it) } })
    }

    fun requireCurrent() {
        accessCheck()
        val capabilities = manager.getNetworkCapabilities(network) ?: throw RouterWifiRequired()
        val properties = manager.getLinkProperties(network) ?: throw RouterWifiRequired()
        if (!isRouterWifi(capabilities) || !properties.reaches(endpoint.socketAddress().address) ||
            properties.linkAddresses.none { it.address == localAddress }) throw RouterWifiRequired()
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = this@RouterWifiConnection.createSocket()
        override fun createSocket(host: String, port: Int): Socket = connected(host, port)
        override fun createSocket(address: InetAddress, port: Int): Socket = connected(address.hostAddress ?: "", port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw RouterWifiRequired()
        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw RouterWifiRequired()
        private fun connected(host: String, port: Int): Socket {
            if (host != endpoint.address || port != endpoint.port) throw RouterWifiRequired()
            return createSocket().apply { try { connect(endpoint.socketAddress(), 15000) } catch (error: IOException) { close(); throw error } }
        }
    }

    companion object {
        fun select(manager: ConnectivityManager, endpoint: RouterEndpoint, socketProtection: RouterSocketProtection? = null,
            accessCheck: () -> Unit = {}): RouterWifiConnection {
            accessCheck()
            val destination = endpoint.socketAddress().address
            val candidates = manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
                if (!isRouterWifi(capabilities) || !properties.reaches(destination)) return@mapNotNull null
                val local = properties.linkAddresses.firstOrNull { it.address is Inet4Address && it.prefixLength in 8..30 &&
                    routerSubnetContains(it.address, destination, it.prefixLength)
                }?.address ?: return@mapNotNull null
                RouterWifiConnection(manager, network, endpoint, local, socketProtection, accessCheck)
            }
            return candidates.singleOrNull() ?: throw RouterWifiRequired()
        }

        fun hasPhysicalWifi(manager: ConnectivityManager): Boolean = manager.allNetworks.any {
            isRouterWifi(manager.getNetworkCapabilities(it))
        }

        fun suggestedGateway(manager: ConnectivityManager): String? {
            val routes = manager.allNetworks.flatMap { network ->
                if (!isRouterWifi(manager.getNetworkCapabilities(network))) emptyList()
                else manager.getLinkProperties(network)?.routes.orEmpty().filter { it.isDefaultRoute }
            }
            return routerWifiGateway(routes.mapNotNull { it.gateway })
        }
    }
}

private fun isRouterWifi(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

private fun LinkProperties.reaches(address: InetAddress): Boolean = routes.any {
    !it.isDefaultRoute && it.destination.contains(address) &&
        (android.os.Build.VERSION.SDK_INT < 33 || it.type == android.net.RouteInfo.RTN_UNICAST)
}
