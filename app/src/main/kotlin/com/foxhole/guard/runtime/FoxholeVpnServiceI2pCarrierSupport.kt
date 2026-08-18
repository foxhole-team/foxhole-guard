package com.foxhole.guard.runtime

import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.I2pAttachment
import com.foxhole.core.runtime.I2pCarrierState
import com.foxhole.core.runtime.I2pCarrierStep
import com.foxhole.core.runtime.I2pTunnelTransition
import com.foxhole.core.runtime.I2pdState
import com.foxhole.core.runtime.i2pCarrierPlan
import com.foxhole.core.runtime.userEngaged
import com.foxhole.guard.diagnosticFailureLabel
import kotlinx.coroutines.CancellationException

internal suspend fun FoxholeVpnService.applyI2pCarrierPlan(
    transition: I2pTunnelTransition,
    settings: Settings = container.settingsRepository.settings.value,
) {
    val steps = i2pCarrierPlan(
        settings = settings.i2p,
        state = I2pCarrierState(
            userEngaged = settings.i2p.userEngaged(),
            attachment = currentI2pAttachment(),
        ),
        transition = transition,
    )
    if (steps.isEmpty()) {
        return
    }
    steps.forEach { step -> applyI2pCarrierStep(step, settings) }
    container.diagnosticsLogger.recordStructured(
        "i2pd",
        "i2p carrier plan applied",
        "transition=$transition",
        "steps=${steps.joinToString(separator = "+")}",
    )
}

internal fun FoxholeVpnService.currentI2pAttachment(): I2pAttachment {
    if (container.i2pdManager.snapshot().state != I2pdState.RUNNING) {
        return I2pAttachment.NONE
    }
    val session = activeSession ?: return I2pAttachment.DIRECT
    return if (session.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID) {
        I2pAttachment.DIRECT
    } else {
        I2pAttachment.TUNNELLED
    }
}

private suspend fun FoxholeVpnService.applyI2pCarrierStep(
    step: I2pCarrierStep,
    settings: Settings,
) {
    when (step) {
        I2pCarrierStep.STOP -> container.i2pdManager.stop()
        I2pCarrierStep.START_DIRECT,
        I2pCarrierStep.START_TUNNELLED,
        -> startI2pCarrierOrRecord(settings)
    }
}

private suspend fun FoxholeVpnService.startI2pCarrierOrRecord(settings: Settings) {
    runCatching { container.i2pdManager.ensureStarted(settings.i2p) }
        .onFailure { error ->
            if (error is CancellationException) throw error
            container.diagnosticsLogger.recordFailure(
                "i2pd",
                "i2p carrier start failed: ${diagnosticFailureLabel(error)}",
            )
        }
}
