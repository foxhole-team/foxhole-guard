package com.foxhole.core.runtime

internal sealed interface PolicyReloadOutcome {
    data class Applied(
        val revision: Long,
    ) : PolicyReloadOutcome

    data class Refused(
        val code: Long,
        val detail: String,
    ) : PolicyReloadOutcome

    /** The JNI call itself failed or the handle is gone. */
    data object CallFailed : PolicyReloadOutcome
}

internal fun policyReloadIsRetryable(code: Long): Boolean =
    code.toInt() == FoxholeNativeEngine.RELOAD_REVISION_CONFLICT

internal fun policyReloadFailure(code: Long): FoxCoreRuntimeFailure =
    when (code.toInt()) {
        0 -> FoxCoreRuntimeFailure.NOT_RUNNING
        FoxholeNativeEngine.RELOAD_TOR_UNAVAILABLE -> FoxCoreRuntimeFailure.POLICY_TOR_UNAVAILABLE
        FoxholeNativeEngine.RELOAD_I2P_UNAVAILABLE -> FoxCoreRuntimeFailure.POLICY_I2P_UNAVAILABLE
        FoxholeNativeEngine.RELOAD_UNKNOWN_OUTBOUND -> FoxCoreRuntimeFailure.POLICY_UNKNOWN_OUTBOUND
        FoxholeNativeEngine.RELOAD_REVISION_CONFLICT ->
            FoxCoreRuntimeFailure.POLICY_REVISION_CONFLICT
        FoxholeNativeEngine.RELOAD_OVERLAY_WITHOUT_FAKE_IP,
        FoxholeNativeEngine.RELOAD_NO_ATTRIBUTION,
        FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP,
        FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_PRIMARY_DNS,
        -> FoxCoreRuntimeFailure.POLICY_ROUTE_UNSUPPORTED
        else -> FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED
    }

// Only -1 is ambiguous enough to justify the extra JNI error-string call.
internal fun policyReloadNeedsDetail(code: Long): Boolean =
    code.toInt() == FoxholeNativeEngine.RELOAD_INVALID

internal fun attemptPolicyReload(
    native: FoxCoreNativeApi,
    handle: Long,
    policyJson: String,
    diagnosticsLogger: RuntimeDiagnosticsSink,
): PolicyReloadOutcome {
    val revision =
        runCatching { native.reloadPolicy(handle, policyJson) }
            .getOrElse {
                diagnosticsLogger.record("foxcore", "policy reload failed")
                return PolicyReloadOutcome.CallFailed
            }
    if (revision > 0L) {
        return PolicyReloadOutcome.Applied(revision)
    }
    val detail =
        if (policyReloadNeedsDetail(revision)) {
            runCatching { native.lastPolicyError(handle) }.getOrDefault("")
        } else {
            ""
        }
    val reason = "reason=${FoxholeNativeEngine.reloadCode(revision)}"
    if (detail.isBlank()) {
        diagnosticsLogger.recordStructured("foxcore", "policy reload rejected", reason)
    } else {
        diagnosticsLogger.recordStructured(
            "foxcore",
            "policy reload rejected",
            reason,
            "detail=${safePolicyDetail(detail)}",
        )
    }
    return PolicyReloadOutcome.Refused(code = revision, detail = detail)
}

private fun safePolicyDetail(detail: String): String =
    detail
        .lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .take(MAX_POLICY_DETAIL_LENGTH)
        .ifBlank { "unspecified" }

private const val MAX_POLICY_DETAIL_LENGTH = 120

internal fun preflightNative(
    native: FoxCoreNativeApi,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    json: kotlinx.serialization.json.Json,
): Result<Unit> {
    val abi =
        try {
            native.abiVersion()
        } catch (error: Throwable) {
            diagnosticsLogger.recordStructured(
                "foxcore",
                "native preflight failed",
                "check=load",
                "error=${error.javaClass.simpleName}",
            )
            return foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_UNAVAILABLE)
        }
    val mismatch = preflightMismatch(native, json, abi)
    if (mismatch != null) {
        diagnosticsLogger.recordStructured(
            "foxcore",
            "native preflight failed",
            mismatch.first,
            mismatch.second,
            mismatch.third,
        )
        return foxCoreFailure(mismatch.failure)
    }
    return Result.success(Unit)
}

private class PreflightMismatch(
    val failure: FoxCoreRuntimeFailure,
    val first: String,
    val second: String? = null,
    val third: String? = null,
)

private fun preflightMismatch(
    native: FoxCoreNativeApi,
    json: kotlinx.serialization.json.Json,
    abi: Int,
): PreflightMismatch? {
    if (abi != FoxholeNativeEngine.ABI_VERSION) {
        return PreflightMismatch(
            FoxCoreRuntimeFailure.ABI_MISMATCH,
            "check=abi_version",
            "native=$abi",
            "expected=${FoxholeNativeEngine.ABI_VERSION}",
        )
    }
    val capabilitiesAbi =
        runCatching {
            json
                .parseToJsonElement(native.capabilities())
                .let { it as kotlinx.serialization.json.JsonObject }["abi_version"]
                ?.toString()
                ?.toIntOrNull()
        }.getOrNull()
    if (capabilitiesAbi != abi) {
        return PreflightMismatch(
            FoxCoreRuntimeFailure.ABI_MISMATCH,
            "check=capabilities",
            "capabilities_abi=${capabilitiesAbi ?: "unreadable"}",
            "expected=$abi",
        )
    }
    if (runCatching { native.version() }.getOrDefault("").isBlank()) {
        return PreflightMismatch(
            FoxCoreRuntimeFailure.NATIVE_UNAVAILABLE,
            "check=version",
        )
    }
    return null
}
