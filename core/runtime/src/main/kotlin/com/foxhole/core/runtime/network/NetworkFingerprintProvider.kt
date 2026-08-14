package com.foxhole.core.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.foxhole.core.network.NetworkFingerprint
import com.foxhole.core.network.NetworkFingerprintSource
import com.foxhole.core.network.buildNetworkFingerprint

class NetworkFingerprintProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: error("missing connectivity manager")

    fun currentFingerprint(): NetworkFingerprint? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.isUpstreamNetwork()) {
            return null
        }
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        return buildNetworkFingerprint(
            NetworkFingerprintSource(
                transport = capabilities.transportName(),
                isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isRoaming = capabilities.isRoamingCompat(),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                interfaceName = linkProperties?.interfaceName,
                dnsServers = linkProperties.normalizedDnsServers(),
                routeGateways = linkProperties.normalizedRouteGateways(),
                privateDnsServerName = linkProperties.privateDnsServerNameCompat(),
            ),
        )
    }
}

private fun LinkProperties?.normalizedDnsServers(): List<String> =
    this?.dnsServers.orEmpty()
        .mapNotNull { address -> address.hostAddress }
        .normalizedProviderNetworkTokens()

private fun LinkProperties?.normalizedRouteGateways(): List<String> =
    this?.routes.orEmpty()
        .mapNotNull { route -> route.gateway?.hostAddress }
        .normalizedProviderNetworkTokens()

private fun LinkProperties?.privateDnsServerNameCompat(): String? {
    if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return null
    }
    return privateDnsServerName
}

private fun NetworkCapabilities.isRoamingCompat(): Boolean =
    resolveFingerprintRoamingState(
        sdkInt = Build.VERSION.SDK_INT,
        hasNotRoamingCapability =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            null
        },
    )

internal fun resolveFingerprintRoamingState(
    sdkInt: Int,
    hasNotRoamingCapability: Boolean?,
): Boolean =
    if (sdkInt >= Build.VERSION_CODES.P) {
        hasNotRoamingCapability == false
    } else {
        false
    }

private fun NetworkCapabilities.transportName(): String =
    when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

private fun NetworkCapabilities.isUpstreamNetwork(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

private fun List<String>.normalizedProviderNetworkTokens(): List<String> =
    mapNotNull(String::normalizedProviderNetworkToken)
        .distinct()
        .sorted()

private fun String.normalizedProviderNetworkToken(): String? =
    trim()
        .lowercase()
        .takeIf(String::isNotBlank)
