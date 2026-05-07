package com.foxhole.beta.vpn

import android.content.Intent
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.preferredLastKnownGoodOptionId
import com.foxhole.beta.core.settings.smartProfilePreference

internal fun handleRuntimeServiceCommand(
    intent: Intent?,
    startId: Int,
    container: FoxholeRuntimeDependencies,
    launchCommand: (suspend () -> Unit) -> Unit,
    launchPriorityCommand: (suspend () -> Unit) -> Unit,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?, suppressLocalGuard: Boolean) -> Unit,
    reload: suspend (profileIdHint: Long) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit,
) {
    when (intent?.action) {
        FoxholeConnectionServiceContract.ACTION_CONNECT -> {
            val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
            val protocolOptionId = intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_PROTOCOL_OPTION_ID)
            val previousVpnNetworkHandle = intent.previousVpnNetworkHandleOrNull()
            launchCommand {
                connect(profileId, startId, protocolOptionId, previousVpnNetworkHandle)
            }
        }

        FoxholeConnectionServiceContract.ACTION_DISCONNECT -> {
            val suppressLocalGuard = intent.getBooleanExtra(FoxholeConnectionServiceContract.EXTRA_SUPPRESS_LOCAL_GUARD, false)
            launchPriorityCommand { disconnect(startId, suppressLocalGuard) }
        }

        FoxholeConnectionServiceContract.ACTION_RELOAD -> {
            val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
            launchCommand { reload(profileId) }
        }

        FoxholeConnectionServiceContract.ACTION_RESTORE -> {
            launchCommand {
                restoreLastActiveConnection(
                    container = container,
                    startId = startId,
                    connect = connect,
                    disconnect = disconnect,
                    startLocalGuard = startLocalGuard,
                )
            }
        }

        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD -> {
            val mode =
                intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_LOCAL_GUARD_MODE)
                    ?.let { raw -> runCatching { LocalGuardMode.valueOf(raw) }.getOrNull() }
                    ?: LocalGuardMode.FIREWALL
            launchCommand { startLocalGuard(mode, startId) }
        }
    }
}

internal fun isPriorityRuntimeServiceCommand(action: String?): Boolean =
    action == FoxholeConnectionServiceContract.ACTION_DISCONNECT

internal fun isFailClosedRuntimeServiceCommand(action: String?): Boolean =
    action !in KNOWN_RUNTIME_SERVICE_ACTIONS

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
    val smartProfilePreference = container.settingsRepository.current().smartProfilePreference(active.id)
    val networkFingerprint = container.networkFingerprintProvider.currentFingerprint()
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
        FoxholeConnectionServiceContract.ACTION_RELOAD,
        FoxholeConnectionServiceContract.ACTION_RESTORE,
        FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
    )
