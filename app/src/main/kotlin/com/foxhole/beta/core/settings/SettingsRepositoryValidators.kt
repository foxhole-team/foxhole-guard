package com.foxhole.beta.core.settings

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.network.ensurePublicHttpsUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun String.ifLoopbackOrDefault(): String =
    trim()
        .lowercase()
        .takeIf { value -> value in setOf("127.0.0.1", "localhost", "::1") }
        ?: "127.0.0.1"

internal fun normalizeIpInfoEndpoint(value: String): String {
    val normalized = value.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
    val host = normalized.toHttpUrlOrNull()?.host?.lowercase()
    return if (host == LEGACY_IP_INFO_HOST) {
        BuildConfig.DEFAULT_IP_INFO_ENDPOINT
    } else {
        normalized.ensurePublicHttpsUrl().toString()
    }
}

private const val LEGACY_IP_INFO_HOST = "api.ip.sb"
