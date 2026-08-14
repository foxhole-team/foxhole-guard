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

// The LAN proxy as a runtime object rather than a saved switch: what we ask the core for, what the
// core says came of it, and the one place that turns the two into the snapshot the UI renders.
//
// Everything here is deliberately fail-closed. A LAN proxy is a listener on the phone's Wi-Fi
// address that relays into the owner's VPN or Tor: raising one that "probably worked" is how a
// flat becomes an open relay. So a refusal is a refusal — never a Ready with an asterisk.

/** The Wi-Fi leg the listener is pinned to. Resolved once per arm, never guessed. */
data class LanNetworkBinding(
    val networkHandle: Long,
    val localAddress: String,
    val interfaceName: String,
    val transport: String,
)

/**
 * A request to publish the LAN proxy. Ports are per-protocol and `0` means "do not offer this one",
 * so BOTH is genuinely two listeners on two ports rather than one surface pretending to be two.
 */
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

/**
 * Maps the core's `LanProxyState` onto the phases the UI knows.
 *
 * The intermediate states (permission, resolve, acquire, bind) all collapse to ARMING on purpose:
 * they are a progress detail of one user-visible act — "the switch is on, nothing is serving yet" —
 * and splitting them across four labels only makes the screen flicker.
 */
private fun phaseForCoreState(state: String?): LanProxyPhase =
    when (state?.trim()?.lowercase()) {
        "ready" -> LanProxyPhase.READY
        "degraded" -> LanProxyPhase.DEGRADED
        "network_lost" -> LanProxyPhase.NETWORK_LOST
        "failed" -> LanProxyPhase.FAILED
        "checking_permission", "resolving_network", "acquiring_components", "binding_listeners" ->
            LanProxyPhase.ARMING
        // `stopping` is still a live listener winding down, but for the owner it is already gone.
        "stopped", "stopping" -> LanProxyPhase.OFF
        else -> LanProxyPhase.OFF
    }

/**
 * The core's refusal codes, typed. Anything the core does not have a code for lands on UNKNOWN
 * rather than being silently read as success.
 */
fun lanProxyReasonForCode(code: Int): LanProxyUnavailableReason =
    when (code) {
        LAN_PROXY_NOT_LINKED -> LanProxyUnavailableReason.CORE_UNSUPPORTED
        // A wildcard/cellular/unknown-interface bind, and a confirmation that no longer matches the
        // network, are the same thing to the user: this network is not one the proxy may go up on.
        FoxholeNativeEngine.LAN_NETWORK_REFUSED,
        FoxholeNativeEngine.LAN_NETWORK_UNCONFIRMED,
        FoxholeNativeEngine.LAN_CAPACITY,
        -> LanProxyUnavailableReason.NETWORK_REFUSED
        FoxholeNativeEngine.LAN_BIND_FAILED -> LanProxyUnavailableReason.BIND_FAILED
        // No engine, or an engine on its way down / without the lane the preset asked for: either
        // way there is no tunnel to share right now.
        FoxholeNativeEngine.LAN_NO_ENGINE,
        FoxholeNativeEngine.LAN_RUNTIME_UNAVAILABLE,
        -> LanProxyUnavailableReason.NO_SESSION
        else -> LanProxyUnavailableReason.UNKNOWN
    }

/**
 * Only a plain OK counts. Every other code is a refusal — including the ones that look benign —
 * because the whole point of this layer is that the screen never shows a proxy the core did not
 * actually bind.
 */
private fun lanProxyCodeIsAccepted(code: Int): Boolean = code == FoxholeNativeEngine.LAN_OK

/**
 * Parses the core's status document. A document that cannot be read is FAILED/UNKNOWN, never an
 * empty OFF: "I could not ask" and "nothing is running" are different facts and the second one is
 * the dangerous thing to invent.
 */
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

/**
 * The core reports its refusals as words, and the words are its own vocabulary — matching on
 * substrings keeps the Android side from having to be re-released whenever a message is reworded.
 */
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

/**
 * Owns the LAN proxy across a session: it applies the request the app computed, remembers what it
 * applied so an unchanged request does not re-bind the listeners on every settings write, and reads
 * the state back from the core instead of assuming its own call worked.
 *
 * Single-threaded by contract — every caller is on the VPN service's control path — but the applied
 * request is volatile so the status reader can run from anywhere.
 */
internal class LanProxyController(
    private val native: FoxCoreNativeApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var applied: LanProxyRequest? = null

    /** Last published snapshot, so a status read without a session still answers honestly. */
    @Volatile
    private var lastStatus: LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    fun status(): LanProxyStatusSnapshot = lastStatus

    /**
     * Brings the LAN proxy in line with [request]. A null request means "take it down"; a
     * null [handle] means there is no session to share and the switch can only be armed.
     */
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

    /** Session teardown: the listener cannot outlive the tunnel it relays into. */
    fun onSessionGone() {
        applied = null
        publish(LanProxyStatusSnapshot(updatedAt = clock()))
    }

    private fun readStatus(
        handle: Long,
        requested: LanProxyUpstream,
    ): LanProxyStatusSnapshot = parseLanProxyStatusJson(native.lanProxyStatus(handle), requested, clock())

    /**
     * A refused call. The result code is coarse by design — it is the shared component ABI — so when
     * it maps to nothing useful the core's own `last_error` sentence is consulted before settling for
     * "unknown": the screen has to be able to tell the user what to change.
     */
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
