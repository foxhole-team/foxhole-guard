package com.foxhole.guard.router

import java.io.IOException
import java.net.Socket

internal fun interface RouterSocketProtection {
    fun protect(socket: Socket): Boolean
    fun isCurrent(socket: Socket): Boolean = true
}
internal class RouterVpnSocketUnavailable : IOException("router_vpn_socket_unavailable")

internal fun createRouterWifiSocket(vpnActive: () -> Boolean, protection: RouterSocketProtection?,
    bindWifi: (Socket) -> Unit, create: () -> Socket = ::Socket): Socket {
    val socket = create()
    try {
        val protected = protection?.protect(socket) == true
        if (vpnActive() && !protected) throw RouterVpnSocketUnavailable()
        bindWifi(socket)
        if (protected && protection?.isCurrent(socket) != true) throw RouterVpnSocketUnavailable()
        if (vpnActive() && !protected) throw RouterVpnSocketUnavailable()
        return socket
    } catch (failure: Exception) {
        try { socket.close() } catch (_: IOException) {}
        throw failure
    }
}
