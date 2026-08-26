package com.foxhole.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

interface LanProxyAddressProvider {
    fun currentWifiIpv4Address(): String?

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
