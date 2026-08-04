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

internal fun Service.handleForegroundRuntimeCommand(
    intent: Intent?,
    startId: Int,
    trafficMode: TrafficMode,
    notificationManager: NotificationManager,
    currentNotificationSnapshot: () -> NotificationSnapshot,
    buildNotification: (NotificationSnapshot) -> Notification,
    container: FoxholeRuntimeDependencies,
    dispatchRuntimeCommand: (RuntimeCommand, suspend (RuntimeCommand) -> Unit) -> Unit,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?) -> Unit,
    disconnectWithOptions: suspend (
        commandStartId: Int?,
        suppressLocalGuard: Boolean,
        preserveSmartStartAnalysis: Boolean,
    ) -> Unit = { commandStartId, _, _ ->
        disconnect(commandStartId)
    },
    reload: suspend (profileIdHint: Long) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit = { _, commandStartId ->
        disconnect(commandStartId)
    },
    failClosedTeardown: suspend (commandStartId: Int, action: String?) -> Unit,
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
                dispatchRuntimeCommand(
                    RuntimeCommand.Stop(
                        reason = "null_intent_reconcile",
                        source = RuntimeCommandSource.SYSTEM,
                    ),
                ) {
                    disconnectWithOptions(
                        startId,
                        true,
                        false,
                    )
                }
            }
        }
        isFailClosedRuntimeServiceCommand(action) -> {
            dispatchRuntimeCommand(
                RuntimeCommand.Kill(
                    reason = "fail_closed:$action",
                    source = RuntimeCommandSource.SYSTEM,
                ),
            ) { failClosedTeardown(startId, action) }
        }
        else -> {
            handleRuntimeServiceCommand(
                intent = intent,
                startId = startId,
                trafficMode = trafficMode,
                container = container,
                dispatchRuntimeCommand = dispatchRuntimeCommand,
                connect = connect,
                disconnect = disconnectWithOptions,
                reload = reload,
                startLocalGuard = startLocalGuard,
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

@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun handleRuntimeServiceCommand(
    intent: Intent?,
    startId: Int,
    trafficMode: TrafficMode,
    container: FoxholeRuntimeDependencies,
    dispatchRuntimeCommand: (RuntimeCommand, suspend (RuntimeCommand) -> Unit) -> Unit,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (
        commandStartId: Int?,
        suppressLocalGuard: Boolean,
        preserveSmartStartAnalysis: Boolean,
    ) -> Unit,
    reload: suspend (profileIdHint: Long) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit,
) {
    when (intent?.action) {
        FoxholeConnectionServiceContract.ACTION_CONNECT -> {
            val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
            val protocolOptionId = intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_PROTOCOL_OPTION_ID)
            val previousVpnNetworkHandle = intent.previousVpnNetworkHandleOrNull()
            val command =
                runtimeCommandForServiceAction(
                    action = intent.action,
                    trafficMode = trafficMode,
                    profileId = profileId,
                    protocolOptionId = protocolOptionId,
                    previousVpnNetworkHandle = previousVpnNetworkHandle,
                ) ?: return
            dispatchRuntimeCommand(command) { runtimeCommand ->
                when (runtimeCommand) {
                    is RuntimeCommand.StartTunnel ->
                        connect(
                            runtimeCommand.profileId,
                            startId,
                            runtimeCommand.optionId,
                            runtimeCommand.previousVpnNetworkHandle,
                        )
                    is RuntimeCommand.StartProxy ->
                        connect(
                            runtimeCommand.profileId,
                            startId,
                            runtimeCommand.optionId,
                            null,
                        )
                    else -> Unit
                }
            }
        }

        FoxholeConnectionServiceContract.ACTION_DISCONNECT -> {
            val suppressLocalGuard = intent.getBooleanExtra(
                FoxholeConnectionServiceContract.EXTRA_SUPPRESS_LOCAL_GUARD,
                false
            )
            val preserveSmartStartAnalysis =
                intent.getBooleanExtra(FoxholeConnectionServiceContract.EXTRA_PRESERVE_SMART_START_ANALYSIS, false)
            val command =
                runtimeCommandForServiceAction(
                    action = intent.action,
                    trafficMode = trafficMode,
                ) ?: return
            dispatchRuntimeCommand(command) {
                disconnect(startId, suppressLocalGuard, preserveSmartStartAnalysis)
            }
        }

        FoxholeConnectionServiceContract.ACTION_KILL,
        FoxholeConnectionServiceContract.ACTION_KILL_TOR,
        -> {
            val killTor = intent.action == FoxholeConnectionServiceContract.ACTION_KILL_TOR
            val reason = if (killTor) "kill_tor" else "kill"
            val command = RuntimeCommand.Kill(reason = reason, source = RuntimeCommandSource.SERVICE)
            dispatchRuntimeCommand(command) {
                // Stopping TOR alone must not tear down a guard something else still wants: if I2P
                // is engaged with outside-tunnel (or the firewall is on), the disconnect re-raise
                // brings its transparent tun back up seamlessly. A full KILL suppresses the guard.
                disconnect(
                    startId,
                    !killTor,
                    false,
                )
            }
        }

        FoxholeConnectionServiceContract.ACTION_RELOAD -> {
            val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
            val command =
                runtimeCommandForServiceAction(
                    action = intent.action,
                    trafficMode = trafficMode,
                    profileId = profileId,
                ) ?: return
            dispatchRuntimeCommand(command) { reload(profileId) }
        }

        FoxholeConnectionServiceContract.ACTION_RESTORE -> {
            val command =
                runtimeCommandForServiceAction(
                    action = intent.action,
                    trafficMode = trafficMode,
                ) ?: return
            dispatchRuntimeCommand(command) {
                restoreLastActiveConnection(
                    container = container,
                    startId = startId,
                    connect = connect,
                    disconnect = { commandStartId, suppressLocalGuard ->
                        disconnect(commandStartId, suppressLocalGuard, false)
                    },
                    startLocalGuard = startLocalGuard,
                )
            }
        }

        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD -> {
            val mode =
                intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_LOCAL_GUARD_MODE)
                    ?.let { raw -> runCatching { LocalGuardMode.valueOf(raw) }.getOrNull() }
                    ?: LocalGuardMode.FIREWALL
            val command =
                runtimeCommandForServiceAction(
                    action = intent.action,
                    trafficMode = trafficMode,
                    localGuardMode = mode,
                    // A hash of the live settings distinguishes guard starts with different
                    // configs in queueReason: otherwise a StartLocalGuard sent because settings
                    // changed coalesced with a still-running start of the same mode and the change
                    // was silently dropped.
                    localGuardConfigStamp = container.settingsRepository.settings.value.hashCode(),
                ) ?: return
            dispatchRuntimeCommand(command) {
                startLocalGuard(mode, startId)
            }
        }
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
    localGuardMode: LocalGuardMode = LocalGuardMode.FIREWALL,
    localGuardConfigStamp: Int = 0,
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
                    )
                TrafficMode.PROXY ->
                    RuntimeCommand.StartProxy(
                        profileId = profileId,
                        optionId = protocolOptionId,
                        source = RuntimeCommandSource.SERVICE,
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
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?, suppressLocalGuard: Boolean) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit,
) {
    val active =
        try {
            container.profileRepository.getActiveProfile()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Tile or restore before the database is unlocked: unhandled, the exception was
            // swallowed in runCommandSafely and the service stayed resident in CONNECTING.
            container.diagnosticsLogger.record(
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
    val restoredOptionId =
        active.selectedProtocolOptionId
            ?.takeIf { optionId ->
                active.protocolOptions.any { option -> option.id == optionId }
            }
    connect(active.id, startId, restoredOptionId, null)
}

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
        FoxholeConnectionServiceContract.ACTION_RESTORE,
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
    )

private val NULL_INTENT_ACTIVE_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )
