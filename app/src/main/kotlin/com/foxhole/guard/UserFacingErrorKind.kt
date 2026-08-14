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
     * The native core is genuinely absent or could not be linked.
     *
     * The only question `error_runtime_missing` answers. It used to be the catch-all FALLBACK of
     * every connect, guard-start and reload sink, so any failure this file could not name — a
     * tunnel a Tor stop had not released yet, a wedged native start — told the user their build
     * has no core. A build either links the core or it does not, and only a link failure knows.
     */
    RUNTIME_LIBRARY_MISSING,

    /**
     * The bounded native start gave up and fenced the runtime.
     *
     * Classified before [SERVER_UNREACHABLE]: the marker is a timeout, but "the server did not
     * answer" is the wrong advice for a start that never reached the network.
     */
    RUNTIME_START_TIMEOUT,

    /** FoxHole Core loaded, but could not establish or replace the protected runtime route. */
    FOXCORE_RUNTIME_FAILURE,

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

    /**
     * «Whole device through TOR» over a VPN that only carries the selected apps.
     *
     * Refused before anything is built, so the sentence has to name the two settings that
     * disagree — the generic profile-invalid fallback sent the owner looking at a profile that was
     * never the problem.
     */
    TOR_ALL_APPS_NEEDS_FULL_TUNNEL,

    UNKNOWN,
}

/**
 * The failures that carry a locale-independent tag this repository writes itself.
 *
 * Kept ahead of — and out of — the fingerprint heuristics below: a marker is an exact answer, while
 * the heuristics are guesses over words that happen to appear in a message. `packet_tunnel_dns_missing`
 * had to be listed before the generic "dns" rule for exactly that reason, and every marker added
 * after it would have needed the same care.
 */
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

/**
 * Locale-independent tag a fenced native start puts on its failure, so the classification above can
 * recognise it. The user-facing sentence is picked here, not carried in an exception message: a
 * localized string travelling as a `message` is invisible to every classifier and used to reach the
 * screen only through a caller's fallback — which is exactly how a start timeout became "this build
 * has no native core".
 */
internal const val RUNTIME_START_TIMEOUT_MARKER = "foxcore_runtime_start_timeout"

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
                    "FoxCoreConfigTranslationException(" +
                        "${cause.rejection.name.lowercase()} at ${cause.path.asDiagnosticJsonPath()})"

                is FoxCoreRuntimeException ->
                    "FoxCoreRuntimeException(${cause.failure.code})"

                else -> cause.javaClass.simpleName
            }
        }.ifEmpty { "unknown" }

/**
 * JSON paths are fixed schema labels, not remote values. Dots nevertheless look like a hostname to
 * the persistence sanitizer (`$.route.final` became `$.[host]`), erasing the field that explains a
 * config refusal. Slash notation keeps the same structural path without resembling a hostname.
 */
private fun String.asDiagnosticJsonPath(): String = replace('.', '/')

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any(::contains)

private const val MAX_ERROR_CAUSE_DEPTH = 8
