package com.foxhole.guard.router

import java.io.IOException
import java.net.Socket

internal fun createRouterHomeVpnSocket(requireCurrent: () -> Unit, bindVpn: (Socket) -> Unit,
    create: () -> Socket = ::Socket): Socket {
    requireCurrent()
    val socket = create()
    try {
        bindVpn(socket)
        requireCurrent()
        return socket
    } catch (failure: Exception) {
        try { socket.close() } catch (_: IOException) {}
        throw failure
    }
}
