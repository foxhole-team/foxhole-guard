package com.foxhole.guard.runtime
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal sealed interface ForegroundRuntimeStartResult {
    data class Started(val intent: Intent) : ForegroundRuntimeStartResult

    data class Blocked(
        val action: String,
        val mode: TrafficMode,
        val reason: ForegroundServiceStartBlockReason,
    ) : ForegroundRuntimeStartResult
}

internal fun interface ForegroundRuntimeServiceStarter {
    fun startForegroundService(
        context: Context,
        intent: Intent,
    )
}

internal enum class ForegroundServiceStartBlockReason {
    FOREGROUND_SERVICE_START_NOT_ALLOWED,
    SECURITY,
    ILLEGAL_STATE,
}

internal object FoxholeConnectionServiceContract {
    const val ACTION_CONNECT = "com.foxhole.guard.action.CONNECT"
    const val ACTION_DISCONNECT = "com.foxhole.guard.action.DISCONNECT"
    const val ACTION_KILL = "com.foxhole.guard.action.KILL"
    const val ACTION_KILL_TOR = "com.foxhole.guard.action.KILL_TOR"
    const val ACTION_RELOAD = "com.foxhole.guard.action.RELOAD"
    const val ACTION_RESTORE = "com.foxhole.guard.action.RESTORE"
    const val ACTION_START_LOCAL_GUARD = "com.foxhole.guard.action.START_LOCAL_GUARD"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_PROTOCOL_OPTION_ID = "protocol_option_id"
    const val EXTRA_PREVIOUS_VPN_NETWORK_HANDLE = "previous_vpn_network_handle"
    const val EXTRA_LOCAL_GUARD_MODE = "local_guard_mode"
    const val EXTRA_SUPPRESS_LOCAL_GUARD = "suppress_local_guard"
    const val EXTRA_PRESERVE_SMART_START_ANALYSIS = "preserve_smart_start_analysis"
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "foxhole-connection"

    /**
     * There is exactly one network-runtime service. [TrafficMode.PROXY] is accepted only as a
     * persisted schema-18 compatibility token and is deliberately routed through VpnService so a
     * stale command cannot resurrect an unprotected proxy-only runtime.
     */
    fun serviceClass(@Suppress("UNUSED_PARAMETER") mode: TrafficMode): Class<out Service> =
        FoxholeVpnService::class.java

    fun serviceMode(
        @Suppress("UNUSED_PARAMETER") snapshot: ConnectionSnapshot,
        @Suppress("UNUSED_PARAMETER") fallbackMode: TrafficMode,
    ): TrafficMode = TrafficMode.TUNNEL

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
        starter: ForegroundRuntimeServiceStarter = DefaultForegroundRuntimeServiceStarter,
    ): ForegroundRuntimeStartResult {
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
        return startForegroundServiceSafely(
            context = context,
            mode = mode,
            action = action,
            intent = intent,
            starter = starter,
        )
    }

    fun stopInactiveServices(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") activeMode: TrafficMode,
    ) = Unit

    /**
     * A runtime service is CREATED by [markServiceCreated] and forgotten by [markServiceDestroyed],
     * so [stopAllServices] can tell "alive, needs a fail-closed kill" from "not running at all".
     */
    private val liveServiceModes: MutableSet<TrafficMode> = ConcurrentHashMap.newKeySet()

    internal fun markServiceCreated(mode: TrafficMode) {
        liveServiceModes += mode
    }

    internal fun markServiceDestroyed(mode: TrafficMode) {
        liveServiceModes -= mode
    }

    fun stopAllServices(context: Context) {
        listOf(TrafficMode.TUNNEL).forEach { mode ->
            if (liveServiceModes.isNotEmpty()) {
                // Alive: the KILL intent is how it tears its runtime down fail-closed.
                startForegroundService(context, mode, ACTION_KILL)
            } else {
                // NOT running: startForegroundService would CREATE it, and Android then demands a
                // startForeground() within its window — during a teardown the main thread is busy
                // enough to miss it, and the system kills the process with
                // ForegroundServiceDidNotStartInTimeException. Never resurrect a service just to
                // tell it to die; stopService is a no-op when it is already gone.
                context.stopService(Intent(context, serviceClass(mode)))
            }
        }
    }
}

internal object DefaultForegroundRuntimeServiceStarter : ForegroundRuntimeServiceStarter {
    override fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        ContextCompat.startForegroundService(context, intent)
    }
}

internal fun startForegroundServiceSafely(
    context: Context,
    mode: TrafficMode,
    action: String,
    intent: Intent,
    starter: ForegroundRuntimeServiceStarter = DefaultForegroundRuntimeServiceStarter,
): ForegroundRuntimeStartResult =
    runCatching {
        starter.startForegroundService(context, intent)
    }.fold(
        onSuccess = { ForegroundRuntimeStartResult.Started(intent) },
        onFailure = { error ->
            val reason = foregroundServiceStartBlockReason(error) ?: throw error
            context.foregroundRuntimeDiagnosticsLogger()?.record(
                "connection",
                foregroundStartBlockedDiagnosticMessage(
                    action = action,
                    mode = mode,
                    reason = reason,
                    error = error,
                ),
            )
            publishForegroundRuntimeStartBlockedSnapshot(
                mode = mode,
                message = context.getString(R.string.runtime_restore_open_app_required),
            )
            ForegroundRuntimeStartResult.Blocked(
                action = action,
                mode = mode,
                reason = reason,
            )
        },
    )

internal fun foregroundServiceStartBlockReason(error: Throwable): ForegroundServiceStartBlockReason? =
    when {
        error.javaClass.name == FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION ->
            ForegroundServiceStartBlockReason.FOREGROUND_SERVICE_START_NOT_ALLOWED
        error is SecurityException -> ForegroundServiceStartBlockReason.SECURITY
        error is IllegalStateException -> ForegroundServiceStartBlockReason.ILLEGAL_STATE
        else -> null
    }

internal fun foregroundStartBlockedDiagnosticMessage(
    action: String?,
    mode: TrafficMode,
    reason: ForegroundServiceStartBlockReason,
    error: Throwable,
): String =
    "foreground service start blocked " +
        "reason=${reason.logValue()} " +
        "action=${action ?: "unknown"} " +
        "mode=${mode.name.lowercase(Locale.ROOT)} " +
        "error=${sanitizedRuntimeFailureType(error)}"

internal fun ForegroundServiceStartBlockReason.logValue(): String = name.lowercase(Locale.ROOT)

internal fun sanitizedRuntimeFailureType(error: Throwable): String =
    error.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "RuntimeException"

private fun Context.foregroundRuntimeDiagnosticsLogger(): RuntimeDiagnosticsSink? =
    runCatching {
        (applicationContext as? FoxholeApplication)?.container?.diagnosticsLogger?.let(
            ::DiagnosticsLoggerRuntimeDiagnosticsSink
        )
    }.getOrNull()

private const val FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION =
    "android.app.ForegroundServiceStartNotAllowedException"

internal fun Intent.previousVpnNetworkHandleOrNull(): Long? =
    if (hasExtra(FoxholeConnectionServiceContract.EXTRA_PREVIOUS_VPN_NETWORK_HANDLE)) {
        getLongExtra(FoxholeConnectionServiceContract.EXTRA_PREVIOUS_VPN_NETWORK_HANDLE, 0L)
    } else {
        null
    }
