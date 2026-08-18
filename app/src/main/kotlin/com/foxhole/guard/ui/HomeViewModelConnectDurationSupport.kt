package com.foxhole.guard.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.core.settings.recordSmartProfileProbeResult
import kotlinx.coroutines.launch

private data class ConnectAttempt(
    val profileId: Long,
    val optionId: String?,
    val startedAtMs: Long,
)

internal fun HomeViewModel.startConnectDurationSupervision() {
    viewModelScope.launch {
        var attempt: ConnectAttempt? = null
        container.connectionController.snapshot.collect { snapshot ->
            when (snapshot.state) {
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
                    attempt = trackedConnectAttempt(attempt, snapshot)
                ConnectionState.CONNECTED -> {
                    val begun = attempt
                    attempt = null
                    recordFinishedConnectDuration(begun, snapshot)
                }
                else -> attempt = null
            }
        }
    }
}

private fun trackedConnectAttempt(
    current: ConnectAttempt?,
    snapshot: ConnectionSnapshot,
): ConnectAttempt? {
    val profileId = snapshot.profileId ?: return current
    if (profileId < 0 || snapshot.inPlaceRuntimeReload) return current
    if (current != null && current.profileId == profileId && current.optionId == snapshot.protocolOptionId) {
        return current
    }
    return ConnectAttempt(
        profileId = profileId,
        optionId = snapshot.protocolOptionId,
        startedAtMs = SystemClock.elapsedRealtime(),
    )
}

private suspend fun HomeViewModel.recordFinishedConnectDuration(
    attempt: ConnectAttempt?,
    snapshot: ConnectionSnapshot,
) {
    if (attempt == null || attempt.profileId != snapshot.profileId || snapshot.isSmartStartConnection) {
        return
    }
    val optionId = snapshot.protocolOptionId
        ?: attempt.optionId
        ?: container.profileRepository.getProfile(attempt.profileId)?.selectedProtocolOptionId
        ?: return
    container.settingsRepository.recordSmartProfileProbeResult(
        profileId = attempt.profileId,
        optionId = optionId,
        latencyMs = null,
        success = true,
        connectDurationMs = (SystemClock.elapsedRealtime() - attempt.startedAtMs).coerceAtLeast(1L),
        validatedAt = System.currentTimeMillis(),
        countTowardOutcomeHistory = false,
        affectsFailureRankingMemory = false,
    )
}
