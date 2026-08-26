package com.foxhole.core.runtime

import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LanProxyUpstream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The Wi-Fi leg the listener is pinned to. Resolved once per arm, never guessed. */
data class LanNetworkBinding(
    val networkHandle: Long,
    val localAddress: String,
    val interfaceName: String,
    val transport: String,
)

// Missing authentication or network binding refuses the LAN surface; partial readiness would be an open relay.
data class LanProxyRequest(
    val upstream: LanProxyUpstream,
    val socksPort: Int,
    val httpPort: Int,
    val username: String,
    val password: String,
    val binding: LanNetworkBinding,
) {
    val offersAnything: Boolean
        get() = socksPort > 0 || httpPort > 0

    fun toConfigJson(): String =
        buildJsonObject {
            put("preset", upstream.corePreset)
            put("socks_port", socksPort.coerceIn(0, MAX_PORT))
            put("http_port", httpPort.coerceIn(0, MAX_PORT))
            put("username", username)
            put("password", password)
            put("network_handle", binding.networkHandle)
            put("local_address", binding.localAddress)
            put("interface_name", binding.interfaceName)
            put("transport", binding.transport)
        }.toString()
}

private val LanProxyUpstream.corePreset: String
    get() =
        when (this) {
            LanProxyUpstream.VPN -> "vpn"
            LanProxyUpstream.TOR -> "tor"
            LanProxyUpstream.MIXED -> "mixed"
        }

private fun coreUpstream(value: String?): LanProxyUpstream? =
    when (value?.trim()?.lowercase()) {
        "vpn" -> LanProxyUpstream.VPN
        "tor" -> LanProxyUpstream.TOR
        "mixed" -> LanProxyUpstream.MIXED
        else -> null
    }

private fun phaseForCoreState(state: String?): LanProxyPhase =
    when (state?.trim()?.lowercase()) {
        "ready" -> LanProxyPhase.READY
        "degraded" -> LanProxyPhase.DEGRADED
        "network_lost" -> LanProxyPhase.NETWORK_LOST
        "failed" -> LanProxyPhase.FAILED
        "checking_permission", "resolving_network", "acquiring_components", "binding_listeners" ->
            LanProxyPhase.ARMING

        "stopped", "stopping" -> LanProxyPhase.OFF
        else -> LanProxyPhase.OFF
    }

fun lanProxyReasonForCode(code: Int): LanProxyUnavailableReason =
    when (code) {
        LAN_PROXY_NOT_LINKED -> LanProxyUnavailableReason.CORE_UNSUPPORTED

        FoxholeNativeEngine.LAN_NETWORK_REFUSED,
        FoxholeNativeEngine.LAN_NETWORK_UNCONFIRMED,
        FoxholeNativeEngine.LAN_CAPACITY,
        -> LanProxyUnavailableReason.NETWORK_REFUSED
        FoxholeNativeEngine.LAN_BIND_FAILED -> LanProxyUnavailableReason.BIND_FAILED

        FoxholeNativeEngine.LAN_NO_ENGINE,
        FoxholeNativeEngine.LAN_RUNTIME_UNAVAILABLE,
        -> LanProxyUnavailableReason.NO_SESSION
        else -> LanProxyUnavailableReason.UNKNOWN
    }

private fun lanProxyCodeIsAccepted(code: Int): Boolean = code == FoxholeNativeEngine.LAN_OK

fun parseLanProxyStatusJson(
    source: String,
    requested: LanProxyUpstream?,
    now: Long,
): LanProxyStatusSnapshot {
    if (source.isBlank()) {
        return LanProxyStatusSnapshot(
            phase = LanProxyPhase.FAILED,
            upstream = requested,
            reason = LanProxyUnavailableReason.UNKNOWN,
            updatedAt = now,
        )
    }
    val root =
        runCatching { Json.parseToJsonElement(source).jsonObject }.getOrNull()
            ?: return LanProxyStatusSnapshot(
                phase = LanProxyPhase.FAILED,
                upstream = requested,
                reason = LanProxyUnavailableReason.UNKNOWN,
                updatedAt = now,
            )
    val phase = phaseForCoreState(root.text("state"))
    val lastError = root.text("last_error")
    return LanProxyStatusSnapshot(
        phase = phase,
        socksAddress = root.text("socks_address"),
        httpAddress = root.text("http_address"),
        upstream = coreUpstream(root.text("preset")) ?: requested,
        reason =
        when (phase) {
            LanProxyPhase.READY -> null
            LanProxyPhase.NETWORK_LOST -> LanProxyUnavailableReason.NO_WIFI
            LanProxyPhase.OFF -> null
            else -> lastError?.let(::reasonForCoreError) ?: LanProxyUnavailableReason.UNKNOWN
        },
        updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull ?: now,
    )
}

