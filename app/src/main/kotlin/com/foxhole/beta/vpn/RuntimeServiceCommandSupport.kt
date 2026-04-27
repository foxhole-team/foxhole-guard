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
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?) -> Unit,
    reload: suspend (profileIdHint: Long) -> Unit,
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
            launchCommand { disconnect(startId) }
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
                )
            }
        }
    }
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
    disconnect: suspend (commandStartId: Int?) -> Unit,
) {
    val active = container.profileRepository.getActiveProfile()
    if (active == null) {
        disconnect(startId)
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
