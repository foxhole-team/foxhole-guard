package com.foxhole.guard.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandSource
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.FoxholeRuntimeDependencies
import com.foxhole.guard.R

internal typealias RuntimeCommandDispatcher =
    (RuntimeCommand, suspend (RuntimeCommand) -> Unit) -> Unit

internal typealias RuntimeConnectHandler = suspend (
    profileId: Long,
    commandStartId: Int,
    protocolOptionId: String?,
    previousVpnNetworkHandle: Long?,
    subscriptionRefreshPrepared: Boolean,
    protocolTestTrafficFreeze: Boolean,
    replaceActiveTunnel: Boolean,
) -> Unit

internal typealias RuntimeDisconnectHandler = suspend (
    commandStartId: Int?,
    suppressLocalGuard: Boolean,
    preserveSmartStartAnalysis: Boolean,
) -> Unit

internal data class RuntimeServiceCommandHandlers(
    val dispatch: RuntimeCommandDispatcher,
    val connect: RuntimeConnectHandler,
    val disconnect: RuntimeDisconnectHandler,
    val reload: suspend (profileIdHint: Long) -> Unit,
    val enforceQuarantine: suspend (requestedRevision: Long) -> Unit,
    val startLocalGuard: suspend (LocalGuardMode, Int) -> Unit,
    val activeProfileSessionPresent: () -> Boolean,
    val failClosedTeardown: suspend (commandStartId: Int, action: String?) -> Unit,
)

internal fun Service.handleForegroundRuntimeCommand(
    intent: Intent?,
    startId: Int,
    trafficMode: TrafficMode,
    notificationManager: NotificationManager,
    currentNotificationSnapshot: () -> NotificationSnapshot,
    buildNotification: (NotificationSnapshot) -> Notification,
    container: FoxholeRuntimeDependencies,
    handlers: RuntimeServiceCommandHandlers,
): Int {
    val action = intent?.action
    ensureConnectionNotificationChannel(notificationManager)
    val notification = buildNotification(currentNotificationSnapshot())
    if (!startForegroundRuntimeSafely(action, startId, trafficMode, notification, container)) {
        return Service.START_NOT_STICKY
    }
    when {
        action == null -> {
            val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
            if (snapshot.state in NULL_INTENT_ACTIVE_STATES) {
                container.diagnosticsLogger.record(
                    "connection",
                    "runtime service null intent ignored while runtime active",
                )
            } else {
                handlers.dispatch(
                    RuntimeCommand.Stop(
                        reason = "null_intent_reconcile",
                        source = RuntimeCommandSource.SYSTEM,
                    ),
                ) {
                    handlers.disconnect(
                        startId,
                        true,
                        false,
                    )
                }
            }
        }
        isFailClosedRuntimeServiceCommand(action) -> {
            handlers.dispatch(
                RuntimeCommand.Kill(
                    reason = "fail_closed:$action",
                    source = RuntimeCommandSource.SYSTEM,
                ),
            ) { handlers.failClosedTeardown(startId, action) }
        }
        else -> {
            handleRuntimeServiceCommand(
                intent = intent,
                startId = startId,
                trafficMode = trafficMode,
                container = container,
                handlers = handlers,
            )
        }
    }
    return Service.START_NOT_STICKY
}

private fun Service.startForegroundRuntimeSafely(
    action: String?,
    startId: Int,
    trafficMode: TrafficMode,
    notification: Notification,
    container: FoxholeRuntimeDependencies,
): Boolean =
    runCatching {
        startForeground(FoxholeConnectionServiceContract.NOTIFICATION_ID, notification)
    }.fold(
        onSuccess = { true },
        onFailure = { error ->
            val reason = foregroundServiceStartBlockReason(error) ?: throw error
            container.diagnosticsLogger.record(
                "connection",
                foregroundStartBlockedDiagnosticMessage(
                    action = action,
                    mode = trafficMode,
                    reason = reason,
                    error = error,
                ),
            )
            publishForegroundRuntimeStartBlockedSnapshot(
                mode = trafficMode,
                message = getString(R.string.runtime_restore_open_app_required),
            )
            stopSelf(startId)
            false
        },
    )

internal fun publishForegroundRuntimeStartBlockedSnapshot(
    mode: TrafficMode,
    message: String,
) {
    FoxholeVpnRuntimeBridge.update(
        ConnectionSnapshot(
            state = ConnectionState.ERROR,
            trafficMode = mode,
            message = message,
        ),
    )
}