private fun reasonForCoreError(message: String): LanProxyUnavailableReason {
    val lowered = message.lowercase()
    return when {
        lowered.contains("unconfirmed") || lowered.contains("refused") -> LanProxyUnavailableReason.NETWORK_REFUSED
        lowered.contains("bind") || lowered.contains("address in use") -> LanProxyUnavailableReason.BIND_FAILED
        lowered.contains("credential") || lowered.contains("auth") -> LanProxyUnavailableReason.NO_CREDENTIALS
        lowered.contains("permission") || lowered.contains("upstream") -> LanProxyUnavailableReason.NO_SESSION
        else -> LanProxyUnavailableReason.UNKNOWN
    }
}

private fun JsonObject.text(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)

internal class LanProxyController(
    private val native: FoxCoreNativeApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var applied: LanProxyRequest? = null

    @Volatile
    private var lastStatus: LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    fun status(): LanProxyStatusSnapshot = lastStatus

    fun sync(
        handle: Long?,
        request: LanProxyRequest?,
        blocked: LanProxyUnavailableReason? = null,
    ): LanProxyStatusSnapshot {
        if (request == null || !request.offersAnything || blocked != null) {
            val phase =
                when {
                    blocked == null -> LanProxyPhase.OFF
                    blocked == LanProxyUnavailableReason.CORE_UNSUPPORTED ||
                        blocked == LanProxyUnavailableReason.PACKET_TUNNEL -> LanProxyPhase.UNAVAILABLE
                    else -> LanProxyPhase.ARMING
                }
            return publish(stopIfApplied(handle).copy(phase = phase, reason = blocked))
        }
        if (handle == null) {
            applied = null
            return publish(
                LanProxyStatusSnapshot(
                    phase = LanProxyPhase.ARMING,
                    upstream = request.upstream,
                    reason = LanProxyUnavailableReason.NO_SESSION,
                    updatedAt = clock(),
                ),
            )
        }
        if (applied == request && lastStatus.phase == LanProxyPhase.READY) {
            return publish(readStatus(handle, request.upstream))
        }
        val confirmed =
            native.confirmLanNetwork(
                handle = handle,
                networkHandle = request.binding.networkHandle,
                localAddress = request.binding.localAddress,
                interfaceName = request.binding.interfaceName,
                transport = request.binding.transport,
            )
        if (!lanProxyCodeIsAccepted(confirmed)) {
            applied = null
            return publish(refusal(handle, request, confirmed))
        }
        val started = native.startLanProxy(handle, request.toConfigJson())
        if (!lanProxyCodeIsAccepted(started)) {
            applied = null
            return publish(refusal(handle, request, started))
        }
        applied = request
        return publish(readStatus(handle, request.upstream))
    }

    fun onSessionGone() {
        applied = null
        publish(LanProxyStatusSnapshot(updatedAt = clock()))
    }

    private fun readStatus(
        handle: Long,
        requested: LanProxyUpstream,
    ): LanProxyStatusSnapshot = parseLanProxyStatusJson(native.lanProxyStatus(handle), requested, clock())

    private fun refusal(
        handle: Long,
        request: LanProxyRequest,
        code: Int,
    ): LanProxyStatusSnapshot {
        val coded = lanProxyReasonForCode(code)
        val reason =
            if (coded == LanProxyUnavailableReason.UNKNOWN) {
                parseLanProxyStatusJson(native.lanProxyStatus(handle), request.upstream, clock()).reason ?: coded
            } else {
                coded
            }
        return LanProxyStatusSnapshot(
            phase =
            if (reason == LanProxyUnavailableReason.CORE_UNSUPPORTED) {
                LanProxyPhase.UNAVAILABLE
            } else {
                LanProxyPhase.FAILED
            },
            upstream = request.upstream,
            reason = reason,
            updatedAt = clock(),
        )
    }

    private fun stopIfApplied(handle: Long?): LanProxyStatusSnapshot {
        if (applied != null && handle != null) {
            native.stopLanProxy(handle)
        }
        applied = null
        return LanProxyStatusSnapshot(updatedAt = clock())
    }

    private fun publish(value: LanProxyStatusSnapshot): LanProxyStatusSnapshot {
        lastStatus = value
        FoxholeVpnRuntimeBridge.updateLanProxyStatus(value)
        return value
    }
}

private const val MAX_PORT = 65535
