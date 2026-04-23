package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.model.ProfileSourceType
import okhttp3.OkHttpClient
import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal sealed interface ImportProfilePlan {
    data class Single(
        val parsed: ParsedImport,
    ) : ImportProfilePlan

    data class Multi(
        val parsed: ParsedSubscriptionImport,
    ) : ImportProfilePlan
}

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
            "$prefix: untrusted TLS certificate chain"

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

internal fun insecureSubscriptionClient(baseClient: OkHttpClient): OkHttpClient {
    val trustAllManager =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    val sslContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    return baseClient.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { cause -> cause is T }
