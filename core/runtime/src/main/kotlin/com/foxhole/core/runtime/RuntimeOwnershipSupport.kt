package com.foxhole.core.runtime
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TrafficMode

// Consolidated runtime ownership/state helpers and the connect-profile resolver.
// Previously split across VpnNetworkOwnership / RuntimeControlPlaneOwnership /
// RuntimeServiceOwnership / RuntimeConnectProfileResolver. Behaviour is unchanged.

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

// A real upstream VPN/proxy profile (positive id) that is currently serving. Only such a tunnel
// already enforces the guard's blocking/DNS duties in place, so enabling the firewall while it runs
// reloads rules into it instead of starting a separate guard. Tor-only (id = -20) and local guard
// (id = -10) are NOT profile tunnels: enabling the firewall must switch modes and stop them.
fun ConnectionSnapshot.isActiveVpnProfileRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES && (profileId ?: 0L) > 0L

fun shouldDeferLocalGuardStartForActiveProfileRuntime(
    snapshot: ConnectionSnapshot,
    activeProfileSessionPresent: Boolean,
): Boolean =
    activeProfileSessionPresent || snapshot.isActiveVpnProfileRuntime()

// Any non-idle local-guard snapshot must be torn down when the guard is disabled. Requiring the
// exact ACTIVE+TUNNEL combination skipped the stop for a guard sitting in ERROR (or mid mode
// switch), leaving the runtime/notification alive until something else reconciled it — the
// user-visible "stop firewall doesn't actually stop it".
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
