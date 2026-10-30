package com.foxhole.guard.core.data

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

internal class SubscriptionFetchConnectivityFailure(cause: IOException) :
    IOException("subscription fetch connectivity unavailable", cause)

// Untagged DNS failures can also represent public-address policy refusals.
internal fun subscriptionFetchConnectivityFailure(error: IOException): Throwable =
    if ((error is ConnectException || error is NoRouteToHostException) && !error.hasUnsafeConnectivityCause()) {
        SubscriptionFetchConnectivityFailure(error)
    } else {
        error
    }

private fun Throwable.hasUnsafeConnectivityCause(): Boolean {
    val pending = ArrayDeque<Throwable>()
    val visited = IdentityHashMap<Throwable, Boolean>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (visited.put(next, true) != null) continue
        if (visited.size > MAX_FAILURE_GRAPH_NODES) return true
        if (next.preventsConnectionCacheFallback()) return true
        next.cause?.let(pending::addLast)
        if (next.suppressed.size > MAX_FAILURE_GRAPH_NODES) return true
        next.suppressed.forEach(pending::addLast)
    }
    return false
}

private const val MAX_FAILURE_GRAPH_NODES = 16

private fun Throwable.preventsConnectionCacheFallback(): Boolean = when (this) {
    is SSLException, is CertificateException, is CancellationException, is Error,
    is InterruptedIOException, is UnknownHostException -> true
    else -> false
}
