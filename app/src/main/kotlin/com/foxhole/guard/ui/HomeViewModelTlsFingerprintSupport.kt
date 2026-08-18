package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.runtime.TlsFingerprintUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val HomeViewModel.tlsFingerprintUpdatePhase: StateFlow<FoxholeUpdatePhase>
    get() = componentUpdates.tlsFingerprintUpdatePhaseMutable.asStateFlow()

internal val HomeViewModel.tlsFingerprintDownloadProgress: StateFlow<RemoteDownloadProgress?>
    get() = componentUpdates.tlsFingerprintDownloadProgressMutable.asStateFlow()

internal fun HomeViewModel.tlsFingerprintInstalledGeneratedAt(): String? =
    container.tlsFingerprintUpdateRepository.installedGeneratedAt()

internal fun HomeViewModel.onTlsFingerprintManualRefresh() {
    if (componentUpdates.tlsFingerprintManualRefreshJob?.isActive == true) {
        return
    }
    componentUpdates.tlsFingerprintUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
    componentUpdates.tlsFingerprintDownloadProgressMutable.value = null
    componentUpdates.tlsFingerprintManualRefreshJob =
        viewModelScope.launch {
            val terminalPhase =
                try {
                    val result =
                        container.tlsFingerprintUpdateRepository.refreshNow(
                            onPhase = { phase ->
                                componentUpdates.tlsFingerprintUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase()
                            },
                            onProgress = { progress ->
                                componentUpdates.tlsFingerprintDownloadProgressMutable.value = progress
                            },
                        )
                    tlsFingerprintTerminalPhase(result.status)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    container.diagnosticsLogger.recordFailure(
                        "tls-fingerprints",
                        "manual fingerprint table refresh failed error=${error.javaClass.simpleName}",
                    )
                    FoxholeUpdatePhase.FAILED
                }
            if (terminalPhase == FoxholeUpdatePhase.FAILED) {
                componentUpdates.tlsFingerprintUpdatePhaseMutable.value = terminalPhase
            } else {
                settleUpdatePhase(
                    { phase -> componentUpdates.tlsFingerprintUpdatePhaseMutable.value = phase },
                    terminalPhase,
                )
            }
        }
}

internal fun tlsFingerprintTerminalPhase(status: TlsFingerprintUpdateStatus): FoxholeUpdatePhase =
    when (status) {
        TlsFingerprintUpdateStatus.UPDATED -> FoxholeUpdatePhase.DONE
        TlsFingerprintUpdateStatus.UP_TO_DATE -> FoxholeUpdatePhase.NO_UPDATE
        TlsFingerprintUpdateStatus.SKIPPED,
        TlsFingerprintUpdateStatus.FAILED,
        -> FoxholeUpdatePhase.FAILED
    }

internal fun tlsFingerprintDownloadFailed(status: TlsFingerprintUpdateStatus): Boolean =
    status != TlsFingerprintUpdateStatus.UPDATED && status != TlsFingerprintUpdateStatus.UP_TO_DATE

internal fun HomeViewModel.onTlsFingerprintManualRefreshCancel() {
    componentUpdates.tlsFingerprintManualRefreshJob?.cancel()
    componentUpdates.tlsFingerprintManualRefreshJob = null
    componentUpdates.tlsFingerprintUpdatePhaseMutable.value = FoxholeUpdatePhase.IDLE
    componentUpdates.tlsFingerprintDownloadProgressMutable.value = null
}
