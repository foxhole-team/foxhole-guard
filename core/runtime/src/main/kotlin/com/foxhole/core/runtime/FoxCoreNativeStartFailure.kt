package com.foxhole.core.runtime

internal fun invokeNativeStartWithDiagnostics(
    diagnosticsLogger: RuntimeDiagnosticsSink,
    start: () -> Long,
): Long? =
    runCatching(start)
        .onFailure { error ->
            val reason = nativeStartFailureReason(error)
            diagnosticsLogger.recordStructured("foxcore", "native start rejected", "reason=$reason")
            if (reason == FoxCoreRuntimeFailure.FLOW_ATTRIBUTION_UNAVAILABLE.code) {
                throw FoxCoreRuntimeException(FoxCoreRuntimeFailure.FLOW_ATTRIBUTION_UNAVAILABLE)
            }
        }.getOrNull()

private fun nativeStartFailureReason(error: Throwable): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "previous foxcore worker" in message -> "worker_lease_busy"
        message.containsAny("start named loopback inbound", "address already in use") -> "loopback_inbound_start_failed"
        "start runtime control proxy" in message -> "control_proxy_start_failed"
        message.containsAny("uid/package routing", "flow attribution") -> "flow_attribution_unavailable"
        "initialization timed out" in message -> "initialization_timeout"
        "initialize outbounds" in message -> "outbound_initialization_failed"
        "initialize dns policy" in message -> "dns_policy_initialization_failed"
        "open tun descriptor" in message -> "tun_open_failed"
        "engine config" in message -> "config_parse_failed"
        "panic inside foxcore" in message -> "native_panic"
        error is SecurityException -> "security_exception"
        error is IllegalArgumentException -> "invalid_argument"
        else -> "native_exception"
    }
}

private fun String.containsAny(vararg fragments: String): Boolean = fragments.any(::contains)
