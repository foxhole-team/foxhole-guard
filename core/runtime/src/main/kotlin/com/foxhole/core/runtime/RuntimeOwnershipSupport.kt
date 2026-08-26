package com.foxhole.core.runtime
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TrafficMode

fun NetworkCapabilities.isFoxholeVpnNetwork(context: Context): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ownerUid == context.applicationInfo.uid)

fun ConnectionSnapshot.isActiveRuntimeFor(mode: TrafficMode): Boolean =
    state in ACTIVE_CONNECTION_STATES && trafficMode == mode

fun ConnectionSnapshot.isActiveRuntimeForAnotherMode(mode: TrafficMode): Boolean =
    state in ACTIVE_CONNECTION_STATES && trafficMode != mode

fun ConnectionSnapshot.isActiveProfileRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        profileId != LOCAL_GUARD_PROFILE_ID

fun ConnectionSnapshot.isActiveVpnProfileRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES && (profileId ?: 0L) > 0L

fun shouldDeferLocalGuardStartForActiveProfileRuntime(
    snapshot: ConnectionSnapshot,
    activeProfileSessionPresent: Boolean,
    activeProfileVpnNetworkPresent: Boolean = true,
): Boolean =
    activeProfileSessionPresent ||
        (activeProfileVpnNetworkPresent && snapshot.isActiveVpnProfileRuntime())

fun shouldStopRuntimeAfterLocalGuardDisabled(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state != ConnectionState.IDLE &&
        snapshot.profileId == LOCAL_GUARD_PROFILE_ID

suspend fun resolveRuntimeConnectProfileId(
    requestedProfileId: Long,
    torOnlyProfileId: Long? = null,
    activeProfileIdProvider: suspend () -> Long?,
): Long? =
    when {
        requestedProfileId > 0L -> requestedProfileId
        torOnlyProfileId != null && requestedProfileId == torOnlyProfileId -> requestedProfileId
        else -> activeProfileIdProvider()?.takeIf { profileId -> profileId > 0L }
    }
