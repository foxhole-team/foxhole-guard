package com.foxhole.guard.runtime

import android.os.Build
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimePolicyUpdate
import com.foxhole.core.runtime.RuntimePolicyUpdateException
import com.foxhole.guard.core.data.RoutingRuntimeCommit
import com.foxhole.guard.core.data.RuntimePresetSnapshot
import com.foxhole.guard.core.settings.SettingsRuntimeCommit

internal suspend fun FoxholeConnectionController.prepareAtomicRoutingSettingsCommit(
    before: Settings,
    next: Settings,
): SettingsRuntimeCommit? {
    if (!before.connection.atomicConnection) return null
    val preset = routingRepository.currentPresetForRuntime()
    if (runtimeConfigAssembler.runtimeSettingsEquivalent(before, next)) return null
    val owner = activeAtomicOwner() ?: return null
    requireAtomicCaptureMode(before, next)
    val epoch = owner.nativeSnapshot().nativeGeneration
    val candidate = atomicCandidateBuilder(next)(preset)
    requireAtomicOwner(owner, epoch)
    val update = owner.prepareAtomicPolicyUpdate(candidate)
    return SettingsRuntimeCommit { completeAtomicUpdate(owner, candidate, update) }
}

internal suspend fun FoxholeConnectionController.prepareAtomicRoutingPresetCommit(
    preset: RoutingPreset?,
): RoutingRuntimeCommit? {
    val settings = settingsRepository.current()
    if (!settings.connection.atomicConnection) return null
    val owner = activeAtomicOwner() ?: return null
    requireAtomicCaptureMode(settings, settings)
    val epoch = owner.nativeSnapshot().nativeGeneration
    // Profile and secret inputs are captured before opening the Room write transaction.
    val build = atomicCandidateBuilder(settings)
    var candidate = build(preset)
    requireAtomicOwner(owner, epoch)
    val update = owner.prepareAtomicPolicyUpdate(candidate)
    return object : RoutingRuntimeCommit {
        override suspend fun stage(preset: RoutingPreset?) {
            val staged = build(preset)
            update.stage(staged)
            candidate = staged
        }
        override suspend fun committed() = completeAtomicUpdate(owner, candidate, update)
    }
}

private fun FoxholeConnectionController.activeAtomicOwner(): FoxholeRuntime? =
    runtimeInstanceStore.current()?.takeIf { it.nativeSnapshot().hasEngineHandle }

private fun requireAtomicCaptureMode(before: Settings, next: Settings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || before.traffic.mode != TrafficMode.TUNNEL ||
        next.traffic.mode != TrafficMode.TUNNEL
    ) throw RuntimePolicyUpdateException(RuntimePolicyUpdateException.Reason.UNSUPPORTED)
}

private fun FoxholeConnectionController.requireAtomicOwner(owner: FoxholeRuntime, epoch: Long) {
    if (runtimeInstanceStore.current() !== owner || owner.nativeSnapshot().nativeGeneration != epoch) {
        throw RuntimePolicyUpdateException(RuntimePolicyUpdateException.Reason.SUPERSEDED)
    }
}

private suspend fun FoxholeConnectionController.atomicCandidateBuilder(
    settings: Settings,
): suspend (RoutingPreset?) -> VpnSession {
    val snapshot = snapshot.value
    val profileId = snapshot.profileId
        ?: throw RuntimePolicyUpdateException(RuntimePolicyUpdateException.Reason.UNSUPPORTED)
    val dns = PrivateDnsSettings.currentState(context)
    val prepared = if (profileId == TOR_ONLY_PROFILE_ID) null else {
        if (profileId <= 0L) throw RuntimePolicyUpdateException(RuntimePolicyUpdateException.Reason.UNSUPPORTED)
        profileRepository.prepareSessionSnapshot(profileId, snapshot.protocolOptionId, settings)
    }
    return { preset ->
        if (profileId == TOR_ONLY_PROFILE_ID) {
            profileRepository.sessionFactory.getTorOnlySession(
                privateDnsState = dns, settingsOverride = settings, presetOverride = RuntimePresetSnapshot(preset),
            )
        } else {
            profileRepository.sessionFactory.getSession(
                profileId = profileId,
                protocolOptionIdOverride = snapshot.protocolOptionId,
                privateDnsState = dns,
                preparedSnapshot = prepared,
                presetOverride = RuntimePresetSnapshot(preset),
            )
        }
    }
}

private suspend fun FoxholeConnectionController.completeAtomicUpdate(
    owner: FoxholeRuntime,
    candidate: VpnSession,
    update: RuntimePolicyUpdate,
) {
    update.commit().getOrThrow()
    if (runtimeInstanceStore.current() !== owner || !owner.nativeSnapshot().hasEngineHandle) {
        throw RuntimePolicyUpdateException(RuntimePolicyUpdateException.Reason.SUPERSEDED)
    }
    markRuntimeApplied(candidate, owner.nativeSnapshot().nativeGeneration)
    // The existing service refreshes its session metadata and performs validation.
    reload(candidate.profileId)
}
