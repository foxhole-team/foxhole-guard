package com.foxhole.core.runtime

import java.net.InetAddress

internal fun tunInterfaceName(fdInfo: String): String? =
    fdInfo.lineSequence()
        .firstOrNull { it.startsWith("iff:") }
        ?.substringAfter(':')?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) }

internal fun matchesOwnedTunNetwork(
    tunFd: Int,
    advertisedDnsServers: List<String>,
    networkInterfaceName: String?,
    networkDnsServers: List<InetAddress>,
    readFdInfo: (Int) -> String,
    isCurrent: () -> Boolean,
): Boolean {
    if (tunFd < 0 || !isCurrent()) return false
    val interfaceName = runCatching { tunInterfaceName(readFdInfo(tunFd)) }.getOrNull() ?: return false
    val expectedDns = advertisedDnsServers.map { numericTunDnsAddress(it) ?: return false }.toSet()
    val matches = interfaceName == networkInterfaceName && expectedDns.isNotEmpty() &&
        expectedDns == networkDnsServers.toSet()
    // A close or replacement during fdinfo acquisition cannot validate the next descriptor owner.
    return matches && isCurrent()
}

private fun numericTunDnsAddress(value: String): InetAddress? {
    val ipv4 = value.split('.')
    val isIpv4 = ipv4.size == 4 && ipv4.all { octet ->
        octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
            (octet.toIntOrNull() ?: -1) in 0..255
    }
    val isIpv6 = value.count { it == ':' } >= 2 && value.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
    }
    if (!isIpv4 && !isIpv6) return null
    return runCatching { InetAddress.getByName(value) }.getOrNull()
}
