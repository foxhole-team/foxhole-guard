package com.foxhole.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

interface LanProxyAddressProvider {
    fun currentWifiIpv4Address(): String?

    /**
     * The full binding the core needs before it will publish a listener: handle, address, interface
     * name and transport. Null means there is no network the LAN proxy may be published on — the
     * beta is deliberately Wi-Fi/Ethernet IPv4 only, so cellular and VPN transports never qualify.
     */
    fun currentLanBinding(): LanNetworkBinding? = null
}

internal object DisabledLanProxyAddressProvider : LanProxyAddressProvider {
    override fun currentWifiIpv4Address(): String? = null

    override fun currentLanBinding(): LanNetworkBinding? = null
}

class AndroidLanProxyAddressProvider(
    private val appContext: Context,
) : LanProxyAddressProvider {
    private val connectivityManager by lazy {
        appContext.getSystemService(ConnectivityManager::class.java) ?: error("missing connectivity manager")
    }

    override fun currentWifiIpv4Address(): String? = currentLanBinding()?.localAddress

    override fun currentLanBinding(): LanNetworkBinding? =
        ConnectivityNetworkRegistry.snapshot(appContext).asSequence().mapNotNull { network ->
            bindingFor(network)
        }.firstOrNull()

    private fun bindingFor(network: Network): LanNetworkBinding? {
        val transport = connectivityManager.getNetworkCapabilities(network)?.lanTransportOrNull()
        val link = connectivityManager.getLinkProperties(network)
        val address = link?.firstUsableIpv4Address()
        val interfaceName = link?.interfaceName?.takeIf(String::isNotBlank)
        if (transport == null || address == null || interfaceName == null) {
            return null
        }
        return LanNetworkBinding(
            networkHandle = network.networkHandle,
            localAddress = address,
            interfaceName = interfaceName,
            transport = transport,
        )
    }

    /**
     * Wi-Fi and Ethernet only. A VPN transport is refused first and deliberately: publishing the
     * listener on the tunnel itself puts it where no LAN client can reach it and where the relay
     * would loop back into its own upstream.
     */
    private fun NetworkCapabilities.lanTransportOrNull(): String? =
        when {
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LAN_TRANSPORT_WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LAN_TRANSPORT_ETHERNET
            else -> null
        }

    private fun LinkProperties.firstUsableIpv4Address(): String? =
        linkAddresses
            .asSequence()
            .mapNotNull { linkAddress -> linkAddress.address as? Inet4Address }
            .mapNotNull { address ->
                address.hostAddress?.takeIf { host ->
                    host.isNotBlank() && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
            }.firstOrNull()
}

private const val LAN_TRANSPORT_WIFI = "wifi"
private const val LAN_TRANSPORT_ETHERNET = "ethernet"
