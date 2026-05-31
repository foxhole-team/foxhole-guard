package com.foxhole.beta.vpn

import android.os.SystemClock
import com.foxhole.beta.core.model.AutoConnectReasonCode
import kotlinx.coroutines.CancellationException

internal fun runtimeValidationFailureReasonCode(
    error: Throwable?,
    dnsProbeFailedMessage: String,
): AutoConnectReasonCode =
    when {
        error.tunnelConnectivityProbeTimeout() != null -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        error.causeMessageContains("timed out") -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        error?.message == dnsProbeFailedMessage -> AutoConnectReasonCode.DNS_FAILURE
        else -> AutoConnectReasonCode.VALIDATION_TIMEOUT
    }

internal fun Throwable?.tunnelConnectivityProbeTimeout(): TunnelConnectivityProbeTimeoutException? {
    var cursor = this
    while (cursor != null) {
        if (cursor is TunnelConnectivityProbeTimeoutException) {
            return cursor
        }
        cursor = cursor.cause
    }
    return null
}

internal fun Throwable?.rootCauseClassName(): String? {
    var cursor = this ?: return null
    while (cursor.cause != null) {
        cursor = cursor.cause ?: break
    }
    return cursor.javaClass.simpleName
}

@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> FoxholeVpnService.profileRuntimeValidationStep(
    step: String,
    crossinline block: suspend () -> T,
): T {
    val startedAtMs = SystemClock.elapsedRealtime()
    return try {
        val result = block()
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "Runtime validation step",
            "step=$step",
            "step_result=success",
            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}",
        )
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "Runtime validation step",
            "step=$step",
            "step_result=failure",
            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}",
            "failure_class=${error.javaClass.simpleName}",
            error.rootCauseClassName()?.let { "failure_root=$it" },
        )
        throw error
    }
}

private fun Throwable?.causeMessageContains(fragment: String): Boolean {
    var cursor = this
    while (cursor != null) {
        if (cursor.message?.contains(fragment, ignoreCase = true) == true) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}
