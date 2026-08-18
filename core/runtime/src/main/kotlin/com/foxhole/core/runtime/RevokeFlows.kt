package com.foxhole.core.runtime

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Which live flows to cut.
 *
 * A policy reload preserves flows that are already open — a routing change must not kill a
 * download. Blocking is the opposite promise: "block this app" has to mean the app is off the
 * network now, not when its sockets happen to close. The two are separate calls so the caller
 * says which one it means, and the core never has to guess from the shape of a policy diff.
 *
 * Typed rather than a JSON string at the call site: the core refuses unknown kinds, missing
 * fields *and* extra fields, so a hand-written `{"kind":"all","package":"..."}` — the shape a
 * mistyped kind produces — is a refusal, not a device-wide cut. Building it here means no call
 * site can write that.
 */
sealed interface RevokeTarget {
    /** Everything. The kill switch. */
    data object All : RevokeTarget

    /** One lane's worth of flows: `vpn`, `tor`, `i2p` or `direct`. */
    data class Lane(
        val lane: FlowLaneName,
    ) : RevokeTarget

    /** Every flow attributed to one Android uid. Shared-uid apps go together, which is correct. */
    data class Uid(
        val uid: Int,
    ) : RevokeTarget

    /** Every flow attributed to one package. */
    data class Package(
        val packageName: String,
    ) : RevokeTarget

    /** Every flow leaving through one named outbound. */
    data class Outbound(
        val outboundId: String,
    ) : RevokeTarget

    /** A single flow, by the `id` of a traffic-map row. */
    data class Flow(
        val flowId: Long,
    ) : RevokeTarget
}

/** The lane names the core accepts; spelled once so a typo cannot reach the JNI boundary. */
enum class FlowLaneName(
    val wireName: String,
) {
    VPN("vpn"),
    TOR("tor"),
    I2P("i2p"),
    DIRECT("direct"),
}

internal fun RevokeTarget.toTargetJson(): String =
    buildJsonObject {
        when (this@toTargetJson) {
            RevokeTarget.All -> put("kind", "all")
            is RevokeTarget.Lane -> {
                put("kind", "lane")
                put("lane", lane.wireName)
            }
            is RevokeTarget.Uid -> {
                put("kind", "uid")
                put("uid", uid)
            }
            is RevokeTarget.Package -> {
                put("kind", "package")
                put("package", packageName)
            }
            is RevokeTarget.Outbound -> {
                put("kind", "outbound")
                put("outbound", outboundId)
            }
            is RevokeTarget.Flow -> {
                put("kind", "flow")
                put("flow", flowId)
            }
        }
    }.toString()

/**
 * What the core made of a revocation request.
 *
 * [Revoked] with a count of zero is a success and the common case: the app was not talking, so the
 * state the caller asked for already holds. Only the negative codes are refusals, and they are
 * distinguished because "the core is not running" is a caller mistake worth a diagnostic, while
 * "the target did not parse" is a bug in this layer.
 */
sealed interface RevokeOutcome {
    data class Revoked(
        val flows: Int,
    ) : RevokeOutcome

    /** No engine to ask. Nothing is flowing through the core, so nothing needed cutting. */
    data object NotRunning : RevokeOutcome

    /** The core refused the target document, or the native library predates this call. */
    data class Refused(
        val code: Int,
    ) : RevokeOutcome
}

internal fun revokeOutcomeForCode(code: Int): RevokeOutcome =
    when {
        code >= 0 -> RevokeOutcome.Revoked(code)
        code == FoxholeNativeEngine.REVOKE_NOT_RUNNING -> RevokeOutcome.NotRunning
        else -> RevokeOutcome.Refused(code)
    }

enum class FailClosedEvent {
    TUNNEL_STARTING,

    TUNNEL_UP,

    TUNNEL_LOST,

    POLICY_RELOAD_REFUSED,

    RUNTIME_STOPPING,
}

fun killSwitchRevokeTarget(
    armed: Boolean,
    event: FailClosedEvent,
): RevokeTarget? {
    if (!armed) {
        return null
    }
    return when (event) {
        FailClosedEvent.TUNNEL_LOST, FailClosedEvent.RUNTIME_STOPPING -> RevokeTarget.All
        FailClosedEvent.TUNNEL_STARTING,
        FailClosedEvent.TUNNEL_UP,
        FailClosedEvent.POLICY_RELOAD_REFUSED,
        -> null
    }
}

fun FoxholeRuntime.applyKillSwitch(
    armed: Boolean,
    event: FailClosedEvent,
): RevokeOutcome? = killSwitchRevokeTarget(armed, event)?.let(::revokeFlows)
