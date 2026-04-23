package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.TrustedSubscriptionCertificate
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal data class SubscriptionCertificateInfo(
    val host: String,
    val sha256Fingerprint: String,
    val subject: String,
    val issuer: String,
)

internal class SubscriptionTlsTrustRequiredException(
    val sourceUrl: String,
    val certificate: SubscriptionCertificateInfo,
) : IllegalStateException(
        "Subscription update failed for ${certificate.host}: untrusted TLS certificate chain",
    )

internal fun SubscriptionCertificateInfo.toTrustedCertificate(
    acceptedAt: Long = System.currentTimeMillis(),
): TrustedSubscriptionCertificate =
    TrustedSubscriptionCertificate(
        host = host,
        sha256Fingerprint = sha256Fingerprint,
        subject = subject,
        issuer = issuer,
        acceptedAt = acceptedAt,
    )

internal fun List<TrustedSubscriptionCertificate>.containsCertificate(
    host: String,
    sha256Fingerprint: String,
): Boolean =
    any { trusted ->
        trusted.host.equals(host, ignoreCase = true) &&
            trusted.sha256Fingerprint.equals(sha256Fingerprint, ignoreCase = true)
    }

internal fun X509Certificate.toSubscriptionCertificateInfo(host: String): SubscriptionCertificateInfo =
    SubscriptionCertificateInfo(
        host = host,
        sha256Fingerprint =
            MessageDigest.getInstance("SHA-256")
                .digest(encoded)
                .joinToString(":") { byte -> "%02X".format(byte) },
        subject = subjectX500Principal?.name.orEmpty(),
        issuer = issuerX500Principal?.name.orEmpty(),
    )

internal fun isTlsTrustFailure(error: Throwable): Boolean {
    val messages =
        generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" | ")
            .lowercase(Locale.US)
    return error.hasCause<SSLHandshakeException>() ||
        error.hasCause<SSLPeerUnverifiedException>() ||
        messages.contains("trust anchor") ||
        messages.contains("self signed")
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { cause -> cause is T }
