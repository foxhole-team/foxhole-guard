package com.foxhole.beta.core.data

import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

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