internal fun handleRuntimeServiceCommand(
    intent: Intent?,
    startId: Int,
    trafficMode: TrafficMode,
    container: FoxholeRuntimeDependencies,
    handlers: RuntimeServiceCommandHandlers,
) {
    val commandIntent = intent ?: return
    when (commandIntent.action) {
        FoxholeConnectionServiceContract.ACTION_CONNECT ->
            dispatchConnectCommand(commandIntent, startId, trafficMode, handlers)
        FoxholeConnectionServiceContract.ACTION_DISCONNECT ->
            dispatchDisconnectCommand(commandIntent, startId, trafficMode, handlers)
        FoxholeConnectionServiceContract.ACTION_KILL,
        FoxholeConnectionServiceContract.ACTION_KILL_TOR,
        -> dispatchKillCommand(commandIntent, startId, handlers)
        FoxholeConnectionServiceContract.ACTION_RELOAD ->
            dispatchReloadCommand(commandIntent, trafficMode, handlers)
        FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE ->
            dispatchQuarantineCommand(commandIntent, trafficMode, handlers)
        FoxholeConnectionServiceContract.ACTION_RESTORE ->
            dispatchRestoreCommand(commandIntent, startId, trafficMode, container, handlers)
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD ->
            dispatchLocalGuardCommand(commandIntent, startId, trafficMode, container, handlers)
    }
}

private fun dispatchConnectCommand(
    intent: Intent,
    startId: Int,
    trafficMode: TrafficMode,
    handlers: RuntimeServiceCommandHandlers,
) {
    val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
    val protocolOptionId = intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_PROTOCOL_OPTION_ID)
    val previousVpnNetworkHandle = intent.previousVpnNetworkHandleOrNull()
    val subscriptionRefreshPrepared =
        intent.getBooleanExtra(
            FoxholeConnectionServiceContract.EXTRA_SUBSCRIPTION_REFRESH_PREPARED,
            false,
        )
    val protocolTestTrafficFreeze =
        intent.getBooleanExtra(FoxholeConnectionServiceContract.EXTRA_PROTOCOL_TEST_TRAFFIC_FREEZE, false)
    val replaceActiveTunnel =
        intent.getBooleanExtra(FoxholeConnectionServiceContract.EXTRA_REPLACE_ACTIVE_TUNNEL, false)
    val command =
        runtimeCommandForServiceAction(
            action = intent.action,
            trafficMode = trafficMode,
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            subscriptionRefreshPrepared = subscriptionRefreshPrepared,
            protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            replaceActiveTunnel = replaceActiveTunnel,
        ) ?: return
    handlers.dispatch(command) { runtimeCommand ->
        when (runtimeCommand) {
            is RuntimeCommand.StartTunnel ->
                handlers.connect(
                    runtimeCommand.profileId,
                    startId,
                    runtimeCommand.optionId,
                    runtimeCommand.previousVpnNetworkHandle,
                    runtimeCommand.subscriptionRefreshPrepared,
                    runtimeCommand.protocolTestTrafficFreeze,
                    runtimeCommand.replaceActiveTunnel,
                )
            is RuntimeCommand.StartProxy ->
                handlers.connect(
                    runtimeCommand.profileId,
                    startId,
                    runtimeCommand.optionId,
                    null,
                    runtimeCommand.subscriptionRefreshPrepared,
                    runtimeCommand.protocolTestTrafficFreeze,
                    runtimeCommand.replaceActiveTunnel,
                )
            else -> Unit
        }
    }
}

private fun dispatchDisconnectCommand(
    intent: Intent,
    startId: Int,
    trafficMode: TrafficMode,
    handlers: RuntimeServiceCommandHandlers,
) {
    val suppressLocalGuard =
        intent.getBooleanExtra(
            FoxholeConnectionServiceContract.EXTRA_SUPPRESS_LOCAL_GUARD,
            false,
        )
    val preserveSmartStartAnalysis =
        intent.getBooleanExtra(
            FoxholeConnectionServiceContract.EXTRA_PRESERVE_SMART_START_ANALYSIS,
            false,
        )
    val command = runtimeCommandForServiceAction(intent.action, trafficMode) ?: return
    handlers.dispatch(command) {
        handlers.disconnect(startId, suppressLocalGuard, preserveSmartStartAnalysis)
    }
}

