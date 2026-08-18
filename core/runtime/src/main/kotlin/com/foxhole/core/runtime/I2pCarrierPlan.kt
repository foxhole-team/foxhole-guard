package com.foxhole.core.runtime

import com.foxhole.core.model.I2pSettings

enum class I2pAttachment {
    NONE,
    DIRECT,
    TUNNELLED,
}

enum class I2pTunnelTransition {
    TUNNEL_STARTING,

    TUNNEL_UP,

    TUNNEL_STOPPED,

    RUNTIME_STOPPING,
}

data class I2pCarrierState(
    val userEngaged: Boolean,
    val attachment: I2pAttachment,
)

enum class I2pCarrierStep {
    STOP,
    START_DIRECT,
    START_TUNNELLED,
}

fun i2pCarrierPlan(
    settings: I2pSettings,
    state: I2pCarrierState,
    transition: I2pTunnelTransition,
): List<I2pCarrierStep> {
    // Teardown and an explicit user-off state always outrank carrier migration.
    if (transition == I2pTunnelTransition.RUNTIME_STOPPING) {
        return stopIfAttached(state)
    }
    if (!state.userEngaged) {
        return stopIfAttached(state)
    }
    return if (settings.autoReconnectAfterVpnDisconnect) {
        followingPlan(settings, state, transition)
    } else {
        stationaryPlan(state, transition)
    }
}

private fun followingPlan(
    settings: I2pSettings,
    state: I2pCarrierState,
    transition: I2pTunnelTransition,
): List<I2pCarrierStep> =
    when (transition) {
        I2pTunnelTransition.TUNNEL_STARTING -> stopIfAttached(state)
        I2pTunnelTransition.TUNNEL_UP ->
            when (state.attachment) {
                I2pAttachment.TUNNELLED -> emptyList()
                I2pAttachment.NONE -> listOf(I2pCarrierStep.START_TUNNELLED)
                I2pAttachment.DIRECT ->
                    // Never overlap direct and tunnelled router generations.
                    listOf(I2pCarrierStep.STOP, I2pCarrierStep.START_TUNNELLED)
            }
        I2pTunnelTransition.TUNNEL_STOPPED -> directPlan(settings, state)
        I2pTunnelTransition.RUNTIME_STOPPING -> stopIfAttached(state)
    }

private fun directPlan(
    settings: I2pSettings,
    state: I2pCarrierState,
): List<I2pCarrierStep> {
    if (!settings.allowOutsideTunnel) {
        return stopIfAttached(state)
    }
    return when (state.attachment) {
        I2pAttachment.DIRECT -> emptyList()
        I2pAttachment.NONE -> listOf(I2pCarrierStep.START_DIRECT)
        I2pAttachment.TUNNELLED -> listOf(I2pCarrierStep.STOP, I2pCarrierStep.START_DIRECT)
    }
}

private fun stationaryPlan(
    state: I2pCarrierState,
    transition: I2pTunnelTransition,
): List<I2pCarrierStep> =
    when (transition) {
        I2pTunnelTransition.TUNNEL_STARTING, I2pTunnelTransition.TUNNEL_UP -> stopIfAttached(state)
        I2pTunnelTransition.TUNNEL_STOPPED -> emptyList()
        I2pTunnelTransition.RUNTIME_STOPPING -> stopIfAttached(state)
    }

private fun stopIfAttached(state: I2pCarrierState): List<I2pCarrierStep> =
    if (state.attachment == I2pAttachment.NONE) emptyList() else listOf(I2pCarrierStep.STOP)

fun I2pSettings.userEngaged(): Boolean = enabled && engaged
