package com.foxhole.guard.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeDefaultNetworkChangesInternal() {
    val connectivityManager =
        getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
    viewModelScope.launch {
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(Unit)
                    }

                    override fun onLost(network: Network) {
                        trySend(Unit)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
                        trySend(Unit)
                    }
                }
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { error ->
                    container.diagnosticsLogger.recordFailure(
                        "network",
                        "default network callback registration failed: ${error::class.simpleName}",
                    )
                    close()
                }
            awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
        }
            .onEach {
                container.ipInfoRepository.markDefaultNetworkChanged()
                invalidateIpInfoRefreshes()
                val snapshot = container.connectionController.snapshot.value
                clearNetworkHandoverIpIdentity(
                    clearDashboardIdentity = shouldClearDashboardIdentityOnUnderlyingHandover(
                        snapshot = snapshot,
                        smartAnalysisRunning = autoConnectUiStateMutable.value.running,
                    ),
                )
                if (snapshot.torActive || snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                    torIdentityProbeTimeoutJob?.cancel()
                    torIdentityProbeMutable.restart()
                }
                torIpInfoMutable.value = null
                withContext(Dispatchers.IO) { container.ipInfoRepository.evictStaleConnections() }
            }
            .debounce(DEFAULT_NETWORK_CHANGE_REFRESH_DEBOUNCE_MS)
            .collect {
                refreshDashboardAfterDefaultNetworkChange()
            }
    }
}

private fun HomeViewModel.refreshDashboardAfterDefaultNetworkChange() {
    val activeJob = ipInfoRefreshJob?.takeUnless { job -> job.isCompleted }
    val activeReason = activeIpInfoRefreshReason
    val outranksNetworkChange =
        activeReason == IpInfoRefreshReason.MANUAL || activeReason == IpInfoRefreshReason.TOR_ROUTE
    if (activeJob != null && outranksNetworkChange) {
        pendingNetworkChangeRefreshJob?.cancel()
        pendingNetworkChangeRefreshJob =
            viewModelScope.launch {
                activeJob.join()
                pendingNetworkChangeRefreshJob = null
                refreshDashboardAfterDefaultNetworkChange()
            }
        return
    }
    val state = container.connectionController.snapshot.value.state
    if (shouldUseConnectedIpRefreshAfterDefaultNetworkChange(state)) {
        scheduleConnectedIpRefresh(
            reason = IpInfoRefreshReason.NETWORK_CHANGE,
            clearExistingIp = false,
            showLoading = dashboardVisible,
            minimumLoadingDurationMs = if (dashboardVisible) HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS else 0L,
        )
    } else {
        startIpInfoRefresh(
            reportFailures = false,
            showLoading = dashboardVisible,
            clearExistingIp = true,
            fetchMode = IpInfoFetchMode.ENTRY_QUICK,
            minimumLoadingDurationMs = HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS,
            reason = IpInfoRefreshReason.NETWORK_CHANGE,
        )
    }
}

internal fun shouldUseConnectedIpRefreshAfterDefaultNetworkChange(state: ConnectionState): Boolean =
    state == ConnectionState.CONNECTED

internal fun shouldClearDashboardIdentityOnUnderlyingHandover(
    snapshot: ConnectionSnapshot,
    smartAnalysisRunning: Boolean,
): Boolean {
    if (smartAnalysisRunning) return false
    val stableVpnRoute =
        snapshot.state == ConnectionState.CONNECTED &&
            snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId != null &&
            snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    return !stableVpnRoute
}

private const val DEFAULT_NETWORK_CHANGE_REFRESH_DEBOUNCE_MS = 800L
