package com.foxhole.beta.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.IpInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.markTorOperationInternal(kind: HomeTorOperationKind) {
    torOperationTimeoutJob?.cancel()
    val startedAt = System.currentTimeMillis()
    val startedIpAddress = container.connectionController.ipInfo.value?.let(::primaryVisibleIp)
    torOperationMutable.value =
        HomeTorOperationUiState(
            kind = kind,
            startedAt = startedAt,
            startedIpAddress = startedIpAddress,
        )
    torOperationTimeoutJob =
        viewModelScope.launch {
            delay(HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS)
            maybeFinishTorOperation(startedAt)
            delay(HomeViewModel.TOR_OPERATION_TIMEOUT_MS)
            clearTorOperation()
        }
}

internal suspend fun HomeViewModel.maybeFinishTorOperationInternal(
    torOperation: HomeTorOperationUiState,
    ipInfo: IpInfo?,
) {
    val publishableIpInfo = ipInfo?.takeIf(torOperation::canPublishTorIp) ?: return
    emitTorConnectedBanner(publishableIpInfo)
    clearTorOperation()
}

private suspend fun HomeViewModel.maybeFinishTorOperation(startedAt: Long) {
    val torOperation = torOperationMutable.value
    if (torOperation.startedAt != startedAt) {
        return
    }
    maybeFinishTorOperationInternal(torOperation, container.connectionController.ipInfo.value)
}

private fun HomeTorOperationUiState.canPublishTorIp(ipInfo: IpInfo): Boolean {
    val readyToPublish =
        active &&
            ipInfo.fetchedAt >= startedAt &&
            System.currentTimeMillis() - startedAt >= HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS
    val currentIpAddress = primaryVisibleIp(ipInfo)
    return readyToPublish && (startedIpAddress == null || currentIpAddress != startedIpAddress)
}

internal fun HomeViewModel.clearTorOperationInternal() {
    torOperationTimeoutJob?.cancel()
    torOperationTimeoutJob = null
    torOperationMutable.value = HomeTorOperationUiState()
}

internal suspend fun HomeViewModel.emitTorConnectedBannerInternal(ipInfo: IpInfo) {
    val country =
        ipInfo.countryName
            ?: ipInfo.countryCode
            ?: getApplication<Application>().getString(R.string.unknown_country)
    val city = ipInfo.city?.takeIf(String::isNotBlank) ?: getApplication<Application>().getString(R.string.unknown_city)
    emitSuccess(getApplication<Application>().getString(R.string.privacy_route_connected_banner, city, country))
}
