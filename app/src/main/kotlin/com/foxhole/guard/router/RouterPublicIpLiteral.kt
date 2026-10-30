package com.foxhole.guard.router

import java.net.InetAddress
import com.foxhole.core.network.isPrivateOrLocalAddress

internal fun routerPublicIpLiteral(value: String): InetAddress? {
    val host = value.removePrefix("[").removeSuffix("]")
    if (host.contains(':')) {
        if (!host.matches(Regex("[0-9a-fA-F:]+"))) return null
    } else {
        val fields = host.split('.')
        if (fields.size != 4 || fields.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.length > 1 && it[0] == '0' || it.toInt() !in 0..255 }) return null
    }
    return runCatching { InetAddress.getByName(host) }.getOrNull()?.takeUnless { it.isPrivateOrLocalAddress() }
}

