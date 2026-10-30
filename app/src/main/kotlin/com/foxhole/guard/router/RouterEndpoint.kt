package com.foxhole.guard.router

import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress

@Serializable
internal data class RouterEndpoint(val address: String, val port: Int = 8443) {
    init { require(routerLanAddress(address) != null && port in 1..65535) }
    fun socketAddress(): InetSocketAddress = InetSocketAddress(requireNotNull(routerLanAddress(address)), port)
}

internal fun routerLanAddress(value: String): InetAddress? {
    val segments = value.split('.')
    if (segments.size != 4 || segments.any { it.isEmpty() || it.length > 3 || it.any { char -> char !in '0'..'9' } ||
            it.length > 1 && it[0] == '0' }) return null
    val octets = segments.map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 } || octets[3] == 0 || octets[3] == 255) return null
    val private = octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168
    if (!private) return null
    return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
}

internal fun routerSubnetContains(local: InetAddress, destination: InetAddress, prefixLength: Int): Boolean {
    val left = local.address
    val right = destination.address
    if (left.size != 4 || right.size != 4 || prefixLength !in 8..30) return false
    for (bit in 0 until prefixLength) {
        val mask = 1 shl (7 - bit % 8)
        if ((left[bit / 8].toInt() and mask) != (right[bit / 8].toInt() and mask)) return false
    }
    return true
}
