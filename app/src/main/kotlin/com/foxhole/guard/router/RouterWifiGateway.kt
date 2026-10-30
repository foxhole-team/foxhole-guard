package com.foxhole.guard.router

import java.net.Inet4Address
import java.net.InetAddress

internal fun routerWifiGateway(gateways: List<InetAddress>): String? = gateways
    .mapNotNull { (it as? Inet4Address)?.hostAddress }
    .filter { routerLanAddress(it) != null }
    .distinct()
    .singleOrNull()
