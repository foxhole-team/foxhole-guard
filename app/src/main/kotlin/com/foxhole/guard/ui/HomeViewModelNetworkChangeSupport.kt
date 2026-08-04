package com.foxhole.guard.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.network.IpInfoFetchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
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
                    container.diagnosticsLogger.record(
                        "network",
                        "default network callback registration failed: ${error::class.simpleName}",
                    )
                    close()
                }
            awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
        }
            // A cellular<->Wi-Fi switch or an AP hop emits a burst of callbacks; coalesce so the
            // refresh runs once the new network has settled instead of firing several times.
            .debounce(DEFAULT_NETWORK_CHANGE_REFRESH_DEBOUNCE_MS)
            .collect {
                withContext(Dispatchers.IO) { container.ipInfoRepository.onDefaultNetworkChanged() }
                refreshDashboardAfterDefaultNetworkChange()
            }
    }
}

private fun HomeViewModel.refreshDashboardAfterDefaultNetworkChange() {
    if (!dashboardVisible) {
        return
    }
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
    if (state == ConnectionState.CONNECTED) {
        // The runtime's own upstream-network callback already handles a connected session; refresh
        // here too so the network card and map keep up if the underlying network changed under it.
        scheduleConnectedIpRefresh(
            reason = IpInfoRefreshReason.NETWORK_CHANGE,
            clearExistingIp = false,
        )
    } else {
        // Idle (no runtime): the shown IP is the device IP and the map origin — run a VISIBLE
        // skeleton refresh (same stable window as the manual swipe refresh) so a Wi-Fi hop or a
        // cellular<->Wi-Fi switch reads as an intentional update of the card and the map origin.
        startIpInfoRefresh(
            reportFailures = false,
            showLoading = true,
            clearExistingIp = false,
            fetchMode = IpInfoFetchMode.ENTRY_QUICK,
            minimumLoadingDurationMs = HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS,
            reason = IpInfoRefreshReason.NETWORK_CHANGE,
        )
    }
}

private const val DEFAULT_NETWORK_CHANGE_REFRESH_DEBOUNCE_MS = 800L
