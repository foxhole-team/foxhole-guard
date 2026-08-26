package com.foxhole.core.runtime

import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException

fun <T> tunnelValidationRequestNetwork(candidate: T?): T? =
    candidate

fun isAppOwnedNetworkBindingDenied(error: Throwable?): Boolean =
    error
        ?.causeChain()
        ?.any { cause ->
            cause is SocketException &&
                cause.message.orEmpty().contains("Binding socket to network", ignoreCase = true) &&
                cause.message.orEmpty().contains("EPERM", ignoreCase = true)
        } == true

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
