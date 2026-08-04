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

/**
 * Automatic Tor exit rotation: while a Tor route is CONNECTED and the setting
 * is on, requests a fresh circuit identity every configured interval — the same operation as the
 * Tor window's manual refresh (identity bump + runtime reload).
 *
 * Lives in the application scope, not the UI: rotation keeps ticking with the app in the
 * background for as long as the process (and with it the tunnel) is alive. Any input change
 * (setting toggled, interval changed, connection state moved) restarts the countdown.
 */
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
                        // A failed identity bump / reload must not kill the supervisor coroutine:
                        // that would silently stop all further auto-rotation until the process
                        // restarts. Log-and-continue keeps the next tick scheduled.
                        runCatching {
                            settingsRepository.rotatePrivacyRouteIdentity()
                            connectionController.reload(gate.profileId)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            diagnosticsLogger.record(
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
            snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || privacyRoute.enabled
        val eligible =
            privacyRoute.permitted &&
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
