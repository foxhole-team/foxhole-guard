package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.network.ProxyAccessType

internal fun Settings.preferredAppProxyAccess(): HttpProxyAccess? {
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

internal fun Settings.tunnelRuntimeProxyAccess(): HttpProxyAccess {
    val surface =
        when (expert.localSurfaces.proxyMode) {
            ProxySurfaceMode.SOCKS5 -> expert.localSurfaces.socks
            ProxySurfaceMode.HTTP -> expert.localSurfaces.http
            ProxySurfaceMode.ALL -> expert.localSurfaces.mixed
        }
    return HttpProxyAccess(
        host = surface.host,
        port = surface.port,
        type = ProxyAccessType.HTTP,
    )
}
