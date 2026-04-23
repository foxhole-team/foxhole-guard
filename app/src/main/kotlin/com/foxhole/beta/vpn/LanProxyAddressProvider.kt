package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.Inet4Address

interface LanProxyAddressProvider {
    fun currentWifiIpv4Address(): String?
}

object DisabledLanProxyAddressProvider : LanProxyAddressProvider {
    override fun currentWifiIpv4Address(): String? = null
}

class AndroidLanProxyAddressProvider(
    private val appContext: Context,
) : LanProxyAddressProvider {
    private val connectivityManager by lazy { appContext.getSystemService<ConnectivityManager>() ?: error("missing connectivity manager") }

    override fun currentWifiIpv4Address(): String? =
        ConnectivityNetworkRegistry.snapshot(appContext).asSequence().mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }
            connectivityManager.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence()
                .mapNotNull { linkAddress -> linkAddress.address as? Inet4Address }
                .mapNotNull { address ->
                    address.hostAddress?.takeIf { host ->
                        host.isNotBlank() && !address.isLoopbackAddress && !address.isLinkLocalAddress
                    }
                }.firstOrNull()
        }.firstOrNull()
}
