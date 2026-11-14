package com.foxhole.guard.router

import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class GuardRouterSmartPhone(private val app: FoxholeApplication) : RouterSmartPhone {
    private val connection get() = app.appGraph.connectionController
    override val changes = connection.snapshot.map { Triple(it.state, it.profileId, it.protocolOptionId) }
        .distinctUntilChanged().map { Unit }
    override fun manualGeneration(): Long = connection.routerSmartManualGeneration()
    override fun isIdle(): Boolean = connection.routerSmartIdle()
    override fun isCurrent(paused: RouterSmartPause): Boolean = connection.routerSmartPauseCurrent(paused)
    override fun pause(profile: RouterConnectedProfile, local: RouterWifiConnection, verifiedAt: Long,
        authorityRevision: Long, pairingId: String): RouterSmartPause? =
        connection.pauseRouterSmartIfCurrent(profile, local, verifiedAt, authorityRevision, pairingId)
    override suspend fun resume(paused: RouterSmartPause, authorityRevision: Long, stillAllowed: () -> Boolean): Boolean =
        connection.resumeRouterSmart(paused, authorityRevision, stillAllowed)
    override fun forget(paused: RouterSmartPause) = connection.forgetRouterSmartPause(paused)
}
