package com.foxhole.core.runtime

import java.lang.ExceptionInInitializerError
import java.lang.reflect.InvocationTargetException

internal fun unwrapVpnRuntimeFailure(error: Throwable): Throwable {
    var current = error
    while (true) {
        current =
            when (current) {
                is InvocationTargetException -> current.targetException ?: current.cause ?: return current
                is ExceptionInInitializerError -> current.exception ?: current.cause ?: return current
                else -> return current
            }
    }
}

/**
 * Detailed diagnostic description. This can retain an unknown native exception message and must
 * not be copied into user-visible state; app/UI boundaries map failures to localized categories.
 */
fun describeVpnRuntimeFailure(error: Throwable): String {
    val root = unwrapVpnRuntimeFailure(error)
    val raw = root.message?.trim().takeIf { !it.isNullOrEmpty() } ?: root.javaClass.simpleName
    return knownRuntimeCompatibilityDescription(raw) ?: raw
}

private fun knownRuntimeCompatibilityDescription(message: String): String? {
    val normalized = message.lowercase()
    return when {
        hasAll(normalized, "tor", "udp") && hasAny(normalized, "not support", "unsupported") ->
            "TOR mode does not support UDP for this profile; switch TOR UDP policy to Proxy or Block."
        hasAny(normalized, "fakeip", "fake-ip") && hasAll(normalized, "strict", "private dns") ->
            "Strict Private DNS is incompatible with FakeIP DNS mode; use Secure DNS Auto/Remote mode."
        hasAll(normalized, "firewall") &&
            hasAny(normalized, "proxy", "tproxy") &&
            hasAny(normalized, "unsupported", "not available") ->
            "Firewall app rules are unavailable in the current proxy mode; use Tunnel mode for per-app firewall."
        else -> null
    }
}

private fun hasAll(message: String, vararg tokens: String): Boolean =
    tokens.all { token -> message.contains(token) }

private fun hasAny(message: String, vararg tokens: String): Boolean =
    tokens.any { token -> message.contains(token) }
