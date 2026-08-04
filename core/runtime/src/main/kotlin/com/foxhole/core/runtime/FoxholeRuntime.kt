package com.foxhole.core.runtime

import com.foxhole.core.model.VpnSession

/**
 * FOXHOLE RUNTIME — the Android control-plane seam for the Rust FoxCore engine.
 *
 * The service owns Android lifecycle and the master TUN descriptor. FoxCore owns all packet,
 * protocol, DNS, Tor and routing work behind this contract.
 */
interface FoxholeRuntime {
    suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun stop(policy: RuntimeStopPolicy = RuntimeStopPolicy()): RuntimeStopResult

    suspend fun forceKill(reason: String): RuntimeKillResult =
        RuntimeKillResult(
            reason = reason,
            tunClosed = true,
            serverDetached = false,
        )

    fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot.NONE

    fun currentDnsServerAddress(): String? = null

    fun runtimeStatsJson(): String? = null

    fun runtimeTrafficMapJson(): String? = null

    fun drainRuntimeAuditEventsJson(max: Int): String? = null

    fun onDefaultNetworkAvailable() {
    }

    fun onDefaultNetworkLost() {
    }
}
