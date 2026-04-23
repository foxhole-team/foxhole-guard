package com.foxhole.beta.core.network

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.security.MessageDigest
import java.util.Locale

data class NetworkFingerprint(
    val key: String,
    val transport: String,
    val isMetered: Boolean = false,
    val isRoaming: Boolean = false,
    val privateDnsActive: Boolean = false,
    val upstreamValidated: Boolean = false,
)

internal data class NetworkFingerprintSource(
    val transport: String,
    val isMetered: Boolean,
    val isRoaming: Boolean,
    val isValidated: Boolean = false,
    val interfaceName: String? = null,
    val dnsServers: List<String> = emptyList(),
    val routeGateways: List<String> = emptyList(),
    val privateDnsServerName: String? = null,
)

class NetworkFingerprintProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>() ?: error("missing connectivity manager")

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

internal fun buildNetworkFingerprint(source: NetworkFingerprintSource): NetworkFingerprint? {
    val normalizedTransport = source.transport.normalizedNetworkToken(defaultValue = "other") ?: "other"
    val normalizedDnsServers = source.dnsServers.normalizedNetworkTokens()
    val normalizedRouteGateways = source.routeGateways.normalizedNetworkTokens()
    val normalizedPrivateDns = source.privateDnsServerName?.normalizedNetworkToken()
    if (normalizedDnsServers.isEmpty() && normalizedRouteGateways.isEmpty() && normalizedPrivateDns == null) {
        return null
    }
    val fingerprintMaterial =
        buildList {
            add("transport=$normalizedTransport")
            add("metered=${source.isMetered}")
            add("roaming=${source.isRoaming}")
            source.interfaceName?.normalizedNetworkToken()?.let { value -> add("interface=$value") }
            if (normalizedDnsServers.isNotEmpty()) {
                add("dns=${normalizedDnsServers.joinToString(separator = ",")}")
            }
            if (normalizedRouteGateways.isNotEmpty()) {
                add("gateway=${normalizedRouteGateways.joinToString(separator = ",")}")
            }
            normalizedPrivateDns?.let { value -> add("private_dns=$value") }
        }.joinToString(separator = "|")
    return NetworkFingerprint(
        key = sha256Hex(fingerprintMaterial),
        transport = normalizedTransport,
        isMetered = source.isMetered,
        isRoaming = source.isRoaming,
        privateDnsActive = normalizedPrivateDns != null,
        upstreamValidated = source.isValidated,
    )
}

private fun LinkProperties?.normalizedDnsServers(): List<String> =
    this?.dnsServers.orEmpty().mapNotNull { address -> address.hostAddress }.normalizedNetworkTokens()

private fun LinkProperties?.normalizedRouteGateways(): List<String> =
    this?.routes.orEmpty().mapNotNull { route -> route.gateway?.hostAddress }.normalizedNetworkTokens()

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

private fun List<String>.normalizedNetworkTokens(): List<String> =
    mapNotNull(String::normalizedNetworkToken)
        .distinct()
        .sorted()

private fun String.normalizedNetworkToken(defaultValue: String? = null): String? =
    trim()
        .lowercase(Locale.ROOT)
        .takeIf(String::isNotBlank)
        ?: defaultValue

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
