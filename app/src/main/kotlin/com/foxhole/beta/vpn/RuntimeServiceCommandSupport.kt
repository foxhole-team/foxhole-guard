package com.foxhole.beta.vpn

import android.content.Intent
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.scopedByNetworkRules
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.preferredLastKnownGoodOptionId
import com.foxhole.beta.core.settings.smartProfilePreference

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
            val suppressLocalGuard = intent.getBooleanExtra(FoxholeConnectionServiceContract.EXTRA_SUPPRESS_LOCAL_GUARD, false)
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
            val reason =
                if (intent.action == FoxholeConnectionServiceContract.ACTION_KILL_TOR) {
                    "kill_tor"
                } else {
                    "kill"
                }
            val command = RuntimeCommand.Kill(reason = reason, source = RuntimeCommandSource.SERVICE)
            dispatchRuntimeCommand(command) {
                disconnect(
                    startId,
                    true,
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
                ) ?: return
            dispatchRuntimeCommand(command) {
                startLocalGuard(mode, startId)
            }
        }
    }
}

internal fun isPriorityRuntimeServiceCommand(action: String?): Boolean =
    action == FoxholeConnectionServiceContract.ACTION_CONNECT ||
        action == FoxholeConnectionServiceContract.ACTION_DISCONNECT ||
        action == FoxholeConnectionServiceContract.ACTION_KILL ||
        action == FoxholeConnectionServiceContract.ACTION_KILL_TOR ||
        action == FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD

internal fun isFailClosedRuntimeServiceCommand(action: String?): Boolean =
    action != null && action !in KNOWN_RUNTIME_SERVICE_ACTIONS

internal fun runtimeCommandForServiceAction(
    action: String?,
    trafficMode: TrafficMode,
    profileId: Long = -1L,
    protocolOptionId: String? = null,
    previousVpnNetworkHandle: Long? = null,
    localGuardMode: LocalGuardMode = LocalGuardMode.FIREWALL,
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
    val active = container.profileRepository.getActiveProfile()
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
    val settings = container.settingsRepository.current()
    val smartProfilePreference = settings.smartProfilePreference(active.id)
    val networkFingerprint =
        container.networkFingerprintProvider
            .currentFingerprint()
            ?.scopedByNetworkRules(settings.networkRules)
    val scopedLastKnownGoodOptionId = smartProfilePreference?.networkMemory(networkFingerprint?.key)?.lastKnownGoodOptionId
    val restoredOptionId =
        smartProfilePreference
            ?.preferredLastKnownGoodOptionId(networkFingerprint?.key)
            ?.takeIf { optionId ->
                optionId !in smartProfilePreference.excludedProtocolOptionIds &&
                    active.protocolOptions.any { option -> option.id == optionId }
            }
    restoredOptionId?.let { optionId ->
        val details =
            buildList {
                add("profile_id=${active.id}")
                add("option=$optionId")
                add("reason=restored_last_good")
                add("scope=${if (optionId == scopedLastKnownGoodOptionId) "network" else "profile"}")
                networkFingerprint?.key?.take(12)?.let { fingerprint -> add("network_fp=$fingerprint") }
            }
        container.diagnosticsLogger.recordStructured(
            "auto-connect",
            "restore candidate",
            *details.toTypedArray(),
        )
    }
    connect(active.id, startId, restoredOptionId, null)
}

private val KNOWN_RUNTIME_SERVICE_ACTIONS =
    setOf(
        FoxholeConnectionServiceContract.ACTION_CONNECT,
        FoxholeConnectionServiceContract.ACTION_DISCONNECT,
        FoxholeConnectionServiceContract.ACTION_KILL,
        FoxholeConnectionServiceContract.ACTION_KILL_TOR,
        FoxholeConnectionServiceContract.ACTION_RELOAD,
        FoxholeConnectionServiceContract.ACTION_RESTORE,
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
    )
