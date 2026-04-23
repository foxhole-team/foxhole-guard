package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.network.HttpProxyAccess

internal fun Settings.preferredAppProxyAccess(): HttpProxyAccess? {
    val surface =
        expert.localSurfaces.http.takeIf { it.enabled }
            ?: expert.localSurfaces.mixed.takeIf { it.enabled }
            ?: return null
    return HttpProxyAccess(
        host = surface.host,
        port = surface.port,
        username = expert.localSurfaces.auth.username.takeIf { expert.localSurfaces.auth.enabled },
        password = expert.localSurfaces.auth.password.takeIf { expert.localSurfaces.auth.enabled },
    )
}
