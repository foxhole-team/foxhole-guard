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

/**
 * App-level default-network observer. The runtime services (VPN/proxy/firewall) register their own
 * network callbacks, but only while a runtime is active; when idle there is nothing watching the
 * network, so switching between cellular and Wi-Fi — or hopping between Wi-Fi access points — did
 * not refresh the dashboard network card or the traffic-map origin. This watches the process
 * default network directly and triggers a device-IP + map refresh on every change (new network,
 * lost network, or a link-properties/IP change on the same network), independent of the runtime.
 */
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

                    // Same network, changed IP/routes (e.g. roaming between Wi-Fi access points).
                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
                        trySend(Unit)
                    }
                }
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { error ->
                    // Registration can fail (e.g. TooManyRequestsException). Losing the idle
                    // refresh is cosmetic; never let the error escape and kill the ViewModel scope.
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
                // The device/upstream identity belongs to the physical network, so its country and
                // city become invalid at the edge, before the debounce. Keep only a connected VPN
                // exit whose route session has not changed; smart analysis keeps its explicit
                // dashboard pin. Repository epoch + coordinator token reject the old network's
                // late answer after this point.
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
            // A cellular<->Wi-Fi switch or an AP hop emits a burst of callbacks; coalesce so the
            // refresh runs once the new network has settled instead of firing several times.
            .debounce(DEFAULT_NETWORK_CHANGE_REFRESH_DEBOUNCE_MS)
            .collect {
                refreshDashboardAfterDefaultNetworkChange()
            }
    }
}

private fun HomeViewModel.refreshDashboardAfterDefaultNetworkChange() {
    // A refresh already in flight predates the change — its answer describes the OLD network, and
    // dropping the event here left that stale identity latched on the dashboard until a manual
    // swipe. Lower-priority refreshes are superseded by the coordinator below; only MANUAL and
    // TOR_ROUTE outrank NETWORK_CHANGE, so for those the event re-fires once they settle.
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
        // The runtime callback normally advances upstreamNetworkRevision first. This process-level
        // callback is the fallback for Android handovers where that edge is coalesced or arrives
        // before the service callback: the coordinator folds an already-running same-generation
        // refresh, so keeping both triggers is safe and never leaves the old Wi-Fi/mobile identity
        // latched indefinitely.
        scheduleConnectedIpRefresh(
            reason = IpInfoRefreshReason.NETWORK_CHANGE,
            clearExistingIp = false,
            showLoading = dashboardVisible,
            minimumLoadingDurationMs = if (dashboardVisible) HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS else 0L,
        )
    } else {
        // Idle (no runtime): the shown IP is the device IP and the map origin — run a VISIBLE
        // skeleton refresh (same stable window as the manual swipe refresh) so a Wi-Fi hop or a
        // cellular<->Wi-Fi switch reads as an intentional update of the card and the map origin.
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