private fun dispatchKillCommand(
    intent: Intent,
    startId: Int,
    handlers: RuntimeServiceCommandHandlers,
) {
    val killTor = intent.action == FoxholeConnectionServiceContract.ACTION_KILL_TOR
    val reason = if (killTor) "kill_tor" else "kill"
    val command = RuntimeCommand.Kill(reason = reason, source = RuntimeCommandSource.SERVICE)
    handlers.dispatch(command) {
        // Stopping TOR alone must not tear down a guard something else still wants: if I2P is
        // engaged with outside-tunnel (or the firewall is on), disconnect re-raises its guard.
        handlers.disconnect(startId, !killTor, false)
    }
}

private fun dispatchReloadCommand(
    intent: Intent,
    trafficMode: TrafficMode,
    handlers: RuntimeServiceCommandHandlers,
) {
    val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
    val command =
        runtimeCommandForServiceAction(
            action = intent.action,
            trafficMode = trafficMode,
            profileId = profileId,
        ) ?: return
    handlers.dispatch(command) { handlers.reload(profileId) }
}

private fun dispatchQuarantineCommand(
    intent: Intent,
    trafficMode: TrafficMode,
    handlers: RuntimeServiceCommandHandlers,
) {
    val revision =
        intent.getLongExtra(
            FoxholeConnectionServiceContract.EXTRA_QUARANTINE_POLICY_REVISION,
            0L,
        )
    val command =
        runtimeCommandForServiceAction(
            action = intent.action,
            trafficMode = trafficMode,
            quarantinePolicyRevision = revision,
        ) as? RuntimeCommand.EnforceQuarantine ?: return
    handlers.dispatch(command) { runtimeCommand ->
        handlers.enforceQuarantine((runtimeCommand as RuntimeCommand.EnforceQuarantine).requestedRevision)
    }
}

private fun dispatchRestoreCommand(
    intent: Intent,
    startId: Int,
    trafficMode: TrafficMode,
    container: FoxholeRuntimeDependencies,
    handlers: RuntimeServiceCommandHandlers,
) {
    val command = runtimeCommandForServiceAction(intent.action, trafficMode) ?: return
    handlers.dispatch(command) {
        restoreLastActiveConnection(
            container = container,
            startId = startId,
            connect = handlers.connect,
            disconnect = { commandStartId, suppressLocalGuard ->
                handlers.disconnect(commandStartId, suppressLocalGuard, false)
            },
            startLocalGuard = handlers.startLocalGuard,
            activeProfileSessionPresent = handlers.activeProfileSessionPresent(),
        )
    }
}

private fun dispatchLocalGuardCommand(
    intent: Intent,
    startId: Int,
    trafficMode: TrafficMode,
    container: FoxholeRuntimeDependencies,
    handlers: RuntimeServiceCommandHandlers,
) {
    val mode =
        intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_LOCAL_GUARD_MODE)
            ?.let { raw -> runCatching { LocalGuardMode.valueOf(raw) }.getOrNull() }
            ?: LocalGuardMode.FIREWALL
    val command =
        runtimeCommandForServiceAction(
            action = intent.action,
            trafficMode = trafficMode,
            localGuardMode = mode,
            // Different live settings must not coalesce behind a running start of the same mode.
            localGuardConfigStamp = container.settingsRepository.settings.value.hashCode(),
        ) ?: return
    handlers.dispatch(command) {
        handlers.startLocalGuard(mode, startId)
    }
}

internal fun isFailClosedRuntimeServiceCommand(action: String?): Boolean =
    action != null && action !in KNOWN_RUNTIME_SERVICE_ACTIONS

