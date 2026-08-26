package com.foxhole.core.model

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

fun IpInfo.isDistinctTorRouteExit(dashboardIpInfo: IpInfo?): Boolean {
    val address = primaryAddressKey().takeIf(String::isNotBlank) ?: return false
    if (countryCode?.isNotBlank() != true) return false

    val dashboardAddress = dashboardIpInfo?.primaryAddressKey()?.takeIf(String::isNotBlank) ?: return false
    return dashboardAddress != address
}
