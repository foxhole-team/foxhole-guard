package com.foxhole.guard

import android.content.Context
import androidx.annotation.StringRes
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.runtime.FoxCoreConfigTranslationException
import com.foxhole.core.runtime.FoxCoreRuntimeException
import com.foxhole.core.runtime.FoxCoreRuntimeFailure
import com.foxhole.core.runtime.TOR_ALL_APPS_NEEDS_FULL_TUNNEL_MARKER
import java.util.Locale

// Stable categories only: exception text stays out of UI because it may contain endpoints or credentials.
internal enum class UserFacingErrorKind {
    PROFILE_CONFIG_INVALID,
    REALITY_VERIFICATION,
    CERTIFICATE_VERIFICATION,
    TLS_HANDSHAKE,
    SERVER_UNREACHABLE,
    DNS_VALIDATION,
    TOR_VALIDATION,

    RUNTIME_ABI_MISMATCH,

    RUNTIME_LIBRARY_MISSING,

    RUNTIME_START_TIMEOUT,

    FOXCORE_RUNTIME_FAILURE,

    PACKET_TUNNEL_DNS_MISSING,

    TOR_ALL_APPS_NEEDS_FULL_TUNNEL,

    UNKNOWN,
}

private fun markerUserFacingErrorKind(fingerprint: String): UserFacingErrorKind? =
    when {
        fingerprint.contains(TOR_ALL_APPS_NEEDS_FULL_TUNNEL_MARKER) ->
            UserFacingErrorKind.TOR_ALL_APPS_NEEDS_FULL_TUNNEL

        fingerprint.contains("foxcore_runtime_abi_mismatch") ->
            UserFacingErrorKind.RUNTIME_ABI_MISMATCH

        fingerprint.contains(RUNTIME_START_TIMEOUT_MARKER) ->
            UserFacingErrorKind.RUNTIME_START_TIMEOUT

        fingerprint.contains("packet_tunnel_dns_missing") ->
            UserFacingErrorKind.PACKET_TUNNEL_DNS_MISSING

        else -> null
    }

internal fun classifyUserFacingError(error: Throwable?): UserFacingErrorKind {
    runtimeFailureUserFacingErrorKind(error)?.let { return it }
    foxCoreRuntimeUserFacingErrorKind(error)?.let { return it }
    val fingerprint =
        generateSequence(error) { cause -> cause.cause }
            .take(MAX_ERROR_CAUSE_DEPTH)
            .joinToString(separator = " ") { cause ->
                "${cause.javaClass.name} ${cause.message.orEmpty()}"
            }.lowercase(Locale.US)
    markerUserFacingErrorKind(fingerprint)?.let { return it }
    return when {
        fingerprint.containsAny(
            "unexpected json token",
            "stored profile config is not valid json",
            "profile has no resolved config",
            "jsondecodingexception",
            "serializationexception",
        ) -> UserFacingErrorKind.PROFILE_CONFIG_INVALID

        fingerprint.containsAny(
            "unsatisfiedlinkerror",
            "dlopen failed",
        ) -> UserFacingErrorKind.RUNTIME_LIBRARY_MISSING

        fingerprint.contains("reality verification") -> UserFacingErrorKind.REALITY_VERIFICATION

        fingerprint.containsAny(
            "certificate verify",
            "certificateexception",
            "certpath",
            "sslpeerunverified",
            "trust anchor",
        ) -> UserFacingErrorKind.CERTIFICATE_VERIFICATION

        fingerprint.containsAny(
            "sslhandshake",
            "tls handshake",
            "handshake_failure",
        ) -> UserFacingErrorKind.TLS_HANDSHAKE

        fingerprint.containsAny(
            "sockettimeout",
            "timed out",
            "timeout",
            "connection refused",
            "connectexception",
            "network is unreachable",
            "no route to host",
        ) -> UserFacingErrorKind.SERVER_UNREACHABLE

        fingerprint.containsAny(
            "unknownhost",
            "dns",
            "name resolution",
        ) -> UserFacingErrorKind.DNS_VALIDATION

        fingerprint.contains("tor") &&
            fingerprint.containsAny("bootstrap", "egress", "istor", "public reachability", "prove") ->
            UserFacingErrorKind.TOR_VALIDATION

        else -> UserFacingErrorKind.UNKNOWN
    }
}

