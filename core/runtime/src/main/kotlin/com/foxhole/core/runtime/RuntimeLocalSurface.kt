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

// Local surface inbounds for RuntimeConfigAssembler: local/LAN proxy inbounds, runtime
// loopback proxy inbound, experimental (clash/v2ray) api, proxy-surface resolution.
// Behaviour-preserving Phase B extraction; json-free (the LAN listen address is resolved
// by the assembler and passed in, keeping lanProxyAddressProvider out of this file).

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
        // The LAN leg is published on the phone's Wi-Fi address, so it is only ever raised WITH
        // credentials (stage2 §8). Without a usable password the surface stays down: an unreachable
        // LAN proxy is a nuisance, an unauthenticated one is an open relay into the owner's VPN/Tor
        // for every device on that network. The loopback leg above is untouched by this rule.
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

/**
 * Credentials for the LAN inbound, or `null` when the surface must not be published at all.
 *
 * Auth is mandatory for the LAN leg, so the stored `enabled` flag is ignored (forced on) instead of
 * being honoured — settings normalization pins it on as well, this is the second, runtime-side half
 * of the same invariant. A blank password fails closed (no inbound); a blank username falls back to
 * the same default login the settings layer uses, so a half-filled form cannot silently turn the
 * surface into an anonymous one.
 */
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

// Mirrors SettingsRepository.DEFAULT_PROXY_LOGIN: the login the settings layer writes when the user
// leaves the field empty, repeated here so the runtime never has to invent a different one.
private const val LAN_PROXY_FALLBACK_USERNAME = "foxhole"
