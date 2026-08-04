package com.foxhole.core.network

import java.net.InetAddress

internal fun testRemoteHostResolver(
    overrides: Map<String, InetAddress> = emptyMap(),
): RemoteHostResolver = { host ->
    val normalized = host.lowercase().trimEnd('.')
    listOf(overrides[normalized] ?: publicTestInetAddress(normalized))
}

internal fun ipv4TestAddress(
    literal: String,
): InetAddress = InetAddress.getByName(literal)

private fun publicTestInetAddress(host: String): InetAddress =
    InetAddress.getByAddress(
        host,
        byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()),
    )
