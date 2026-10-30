package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ApkScanSettings
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.sentinelAttempt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal val HomeViewModel.sentinelScanState: StateFlow<SentinelScanControlState>
    get() = getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.controller.state
internal val HomeViewModel.sentinelScanAvailable: Boolean
    get() = getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.engineAvailable

internal fun HomeViewModel.refreshSentinelScanPolicy() {
    viewModelScope.launch {
        sentinelAttempt { getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.refreshPolicy() }
            .onFailure {
                emitError(
                    getApplication<Application>().getString(R.string.sentinel_static_status_unavailable)
                )
            }
    }
}
internal fun HomeViewModel.requestSentinelPackage(packageName: String) {
    viewModelScope.launch {
        sentinelAttempt {
            getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.requestPackage(
                packageName
            )
        }
            .onFailure {
                emitError(
                    getApplication<Application>().getString(R.string.sentinel_static_status_unavailable)
                )
            }
    }
}
internal fun HomeViewModel.requestSentinelBaseline() {
    viewModelScope.launch {
        sentinelAttempt { getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.requestBaseline() }
            .onFailure {
                emitError(
                    getApplication<Application>().getString(R.string.sentinel_static_status_unavailable)
                )
            }
    }
}
internal fun HomeViewModel.cancelSentinelScan() {
    viewModelScope.launch { getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.controller.cancel() }
}
internal fun HomeViewModel.updateSentinelScanSettings(transform: (ApkScanSettings) -> ApkScanSettings) {
    viewModelScope.launch {
        container.settingsRepository.update { current ->
            current.copy(
                anomaly = current.anomaly.copy(apkScan = transform(current.anomaly.apkScan))
            )
        }
        getApplication<FoxholeApplication>().appGraph.sentinelScanGateway.refreshPolicy()
    }
}
