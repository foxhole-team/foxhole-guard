package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ProfileSourceType
import java.net.URI
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal fun resolveImportProfilePlan(
    parsed: ParsedImport,
    localParsedProfiles: ParsedSubscriptionImport?,
): ImportProfilePlan =
    if (
        parsed.sourceType != ProfileSourceType.SUBSCRIPTION_URL &&
        localParsedProfiles != null &&
        localParsedProfiles.profiles.size > 1
    ) {
        ImportProfilePlan.Multi(localParsedProfiles)
    } else {
        ImportProfilePlan.Single(parsed)
    }

internal fun describeSubscriptionTransportFailure(
    sourceUrl: String,
    error: Throwable,
): String {
    val host = runCatching { URI(sourceUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
    val prefix = if (host != null) "Subscription update failed for $host" else "Subscription update failed"
    val errorMessages =
        generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" | ")
            .lowercase()
    return when {
        error.hasCause<SSLHandshakeException>() ||
            error.hasCause<SSLPeerUnverifiedException>() ||
            error.hasCause<CertPathValidatorException>() ||
            errorMessages.contains("trust anchor") ||
            errorMessages.contains("self signed") ->
            "$prefix: untrusted TLS certificate chain; self-signed subscription certificates are not supported"

        else -> error.message ?: error.javaClass.simpleName
    }
}

internal fun describeSubscriptionHttpFailure(
    code: Int,
    serverHeader: String?,
    responseBody: String?,
): String =
    when {
        code == 503 &&
            (
                serverHeader?.contains("ddos-guard", ignoreCase = true) == true ||
                    responseBody?.contains("DDoS-Guard", ignoreCase = true) == true
                ) -> "subscription provider blocked automated fetch with anti-bot challenge"

        else -> "refresh failed with http $code"
    }

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { cause -> cause is T }
