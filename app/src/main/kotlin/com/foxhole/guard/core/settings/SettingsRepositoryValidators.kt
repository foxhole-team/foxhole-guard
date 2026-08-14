package com.foxhole.guard.core.settings

import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.guard.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun String.ifLoopbackOrDefault(): String =
    trim()
        .lowercase()
        .takeIf { value -> value in setOf("127.0.0.1", "localhost", "::1") }
        ?: "127.0.0.1"

internal fun normalizeIpInfoEndpoint(value: String): String {
    val normalized = value.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
    val host = normalized.toHttpUrlOrNull()?.host?.lowercase()
    return if (host in LEGACY_IP_INFO_HOSTS) {
        BuildConfig.DEFAULT_IP_INFO_ENDPOINT
    } else {
        normalized.ensurePublicHttpsUrl().toString()
    }
}

private val LEGACY_IP_INFO_HOSTS = setOf("api.ip.sb", "api.ipify.org")
