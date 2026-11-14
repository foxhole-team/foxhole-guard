package com.foxhole.guard.router

import kotlinx.coroutines.flow.Flow

internal interface RouterSmartPhone {
    val changes: Flow<Unit>
    fun manualGeneration(): Long
    fun isIdle(): Boolean
    fun isCurrent(paused: RouterSmartPause): Boolean
    fun pause(profile: RouterConnectedProfile, local: RouterWifiConnection, verifiedAt: Long,
        authorityRevision: Long, pairingId: String): RouterSmartPause?
    suspend fun resume(paused: RouterSmartPause, authorityRevision: Long, stillAllowed: () -> Boolean): Boolean
    fun forget(paused: RouterSmartPause)
}
