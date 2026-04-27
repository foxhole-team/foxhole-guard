package com.foxhole.beta.vpn

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
