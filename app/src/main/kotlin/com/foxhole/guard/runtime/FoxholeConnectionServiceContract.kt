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
import java.util.concurrent.atomic.AtomicBoolean

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
    const val ACTION_ENFORCE_QUARANTINE = "com.foxhole.guard.action.ENFORCE_QUARANTINE"
    const val ACTION_RESTORE = "com.foxhole.guard.action.RESTORE"
    const val ACTION_START_LOCAL_GUARD = "com.foxhole.guard.action.START_LOCAL_GUARD"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_PROTOCOL_OPTION_ID = "protocol_option_id"
    const val EXTRA_PREVIOUS_VPN_NETWORK_HANDLE = "previous_vpn_network_handle"
    const val EXTRA_SUBSCRIPTION_REFRESH_PREPARED = "subscription_refresh_prepared"
    const val EXTRA_PROTOCOL_TEST_TRAFFIC_FREEZE = "protocol_test_traffic_freeze"
    const val EXTRA_REPLACE_ACTIVE_TUNNEL = "replace_active_tunnel"
    const val EXTRA_LOCAL_GUARD_MODE = "local_guard_mode"
    const val EXTRA_SUPPRESS_LOCAL_GUARD = "suppress_local_guard"
    const val EXTRA_PRESERVE_SMART_START_ANALYSIS = "preserve_smart_start_analysis"
    const val EXTRA_QUARANTINE_POLICY_REVISION = "quarantine_policy_revision"
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_CHANNEL_ID = "foxhole-connection"

    fun serviceClass(): Class<out Service> = FoxholeVpnService::class.java

    fun serviceMode(
        @Suppress("UNUSED_PARAMETER") snapshot: ConnectionSnapshot,
        @Suppress("UNUSED_PARAMETER") fallbackMode: TrafficMode,
    ): TrafficMode = TrafficMode.TUNNEL

    fun connectIntent(
        context: Context,
        profileId: Long,
        protocolOptionId: String? = null,
        previousVpnNetworkHandle: Long? = null,
        subscriptionRefreshPrepared: Boolean = false,
        protocolTestTrafficFreeze: Boolean = false,
        replaceActiveTunnel: Boolean = false,
    ): Intent =
        Intent(context, serviceClass())
            .setAction(ACTION_CONNECT)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .apply {
                protocolOptionId?.let { putExtra(EXTRA_PROTOCOL_OPTION_ID, it) }
                previousVpnNetworkHandle?.let { putExtra(EXTRA_PREVIOUS_VPN_NETWORK_HANDLE, it) }
                if (subscriptionRefreshPrepared) putExtra(EXTRA_SUBSCRIPTION_REFRESH_PREPARED, true)
                if (protocolTestTrafficFreeze) putExtra(EXTRA_PROTOCOL_TEST_TRAFFIC_FREEZE, true)
                if (replaceActiveTunnel) putExtra(EXTRA_REPLACE_ACTIVE_TUNNEL, true)
            }

    fun disconnectIntent(
        context: Context,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ): Intent =
        Intent(context, serviceClass())
            .setAction(ACTION_DISCONNECT)
            .putExtra(EXTRA_SUPPRESS_LOCAL_GUARD, suppressLocalGuard)
            .putExtra(EXTRA_PRESERVE_SMART_START_ANALYSIS, preserveSmartStartAnalysis)

    fun reloadIntent(
        context: Context,
        profileId: Long? = null,
    ): Intent =
        Intent(context, serviceClass())
            .setAction(ACTION_RELOAD)
            .apply {
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it) }
            }

    fun restoreIntent(context: Context): Intent =
        Intent(context, serviceClass()).setAction(ACTION_RESTORE)

    fun enforceQuarantineIntent(
        context: Context,
        revision: Long,
    ): Intent =
        Intent(context, serviceClass())
            .setAction(ACTION_ENFORCE_QUARANTINE)
            .putExtra(EXTRA_QUARANTINE_POLICY_REVISION, revision)

    fun localGuardIntent(
        context: Context,
        mode: LocalGuardMode,
    ): Intent =
        Intent(context, serviceClass())
            .setAction(ACTION_START_LOCAL_GUARD)
            .putExtra(EXTRA_LOCAL_GUARD_MODE, mode.name)

    fun serviceIntent(
        context: Context,
        action: String,
        profileId: Long? = null,
        protocolOptionId: String? = null,
        previousVpnNetworkHandle: Long? = null,
        localGuardMode: LocalGuardMode? = null,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
        subscriptionRefreshPrepared: Boolean = false,
        protocolTestTrafficFreeze: Boolean = false,
        replaceActiveTunnel: Boolean = false,
        quarantinePolicyRevision: Long = 0L,
    ): Intent =
        when (action) {
            ACTION_CONNECT ->
                connectIntent(
                    context,
                    requireNotNull(profileId),
                    protocolOptionId,
                    previousVpnNetworkHandle,
                    subscriptionRefreshPrepared,
                    protocolTestTrafficFreeze,
                    replaceActiveTunnel,
                )
            ACTION_DISCONNECT -> disconnectIntent(context, suppressLocalGuard, preserveSmartStartAnalysis)
            ACTION_KILL,
            ACTION_KILL_TOR,
            ->
                disconnectIntent(
                    context,
                    suppressLocalGuard = true,
                    preserveSmartStartAnalysis = false,
                ).setAction(action)
            ACTION_RELOAD -> reloadIntent(context, profileId)
            ACTION_ENFORCE_QUARANTINE -> enforceQuarantineIntent(context, quarantinePolicyRevision)
            ACTION_RESTORE -> restoreIntent(context)
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
        subscriptionRefreshPrepared: Boolean = false,
        protocolTestTrafficFreeze: Boolean = false,
        replaceActiveTunnel: Boolean = false,
        quarantinePolicyRevision: Long = 0L,
        starter: ForegroundRuntimeServiceStarter = DefaultForegroundRuntimeServiceStarter,
    ): ForegroundRuntimeStartResult {
        val intent =
            serviceIntent(
                context = context,
                action = action,
                profileId = profileId,
                protocolOptionId = protocolOptionId,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
                localGuardMode = localGuardMode,
                suppressLocalGuard = suppressLocalGuard,
                preserveSmartStartAnalysis = preserveSmartStartAnalysis,
                subscriptionRefreshPrepared = subscriptionRefreshPrepared,
                protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                replaceActiveTunnel = replaceActiveTunnel,
                quarantinePolicyRevision = quarantinePolicyRevision,
            )
        return startForegroundServiceSafely(
            context = context,
            mode = mode,
            action = action,
            intent = intent,
            starter = starter,
        )
    }

    private val serviceLive = AtomicBoolean(false)

    internal fun markServiceCreated() {
        serviceLive.set(true)
    }

    internal fun markServiceDestroyed() {
        serviceLive.set(false)
    }

    fun stopAllServices(context: Context) {
        if (serviceLive.get()) {
            // Alive: the KILL intent is how it tears its runtime down fail-closed.
            startForegroundService(context, TrafficMode.TUNNEL, ACTION_KILL)
        } else {
            context.stopService(Intent(context, serviceClass()))
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