private fun foxCoreRuntimeUserFacingErrorKind(error: Throwable?): UserFacingErrorKind? {
    val failure =
        generateSequence(error) { cause -> cause.cause }
            .take(MAX_ERROR_CAUSE_DEPTH)
            .filterIsInstance<FoxCoreRuntimeException>()
            .firstOrNull()
            ?.failure
            ?: return null
    return when (failure) {
        FoxCoreRuntimeFailure.ABI_MISMATCH -> UserFacingErrorKind.RUNTIME_ABI_MISMATCH
        FoxCoreRuntimeFailure.NATIVE_UNAVAILABLE -> UserFacingErrorKind.RUNTIME_LIBRARY_MISSING
        FoxCoreRuntimeFailure.CONFIG_REJECTED -> UserFacingErrorKind.PROFILE_CONFIG_INVALID
        else -> UserFacingErrorKind.FOXCORE_RUNTIME_FAILURE
    }
}

private fun runtimeFailureUserFacingErrorKind(error: Throwable?): UserFacingErrorKind? =
    when (error.runtimeFailureCodeOrNull()) {
        RuntimeFailureCode.VPN_NETWORK_MISSING,
        RuntimeFailureCode.ENDPOINT_REFUSED,
        -> UserFacingErrorKind.SERVER_UNREACHABLE

        RuntimeFailureCode.DNS_FAILURE -> UserFacingErrorKind.DNS_VALIDATION
        else -> null
    }

internal fun Throwable?.runtimeFailureCodeOrNull(): RuntimeFailureCode? =
    generateSequence(this) { cause -> cause.cause }
        .take(MAX_ERROR_CAUSE_DEPTH)
        .filterIsInstance<RuntimeFailureException>()
        .firstOrNull()
        ?.code

internal fun Context.userFacingErrorMessage(
    error: Throwable?,
    @StringRes fallbackRes: Int,
): String = getString(userFacingErrorMessageRes(error, fallbackRes))

@StringRes
internal fun userFacingErrorMessageRes(
    error: Throwable?,
    @StringRes fallbackRes: Int,
): Int =
    when (classifyUserFacingError(error)) {
        UserFacingErrorKind.TOR_ALL_APPS_NEEDS_FULL_TUNNEL -> R.string.error_tor_all_apps_needs_full_tunnel
        UserFacingErrorKind.PACKET_TUNNEL_DNS_MISSING -> R.string.vpn_error_packet_tunnel_dns_missing
        UserFacingErrorKind.PROFILE_CONFIG_INVALID -> R.string.profile_config_invalid_reimport
        UserFacingErrorKind.REALITY_VERIFICATION -> R.string.vpn_error_reality_verification_failed
        UserFacingErrorKind.CERTIFICATE_VERIFICATION -> R.string.vpn_error_certificate_verify_failed
        UserFacingErrorKind.TLS_HANDSHAKE -> R.string.vpn_error_tls_handshake_failed
        UserFacingErrorKind.SERVER_UNREACHABLE -> R.string.vpn_error_server_timeout
        UserFacingErrorKind.DNS_VALIDATION,
        UserFacingErrorKind.TOR_VALIDATION,
        -> R.string.error_dns_probe_failed
        UserFacingErrorKind.RUNTIME_ABI_MISMATCH -> R.string.error_runtime_abi_mismatch
        UserFacingErrorKind.RUNTIME_LIBRARY_MISSING -> R.string.error_runtime_missing
        UserFacingErrorKind.RUNTIME_START_TIMEOUT -> R.string.error_runtime_start_timeout
        UserFacingErrorKind.FOXCORE_RUNTIME_FAILURE -> R.string.error_foxcore_runtime_failed
        UserFacingErrorKind.UNKNOWN -> fallbackRes
    }

internal const val RUNTIME_START_TIMEOUT_MARKER = "foxcore_runtime_start_timeout"

internal fun diagnosticFailureLabel(error: Throwable?): String =
    generateSequence(error) { cause -> cause.cause }
        .take(MAX_ERROR_CAUSE_DEPTH)
        .joinToString(separator = " <- ") { cause ->
            when (cause) {
                is FoxCoreConfigTranslationException ->
                    "FoxCoreConfigTranslationException(" +
                        "${cause.rejection.name.lowercase()} at ${cause.path.asDiagnosticJsonPath()})"

                is FoxCoreRuntimeException ->
                    "FoxCoreRuntimeException(${cause.failure.code})"

                else -> cause.javaClass.simpleName
            }
        }.ifEmpty { "unknown" }

private fun String.asDiagnosticJsonPath(): String = replace('.', '/')

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any(::contains)

private const val MAX_ERROR_CAUSE_DEPTH = 8
