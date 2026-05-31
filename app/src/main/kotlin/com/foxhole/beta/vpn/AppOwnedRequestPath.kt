package com.foxhole.beta.vpn

import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException

internal enum class AppOwnedRequestPath {
    NORMAL_PROCESS,
    EXPLICIT_NETWORK_BINDING,
}

internal fun appOwnedRequestPath(): AppOwnedRequestPath = AppOwnedRequestPath.NORMAL_PROCESS

internal fun <T> boundNetworkForAppOwnedRequest(candidate: T?): T? =
    when (appOwnedRequestPath()) {
        AppOwnedRequestPath.NORMAL_PROCESS -> null
        AppOwnedRequestPath.EXPLICIT_NETWORK_BINDING -> candidate
    }

internal fun <T> tunnelValidationRequestNetwork(candidate: T?): T? =
    candidate

internal fun shouldFallbackAppOwnedNetworkRequest(error: Throwable): Boolean =
    error.causeChain().any { cause ->
        when (cause) {
            is SocketException ->
                cause.message.orEmpty().contains("EPERM", ignoreCase = true) ||
                    cause.message.orEmpty().contains("Binding socket to network", ignoreCase = true)
            is UnknownHostException -> true
            is InterruptedIOException -> cause.message.orEmpty().contains("timeout", ignoreCase = true)
            else -> false
        }
    }

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause }
