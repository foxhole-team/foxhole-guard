package com.foxhole.core.model

// Pure IpInfo helpers shared by the runtime engine and the app (moved out of the host
// connection controller so the engine does not depend on it).
fun IpInfo.withDnsServers(
    localDnsServers: List<String>,
    remoteDnsServers: List<String>,
): IpInfo =
    copy(
        localDnsServers = localDnsServers,
        remoteDnsServers = remoteDnsServers,
    )

fun IpInfo.retainKnownLocationFrom(previous: IpInfo?): IpInfo {
    if (previous == null || !samePrimaryAddress(previous)) {
        return this
    }
    return copy(
        countryCode = countryCode ?: previous.countryCode,
        countryName = countryName ?: previous.countryName,
        city = city?.takeIf(String::isNotBlank) ?: previous.city,
        isp = isp?.takeIf(String::isNotBlank) ?: previous.isp,
    )
}

fun IpInfo.retainKnownDetailsFrom(previous: IpInfo?): IpInfo {
    val previousInfo = previous ?: return this
    if (!samePrimaryAddress(previousInfo)) {
        return this
    }
    return retainKnownLocationFrom(previousInfo).copy(
        ipv4 = ipv4 ?: previousInfo.ipv4,
        localDnsServers = localDnsServers.ifEmpty { previousInfo.localDnsServers },
        remoteDnsServers = remoteDnsServers.ifEmpty { previousInfo.remoteDnsServers },
    )
}

fun IpInfo.samePrimaryAddress(other: IpInfo): Boolean =
    primaryAddressKey() == other.primaryAddressKey()

private fun IpInfo.primaryAddressKey(): String =
    ipv4 ?: ip

// A validated tunnel exit qualifies as the Tor route exit only when it has a real located address
// that differs from the dashboard VPN exit. This keeps the bypass case (where the validated IP is
// the VPN exit) from being mislabeled as Tor.
fun IpInfo.isDistinctTorRouteExit(dashboardIpInfo: IpInfo?): Boolean {
    val address = primaryAddressKey().takeIf(String::isNotBlank) ?: return false
    if (countryCode?.isNotBlank() != true) return false
    // Require a known VPN dashboard exit to compare against. Without it we cannot tell whether this
    // validated IP is the Tor exit or the VPN exit, and must not publish the VPN exit as Tor.
    val dashboardAddress = dashboardIpInfo?.primaryAddressKey()?.takeIf(String::isNotBlank) ?: return false
    return dashboardAddress != address
}
