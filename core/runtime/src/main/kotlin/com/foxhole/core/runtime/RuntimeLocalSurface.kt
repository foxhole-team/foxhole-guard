package com.foxhole.core.runtime

import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.ProxyAccessType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

internal fun buildLocalSurfaceInbounds(
    localSurfaces: LocalSurfaceSettings,
    includeLocalProxy: Boolean,
    lanListenAddress: String?,
): List<JsonObject> =
    buildList {
        if (includeLocalProxy) {
            val localSurface = localSurfaces.proxySurface()
            add(
                proxyInbound(
                    type = localSurfaces.proxyMode.inboundType,
                    tag = "${localSurfaces.proxyMode.inboundTag}-in",
                    listenHost = localSurface.host,
                    port = localSurface.port,
                    auth = localSurfaces.auth,
                ),
            )
        }

        val lanAuth = localSurfaces.lanAuth.mandatoryLanProxyAuthOrNull()
        if (lanListenAddress != null && lanAuth != null) {
            val lanSurface = localSurfaces.lanProxySurface()
            add(
                proxyInbound(
                    type = localSurfaces.lanProxyMode.inboundType,
                    tag = "${localSurfaces.lanProxyMode.inboundTag}-in-lan",
                    listenHost = lanListenAddress,
                    port = lanSurface.port,
                    auth = lanAuth,
                ),
            )
        }
    }

internal fun LocalAuthSettings.mandatoryLanProxyAuthOrNull(): LocalAuthSettings? =
    takeIf { password.isNotBlank() }
        ?.copy(
            enabled = true,
            username = username.ifBlank { LAN_PROXY_FALLBACK_USERNAME },
        )

internal fun runtimeLoopbackProxyInbound(localSurfaces: LocalSurfaceSettings): JsonObject {
    val localSurface = localSurfaces.http
    return proxyInbound(
        type = ProxySurfaceMode.HTTP.inboundType,
        tag = RUNTIME_LOOPBACK_PROXY_INBOUND_TAG,
        listenHost = localSurface.host,
        port = localSurface.port,
        auth = runtimeLoopbackProxyAuth(),
    )
}

internal fun runtimeLoopbackProxyAccess(localSurfaces: LocalSurfaceSettings): HttpProxyAccess {
    val localSurface = localSurfaces.http
    val auth = runtimeLoopbackProxyAuth()
    return HttpProxyAccess(
        host = localSurface.host,
        port = localSurface.port,
        username = auth.username,
        password = auth.password,
        type = ProxyAccessType.HTTP,
    )
}

internal fun runtimeLoopbackProxyAuth(): LocalAuthSettings =
    LocalAuthSettings(
        enabled = true,
        username = RUNTIME_LOOPBACK_PROXY_USERNAME,
        password = RuntimeLoopbackProxySecret.password,
    )

internal fun proxyInbound(
    type: String,
    tag: String,
    listenHost: String,
    port: Int,
    auth: LocalAuthSettings,
): JsonObject =
    buildJsonObject {
        put("type", type)
        put("tag", tag)
        put("listen", listenHost)
        put("listen_port", port)
        if (auth.enabled) {
            putJsonArray("users") {
                add(
                    buildJsonObject {
                        put("username", auth.username)
                        put("password", auth.password)
                    },
                )
            }
        }
    }

internal fun patchExperimental(
    existing: JsonObject?,
    localSurfaces: LocalSurfaceSettings,
): JsonObject =
    buildJsonObject {
        existing?.forEach { (key, value) ->
            if (key != "clash_api" && key != "v2ray_api") {
                put(key, value)
            }
        }
        if (localSurfaces.clashApi.enabled) {
            putJsonObject("clash_api") {
                put("external_controller", "${localSurfaces.clashApi.host}:${localSurfaces.clashApi.port}")
                put("secret", localSurfaces.auth.apiSecret)
            }
        }
    }

internal fun LocalSurfaceSettings.proxySurface(): ProxyInboundSettings =
    when (proxyMode) {
        ProxySurfaceMode.SOCKS5 -> socks
        ProxySurfaceMode.HTTP -> http
        ProxySurfaceMode.ALL -> mixed
    }

internal fun LocalSurfaceSettings.lanProxySurface(): ProxyInboundSettings =
    when (lanProxyMode) {
        ProxySurfaceMode.SOCKS5 -> socks
        ProxySurfaceMode.HTTP -> http
        ProxySurfaceMode.ALL -> mixed
    }

internal val ProxySurfaceMode.inboundType: String
    get() =
        when (this) {
            ProxySurfaceMode.SOCKS5 -> "socks"
            ProxySurfaceMode.HTTP -> "http"
            ProxySurfaceMode.ALL -> "mixed"
        }

internal val ProxySurfaceMode.inboundTag: String
    get() =
        when (this) {
            ProxySurfaceMode.SOCKS5 -> "socks"
            ProxySurfaceMode.HTTP -> "http"
            ProxySurfaceMode.ALL -> "mixed"
        }

internal fun JsonObject.hasDnsServers(): Boolean =
    this["servers"]?.jsonArray?.isNotEmpty() == true

private object RuntimeLoopbackProxySecret {
    val password: String = UUID.randomUUID().toString() + UUID.randomUUID().toString()
}

private const val RUNTIME_LOOPBACK_PROXY_USERNAME = "foxhole-runtime"

private const val LAN_PROXY_FALLBACK_USERNAME = "foxhole"
