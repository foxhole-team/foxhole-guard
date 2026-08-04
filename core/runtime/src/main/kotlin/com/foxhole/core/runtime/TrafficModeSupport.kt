package com.foxhole.core.runtime

import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.ProxyAccessType

fun Settings.preferredAppProxyAccess(): HttpProxyAccess? {
    val surface =
        when (expert.localSurfaces.proxyMode) {
            ProxySurfaceMode.SOCKS5 -> expert.localSurfaces.socks
            ProxySurfaceMode.HTTP -> expert.localSurfaces.http
            ProxySurfaceMode.ALL -> expert.localSurfaces.mixed
        }
    return HttpProxyAccess(
        host = surface.host,
        port = surface.port,
        username = expert.localSurfaces.auth.username.takeIf { expert.localSurfaces.auth.enabled },
        password = expert.localSurfaces.auth.password.takeIf { expert.localSurfaces.auth.enabled },
        type =
        when (expert.localSurfaces.proxyMode) {
            ProxySurfaceMode.SOCKS5 -> ProxyAccessType.SOCKS
            ProxySurfaceMode.HTTP,
            ProxySurfaceMode.ALL,
            -> ProxyAccessType.HTTP
        },
    )
}

fun Settings.tunnelRuntimeProxyAccess(): HttpProxyAccess {
    return runtimeLoopbackProxyAccess(expert.localSurfaces)
}
