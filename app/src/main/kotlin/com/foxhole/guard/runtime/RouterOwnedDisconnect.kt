package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandSource

// A queued home return may stop only the exact session that supplied its proof.
internal data class RouterOwnedDisconnect(
    val profileId: Long,
    val protocolOptionId: String?,
    val nativeGeneration: Long,
    val networkHandle: Long,
    val requestGeneration: Long,
    val wifiNetworkHandle: Long,
    val lanAddress: String,
    val lanPort: Int,
    val verifiedAtElapsedMs: Long,
    val authorityRevision: Long,
    val smartToken: String? = null,
) {
    fun matches(snapshot: ConnectionSnapshot, activeProfileId: Long?, activeOptionId: String?,
        currentNativeGeneration: Long?, currentNetworkHandle: Long?, currentRequestGeneration: Long,
        currentWifiNetworkHandle: Long?, nowElapsedMs: Long, currentAuthorityRevision: Long): Boolean =
        profileId > 0 && nativeGeneration > 0 && networkHandle > 0 && requestGeneration >= 0 &&
            snapshot.state == ConnectionState.CONNECTED && snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId == profileId && snapshot.protocolOptionId == protocolOptionId &&
            activeProfileId == profileId && activeOptionId == protocolOptionId &&
            currentNativeGeneration == nativeGeneration && currentNetworkHandle == networkHandle &&
            currentRequestGeneration == requestGeneration && wifiNetworkHandle > 0 &&
            currentWifiNetworkHandle == wifiNetworkHandle && verifiedAtElapsedMs >= 0 &&
            nowElapsedMs >= verifiedAtElapsedMs && nowElapsedMs - verifiedAtElapsedMs <= 5000L &&
            authorityRevision >= 0 && currentAuthorityRevision == authorityRevision

    override fun toString(): String = "RouterOwnedDisconnect"
}

// Normal priority is deliberate: this conditional operation must never preempt a new start.
internal fun routerOwnedDisconnectCommand(): RuntimeCommand =
    RuntimeCommand.Reload(reason = "router_home_owned_stop", source = RuntimeCommandSource.SYSTEM)
