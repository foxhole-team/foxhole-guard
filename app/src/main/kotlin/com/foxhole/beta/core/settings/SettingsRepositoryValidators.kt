package com.foxhole.beta.core.settings

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_STEP_SECONDS
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
    return if (host in LEGACY_IP_INFO_HOSTS) {
        BuildConfig.DEFAULT_IP_INFO_ENDPOINT
    } else {
        normalized.ensurePublicHttpsUrl().toString()
    }
}

internal fun normalizeSmartStartTimeoutSeconds(
    value: Int,
    minSeconds: Int,
): Int {
    val bounded = value.coerceIn(minSeconds, SMART_START_TIMEOUT_MAX_SECONDS)
    val offset = bounded - minSeconds
    val roundedOffset = (offset / SMART_START_TIMEOUT_STEP_SECONDS) * SMART_START_TIMEOUT_STEP_SECONDS
    return (minSeconds + roundedOffset).coerceIn(minSeconds, SMART_START_TIMEOUT_MAX_SECONDS)
}

internal fun normalizeSmartStartSubscriptionRetryAttempts(value: Int): Int =
    SMART_START_SUBSCRIPTION_RETRY_ATTEMPT_OPTIONS
        .minByOrNull { option -> kotlin.math.abs(option - value) }
        ?: 3

internal fun normalizeSmartStartSubscriptionRetryDelaySeconds(value: Int): Int =
    SMART_START_SUBSCRIPTION_RETRY_DELAY_OPTIONS
        .minByOrNull { option -> kotlin.math.abs(option - value) }
        ?: 5

internal val SMART_START_SUBSCRIPTION_RETRY_ATTEMPT_OPTIONS =
    listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20)

internal val SMART_START_SUBSCRIPTION_RETRY_DELAY_OPTIONS =
    listOf(1, 3, 5, 10, 15, 20, 25, 30, 40, 50, 60)

private val LEGACY_IP_INFO_HOSTS = setOf("api.ip.sb", "api.ipify.org")
