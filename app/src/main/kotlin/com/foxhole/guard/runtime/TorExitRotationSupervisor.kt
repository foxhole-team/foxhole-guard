package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.rotatePrivacyRouteIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TorExitRotationSupervisor(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val connectionController: FoxholeConnectionController,
    private val diagnosticsLogger: DiagnosticsLogger,
) {
    fun start() {
        scope.launch {
            combine(connectionController.snapshot, settingsRepository.settings) { snapshot, settings ->
                rotationGate(snapshot, settings.privacyRoute)
            }
                .distinctUntilChanged()
                .collectLatest { gate ->
                    if (gate == null) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        delay(gate.intervalMinutes * MILLIS_PER_MINUTE)

                        runCatching {
                            settingsRepository.rotatePrivacyRouteIdentity()
                            connectionController.reload(gate.profileId)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            diagnosticsLogger.recordFailure(
                                "tor",
                                "auto exit rotation failed; next tick stays scheduled: " +
                                    (error.message ?: error.javaClass.simpleName),
                            )
                        }
                    }
                }
        }
    }

    private data class RotationGate(
        val profileId: Long?,
        val intervalMinutes: Long,
    )

    private fun rotationGate(
        snapshot: ConnectionSnapshot,
        privacyRoute: PrivacyRouteSettings,
    ): RotationGate? {
        val torRouteActive =
            snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || snapshot.torActive
        val eligible =
            privacyRoute.permitted &&
                privacyRoute.enabled &&
                privacyRoute.autoRotateExit &&
                torRouteActive &&
                snapshot.state == ConnectionState.CONNECTED
        if (!eligible) {
            return null
        }
        return RotationGate(
            profileId = snapshot.profileId,
            intervalMinutes = privacyRoute.autoRotateIntervalMinutes.toLong().coerceAtLeast(1L),
        )
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
