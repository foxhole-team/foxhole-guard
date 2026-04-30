package com.foxhole.beta.core.model

enum class RuntimeFailureCode {
    ACTIVE_CONNECTION_REQUIRED,
    DNS_FAILURE,
    ENDPOINT_REFUSED,
    LATENCY_PROBE_FAILED,
    VALIDATION_TIMEOUT,
    VPN_NETWORK_MISSING,
    UNKNOWN,
}

class RuntimeFailureException(
    val code: RuntimeFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

fun Throwable.runtimeFailureCode(): RuntimeFailureCode =
    (this as? RuntimeFailureException)?.code ?: RuntimeFailureCode.UNKNOWN
