package com.foxhole.guard

import android.content.Context
import androidx.annotation.StringRes
import com.foxhole.core.runtime.FoxCoreConfigTranslationException
import java.util.Locale

/**
 * Stable error categories allowed to cross into UI state, banners, and notifications.
 *
 * Exception messages stay in diagnostics. They may contain Java implementation details, remote
 * hosts, certificate paths or fragments of a provider configuration, so unknown failures always
 * collapse to the caller's localized fallback.
 */
internal enum class UserFacingErrorKind {
    PROFILE_CONFIG_INVALID,
    REALITY_VERIFICATION,
    CERTIFICATE_VERIFICATION,
    TLS_HANDSHAKE,
    SERVER_UNREACHABLE,
    DNS_VALIDATION,
    TOR_VALIDATION,

    /**
     * The native core loaded, but it is not the one this app was built against.
     * Distinct from "no library" because the fix is different and because the
     * app and the core are two repositories that can drift apart.
     */
    RUNTIME_ABI_MISMATCH,

    /**
     * A WireGuard/AmneziaWG profile that carries no resolver of its own.
     *
     * An L3 tunnel moves IP packets and offers no stream outbound to resolve through, so the
     * runtime advertises the resolver the profile names and lets lookups ride inside the tunnel.
     * With no resolver named there is nothing honest left to do: resolving beside the tunnel would
     * put the user's lookups on the open network while the screen says they are tunnelled.
     *
     * Classified before [DNS_VALIDATION] because that one means "the tunnel came up and then DNS
     * did not work", and the message says so. This refusal happens BEFORE the tunnel starts, and
     * the path it names ($.dns.servers) contains "dns", so the generic rule used to catch it and
     * tell the user about a validation step that never ran.
     */
    PACKET_TUNNEL_DNS_MISSING,

    UNKNOWN,
}

internal fun classifyUserFacingError(error: Throwable?): UserFacingErrorKind {
    val fingerprint =
        generateSequence(error) { cause -> cause.cause }
            .take(MAX_ERROR_CAUSE_DEPTH)
            .joinToString(separator = " ") { cause ->
                "${cause.javaClass.name} ${cause.message.orEmpty()}"
            }.lowercase(Locale.US)
    return when {
        fingerprint.containsAny(
            "unexpected json token",
            "stored profile config is not valid json",
            "profile has no resolved config",
            "jsondecodingexception",
            "serializationexception",
        ) -> UserFacingErrorKind.PROFILE_CONFIG_INVALID

        fingerprint.contains("foxcore_runtime_abi_mismatch") ->
            UserFacingErrorKind.RUNTIME_ABI_MISMATCH

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

        fingerprint.contains("packet_tunnel_dns_missing") ->
            UserFacingErrorKind.PACKET_TUNNEL_DNS_MISSING

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

internal fun Context.userFacingErrorMessage(
    error: Throwable?,
    @StringRes fallbackRes: Int,
): String =
    getString(
        when (classifyUserFacingError(error)) {
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
            UserFacingErrorKind.UNKNOWN -> fallbackRes
        },
    )

/**
 * The technical cause of a failure, for the diagnostics journal only.
 *
 * This file already says exception messages stay in diagnostics. They did not: the connect and
 * reload paths published the localized fallback and dropped the throwable, so a profile the
 * translator rejected reached the journal as the same sentence the user had just read on screen,
 * naming neither the field nor the reason. On a live VLESS profile that failure took 1.2 seconds
 * and the journal could not say what was wrong with it.
 *
 * Messages travel only for failures whose text this repository writes and knows to be value-free:
 * a translation rejection is a rejection name and a JSON path. Everything else contributes its
 * class name alone, because a remote host, a certificate subject or a fragment of a provider's
 * configuration must not reach a journal the user can export.
 */
internal fun diagnosticFailureLabel(error: Throwable?): String =
    generateSequence(error) { cause -> cause.cause }
        .take(MAX_ERROR_CAUSE_DEPTH)
        .joinToString(separator = " <- ") { cause ->
            when (cause) {
                is FoxCoreConfigTranslationException ->
                    "${cause.javaClass.simpleName}(${cause.rejection.name.lowercase()} at ${cause.path})"

                else -> cause.javaClass.simpleName
            }
        }.ifEmpty { "unknown" }

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any(::contains)

private const val MAX_ERROR_CAUSE_DEPTH = 8
