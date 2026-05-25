package com.foxhole.beta.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode

internal object FoxholeConnectionServiceContract {
    const val ACTION_CONNECT = "com.foxhole.beta.action.CONNECT"
    const val ACTION_DISCONNECT = "com.foxhole.beta.action.DISCONNECT"
    const val ACTION_KILL = "com.foxhole.beta.action.KILL"
    const val ACTION_KILL_TOR = "com.foxhole.beta.action.KILL_TOR"
    const val ACTION_RELOAD = "com.foxhole.beta.action.RELOAD"
    const val ACTION_RESTORE = "com.foxhole.beta.action.RESTORE"
    const val ACTION_START_LOCAL_GUARD = "com.foxhole.beta.action.START_LOCAL_GUARD"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_PROTOCOL_OPTION_ID = "protocol_option_id"
    const val EXTRA_PREVIOUS_VPN_NETWORK_HANDLE = "previous_vpn_network_handle"
    const val EXTRA_LOCAL_GUARD_MODE = "local_guard_mode"
    const val EXTRA_SUPPRESS_LOCAL_GUARD = "suppress_local_guard"
    const val EXTRA_PRESERVE_SMART_START_ANALYSIS = "preserve_smart_start_analysis"
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "foxhole-connection"

    private val activeStates =
        setOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        )

    fun serviceClass(mode: TrafficMode): Class<out Service> =
        when (mode) {
            TrafficMode.TUNNEL -> FoxholeVpnService::class.java
            TrafficMode.PROXY -> FoxholeProxyService::class.java
        }

    fun serviceMode(
        snapshot: ConnectionSnapshot,
        fallbackMode: TrafficMode,
    ): TrafficMode = if (snapshot.state in activeStates) snapshot.trafficMode else fallbackMode

    fun connectIntent(
        context: Context,
        mode: TrafficMode,
        profileId: Long,
        protocolOptionId: String? = null,
        previousVpnNetworkHandle: Long? = null,
    ): Intent =
        Intent(context, serviceClass(mode))
            .setAction(ACTION_CONNECT)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .apply {
                protocolOptionId?.let { putExtra(EXTRA_PROTOCOL_OPTION_ID, it) }
                previousVpnNetworkHandle?.let { putExtra(EXTRA_PREVIOUS_VPN_NETWORK_HANDLE, it) }
            }

    fun disconnectIntent(
        context: Context,
        mode: TrafficMode,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ): Intent =
        Intent(context, serviceClass(mode))
            .setAction(ACTION_DISCONNECT)
            .putExtra(EXTRA_SUPPRESS_LOCAL_GUARD, suppressLocalGuard)
            .putExtra(EXTRA_PRESERVE_SMART_START_ANALYSIS, preserveSmartStartAnalysis)

    fun reloadIntent(
        context: Context,
        mode: TrafficMode,
        profileId: Long? = null,
    ): Intent =
        Intent(context, serviceClass(mode))
            .setAction(ACTION_RELOAD)
            .apply {
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it) }
            }

    fun restoreIntent(
        context: Context,
        mode: TrafficMode,
    ): Intent = Intent(context, serviceClass(mode)).setAction(ACTION_RESTORE)

    fun localGuardIntent(
        context: Context,
        mode: LocalGuardMode,
    ): Intent =
        Intent(context, FoxholeVpnService::class.java)
            .setAction(ACTION_START_LOCAL_GUARD)
            .putExtra(EXTRA_LOCAL_GUARD_MODE, mode.name)

    fun serviceIntent(
        context: Context,
        mode: TrafficMode,
        action: String,
        profileId: Long? = null,
        protocolOptionId: String? = null,
        previousVpnNetworkHandle: Long? = null,
        localGuardMode: LocalGuardMode? = null,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ): Intent =
        when (action) {
            ACTION_CONNECT ->
                connectIntent(
                    context,
                    mode,
                    requireNotNull(profileId),
                    protocolOptionId,
                    previousVpnNetworkHandle,
                )
            ACTION_DISCONNECT -> disconnectIntent(context, mode, suppressLocalGuard, preserveSmartStartAnalysis)
            ACTION_KILL,
            ACTION_KILL_TOR,
            ->
                disconnectIntent(
                    context,
                    mode,
                    suppressLocalGuard = true,
                    preserveSmartStartAnalysis = false,
                ).setAction(action)
            ACTION_RELOAD -> reloadIntent(context, mode, profileId)
            ACTION_RESTORE -> restoreIntent(context, mode)
            ACTION_START_LOCAL_GUARD -> localGuardIntent(context, requireNotNull(localGuardMode))
            else -> error("unsupported action: $action")
        }

    fun startForegroundService(
        context: Context,
        mode: TrafficMode,
        action: String,
        profileId: Long? = null,
        protocolOptionId: String? = null,
        previousVpnNetworkHandle: Long? = null,
        localGuardMode: LocalGuardMode? = null,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ) {
        val intent =
            serviceIntent(
                context,
                mode,
                action,
                profileId,
                protocolOptionId,
                previousVpnNetworkHandle,
                localGuardMode,
                suppressLocalGuard,
                preserveSmartStartAnalysis,
            )
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopInactiveServices(
        context: Context,
        activeMode: TrafficMode,
    ) {
        TrafficMode.entries
            .filterNot { it == activeMode }
            .forEach { mode ->
                context.stopService(Intent(context, serviceClass(mode)))
            }
    }

    fun stopAllServices(context: Context) {
        TrafficMode.entries.forEach { mode ->
            ContextCompat.startForegroundService(
                context,
                Intent(context, serviceClass(mode)).setAction(ACTION_KILL),
            )
        }
    }
}

internal fun Intent.previousVpnNetworkHandleOrNull(): Long? =
    if (hasExtra(FoxholeConnectionServiceContract.EXTRA_PREVIOUS_VPN_NETWORK_HANDLE)) {
        getLongExtra(FoxholeConnectionServiceContract.EXTRA_PREVIOUS_VPN_NETWORK_HANDLE, 0L)
    } else {
        null
    }
