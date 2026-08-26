package com.foxhole.guard.ui

import com.foxhole.core.model.IpInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeIpRefreshState {
    val ipInfoLoadingMutable = MutableStateFlow(false)
    val ipInfoRefreshReasonMutable = MutableStateFlow<IpInfoRefreshReason?>(null)
    val torIpInfoMutable = MutableStateFlow<IpInfo?>(null)
    val publicDnsIdentityMutable = MutableStateFlow(PublicDnsIdentity())
    val coordinator = IpRefreshCoordinator()
    var ipInfoRefreshJob: Job? = null
    var ipInfoRefreshToken: Long = 0L
    var activeIpInfoRefreshReason: IpInfoRefreshReason? = null
    var pendingPostConnectIpRefresh: Boolean = false
    var connectedIpRefreshJob: Job? = null
    var postConnectTorRouteRefreshJob: Job? = null
    var foregroundRefreshJob: Job? = null
    var pendingNetworkChangeRefreshJob: Job? = null
    var publicDnsIdentityRefreshJob: Job? = null
    var publicDnsIdentityRefreshGeneration: Long = 0L
    var lastForegroundDashboardRefreshElapsedMs: Long = 0L
    var lastAppForegroundRefreshElapsedMs: Long = 0L
    var firstAppForegroundHandled: Boolean = false
}