internal fun runtimeCommandForServiceAction(
    action: String?,
    trafficMode: TrafficMode,
    profileId: Long = -1L,
    protocolOptionId: String? = null,
    previousVpnNetworkHandle: Long? = null,
    subscriptionRefreshPrepared: Boolean = false,
    protocolTestTrafficFreeze: Boolean = false,
    replaceActiveTunnel: Boolean = false,
    localGuardMode: LocalGuardMode = LocalGuardMode.FIREWALL,
    localGuardConfigStamp: Int = 0,
    quarantinePolicyRevision: Long = 0L,
): RuntimeCommand? =
    when (action) {
        FoxholeConnectionServiceContract.ACTION_CONNECT ->
            when (trafficMode) {
                TrafficMode.TUNNEL ->
                    RuntimeCommand.StartTunnel(
                        profileId = profileId,
                        optionId = protocolOptionId,
                        previousVpnNetworkHandle = previousVpnNetworkHandle,
                        source = RuntimeCommandSource.SERVICE,
                        subscriptionRefreshPrepared = subscriptionRefreshPrepared,
                        protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                        replaceActiveTunnel = replaceActiveTunnel,
                    )
                TrafficMode.PROXY ->
                    RuntimeCommand.StartProxy(
                        profileId = profileId,
                        optionId = protocolOptionId,
                        source = RuntimeCommandSource.SERVICE,
                        subscriptionRefreshPrepared = subscriptionRefreshPrepared,
                        protocolTestTrafficFreeze = protocolTestTrafficFreeze,
                        replaceActiveTunnel = replaceActiveTunnel,
                    )
            }
        FoxholeConnectionServiceContract.ACTION_DISCONNECT ->
            RuntimeCommand.Stop(reason = "disconnect", source = RuntimeCommandSource.SERVICE)
        FoxholeConnectionServiceContract.ACTION_KILL ->
            RuntimeCommand.Kill(reason = "kill", source = RuntimeCommandSource.SERVICE)
        FoxholeConnectionServiceContract.ACTION_KILL_TOR ->
            RuntimeCommand.Kill(reason = "kill_tor", source = RuntimeCommandSource.SERVICE)
        FoxholeConnectionServiceContract.ACTION_RELOAD ->
            RuntimeCommand.Reload(reason = profileId.toString(), source = RuntimeCommandSource.SERVICE)
        FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE ->
            RuntimeCommand.EnforceQuarantine(
                requestedRevision = quarantinePolicyRevision,
                source = RuntimeCommandSource.SERVICE,
            )
        FoxholeConnectionServiceContract.ACTION_RESTORE ->
            RuntimeCommand.Restore(source = RuntimeCommandSource.SERVICE)
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD ->
            RuntimeCommand.StartLocalGuard(
                mode = localGuardMode,
                source = RuntimeCommandSource.SERVICE,
                configStamp = localGuardConfigStamp,
            )
        else -> null
    }

private suspend fun restoreLastActiveConnection(
    container: FoxholeRuntimeDependencies,
    startId: Int,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
        subscriptionRefreshPrepared: Boolean,
        protocolTestTrafficFreeze: Boolean,
        replaceActiveTunnel: Boolean,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?, suppressLocalGuard: Boolean) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit,
    activeProfileSessionPresent: Boolean,
) {
    if (shouldSkipRestoreForActiveProfileRuntime(activeProfileSessionPresent)) {
        container.diagnosticsLogger.record(
            "connection",
            "restore skipped: profile runtime already active",
        )
        return
    }
    val active =
        try {
            container.profileRepository.getActiveProfile()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Tile or restore before the database is unlocked: unhandled, the exception was
            // swallowed in runCommandSafely and the service stayed resident in CONNECTING.
            container.diagnosticsLogger.recordFailure(
                "connection",
                "restore failed: ${error.javaClass.simpleName}",
            )
            disconnect(startId, false)
            return
        }
    if (active == null) {
        val localGuardMode = container.settingsRepository.current().localGuardModeOrNull()
        container.diagnosticsLogger.record(
            "connection",
            if (localGuardMode == null) {
                "restore skipped: no active profile"
            } else {
                "restore fallback: no active profile, starting local guard"
            },
        )
        if (localGuardMode != null) {
            startLocalGuard(localGuardMode, startId)
            return
        }
        disconnect(startId, false)
        return
    }
    // RESTORE means "resume this profile", not "force this exact stale option". A mandatory
    // subscription refresh may rename/remove the stored option; null lets the refreshed persisted
    // selection (or its safe fallback) win instead of failing a boot/tile/widget restore.
    connect(
        active.id,
        startId,
        null,
        null,
        false,
        false,
        false,
    )
}

/** A second tile/widget/notification RESTORE must not refresh and restart an active profile. */
internal fun shouldSkipRestoreForActiveProfileRuntime(activeProfileSessionPresent: Boolean): Boolean =
    activeProfileSessionPresent

private val KNOWN_RUNTIME_SERVICE_ACTIONS =
    setOf(
        // Android may start the authorized VPN service with its manifest interface action.
        // It is a framework lifecycle signal, not an app command; the dispatcher leaves it as a no-op.
        VpnService.SERVICE_INTERFACE,
        FoxholeConnectionServiceContract.ACTION_CONNECT,
        FoxholeConnectionServiceContract.ACTION_DISCONNECT,
        FoxholeConnectionServiceContract.ACTION_KILL,
        FoxholeConnectionServiceContract.ACTION_KILL_TOR,
        FoxholeConnectionServiceContract.ACTION_RELOAD,
        FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE,
        FoxholeConnectionServiceContract.ACTION_RESTORE,
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
    )

private val NULL_INTENT_ACTIVE_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
        ConnectionState.DISCONNECTING,
    )
