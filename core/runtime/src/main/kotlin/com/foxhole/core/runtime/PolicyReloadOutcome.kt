package com.foxhole.core.runtime

/**
 * What one `nativeReloadPolicy` call actually said. The core returns eight distinct refusal
 * codes ("no Tor in this build" is permanent, "malformed policy" is an app bug, a revision
 * conflict is a retry); flattening them into one POLICY_RELOAD_FAILED reproduced D11 app-side.
 */
internal sealed interface PolicyReloadOutcome {
    /** Applied; the core installed this revision. */
    data class Applied(
        val revision: Long,
    ) : PolicyReloadOutcome

    /**
     * Refused with a code. [detail] is the core's own words (`nativeLastPolicyError`),
     * populated only for [RELOAD_INVALID] — the one code that cannot be acted on alone.
     */
    data class Refused(
        val code: Long,
        val detail: String,
    ) : PolicyReloadOutcome

    /** The JNI call itself failed or the handle is gone. */
    data object CallFailed : PolicyReloadOutcome
}

/**
 * Only the revision conflict is worth one retry without the guard: the app is the only policy
 * writer (a signed DNS rule-set install bumps the revision), so the user's request still stands.
 * Everything else refuses identically forever — a retry loop there is a battery drain that
 * never reports a problem.
 */
internal fun policyReloadIsRetryable(code: Long): Boolean =
    code.toInt() == FoxholeNativeEngine.RELOAD_REVISION_CONFLICT

/**
 * Maps a refusal code to the reported failure, keeping apart what matters to a user: no Tor in
 * this runtime (switch comes down) vs unparseable policy (app bug) vs conflict (just retry).
 */
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
        -> FoxCoreRuntimeFailure.POLICY_ROUTE_UNSUPPORTED
        else -> FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED
    }

/**
 * Read the core's own words only for `-1`: the other seven name their cause, while `-1` covers
 * both a truncated write and a removed schema field — different fixes. One extra JNI call,
 * taken only when it can tell us something.
 */
internal fun policyReloadNeedsDetail(code: Long): Boolean =
    code.toInt() == FoxholeNativeEngine.RELOAD_INVALID

/**
 * One reload attempt with the refusal read back rather than flattened. Lives here (not on the
 * runtime class) so codes, meaning, retryability and the producing call read as one piece.
 */
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
        // The core's prose is a message, not an identifier — reduce it to the same shape every
        // other reason in this log has.
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

/**
 * Check the native library is there and is the one this app was built against. All four failure
 * modes used to report NATIVE_UNAVAILABLE ("not included in this build") — true for exactly one
 * of them: an ABI mismatch (a library from a different core revision, the ordinary accident with
 * two repositories) read as a missing library. Pixel-diagnosed: eighteen straight start failures
 * said "not included" for a library that was in the APK.
 */
internal fun preflightNative(
    native: FoxCoreNativeApi,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    json: kotlinx.serialization.json.Json,
): Result<Unit> {
    // The only failure that truly is a missing/unloadable library: the very first call into it.
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

/** Three detail slots, because a vararg call with a spread copies the array. */
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
