package com.foxhole.core.runtime

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface RevokeTarget {
    data object All : RevokeTarget

    data class Lane(
        val lane: FlowLaneName,
    ) : RevokeTarget

    /** Every flow attributed to one Android uid. Shared-uid apps go together, which is correct. */
    data class Uid(
        val uid: Int,
    ) : RevokeTarget

    data class Package(
        val packageName: String,
    ) : RevokeTarget

    data class Outbound(
        val outboundId: String,
    ) : RevokeTarget

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

sealed interface RevokeOutcome {
    data class Revoked(
        val flows: Int,
    ) : RevokeOutcome

    data object NotRunning : RevokeOutcome

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
