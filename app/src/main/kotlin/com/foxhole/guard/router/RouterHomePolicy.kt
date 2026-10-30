package com.foxhole.guard.router

internal data class RouterHomeProof(
    val pairingId: String,
    val serverId: String,
    val generation: String,
    val verifiedAtElapsedMs: Long,
    val thisPhoneRouted: Boolean,
    val tunnelValidated: Boolean,
)

internal data class RouterHomeOffEvidence(val pairingId: String, val verifiedAtElapsedMs: Long, val observedRouterUptimeMs: Long? = null)

internal data class RouterPhoneSession(
    val profileId: String,
    val profileRevision: Long,
    val connectionId: Long,
)

internal data class RouterAutomaticPause(
    val owner: RouterPhoneSession,
    val pairingId: String,
)

internal sealed interface RouterHomeAction {
    data object None : RouterHomeAction
    data class Pause(val ownership: RouterAutomaticPause) : RouterHomeAction
    data class Resume(val ownership: RouterAutomaticPause) : RouterHomeAction
}

internal class RouterHomePolicy {
    private var pause: RouterAutomaticPause? = null
    private var lastHealthyAtElapsedMs: Long? = null
    private var offSinceElapsedMs: Long? = null
    private var offSinceRouterUptimeMs: Long? = null
    private var manualOverride = false
    private var observedPairingId: String? = null

    fun evaluate(
        enabled: Boolean,
        pairedId: String?,
        wifiIsPaired: Boolean,
        proof: RouterHomeProof?,
        nowElapsedMs: Long,
        connectedPhone: RouterPhoneSession?,
        selectedProfileId: String?,
        profileRevision: Long,
        phoneIdle: Boolean,
        confirmedOff: RouterHomeOffEvidence? = null,
    ): RouterHomeAction {
        if (!enabled || pairedId == null || nowElapsedMs < 0) {
            clear(); manualOverride = false; observedPairingId = null
            return RouterHomeAction.None
        }
        if (observedPairingId != null && observedPairingId != pairedId) { clear(); manualOverride = false }
        observedPairingId = pairedId
        if (manualOverride) {
            if (wifiIsPaired) return RouterHomeAction.None
            manualOverride = false
        }
        val fresh = proof != null && proof.pairingId == pairedId && proof.serverId.isNotBlank() && proof.generation.isNotBlank() &&
            proof.tunnelValidated && proof.thisPhoneRouted && nowElapsedMs >= proof.verifiedAtElapsedMs &&
            nowElapsedMs - proof.verifiedAtElapsedMs <= FRESH_PROOF_MS
        val paused = pause
        if (paused != null) {
            if (paused.pairingId != pairedId || paused.owner.profileId != selectedProfileId ||
                paused.owner.profileRevision != profileRevision || connectedPhone != null && connectedPhone != paused.owner) {
                clear()
                return RouterHomeAction.None
            }
            if (!wifiIsPaired && phoneIdle) {
                clear()
                return RouterHomeAction.Resume(paused)
            }
            if (fresh) {
                lastHealthyAtElapsedMs = maxOf(lastHealthyAtElapsedMs ?: 0, requireNotNull(proof).verifiedAtElapsedMs)
                offSinceElapsedMs = null
                offSinceRouterUptimeMs = null
                return RouterHomeAction.None
            }
            val freshOff = confirmedOff != null && confirmedOff.pairingId == pairedId &&
                confirmedOff.verifiedAtElapsedMs >= (lastHealthyAtElapsedMs ?: 0) &&
                nowElapsedMs >= confirmedOff.verifiedAtElapsedMs && nowElapsedMs - confirmedOff.verifiedAtElapsedMs <= FRESH_PROOF_MS
            if (freshOff) {
                val observed = requireNotNull(confirmedOff).verifiedAtElapsedMs
                val uptime = confirmedOff.observedRouterUptimeMs
                if (uptime != null && offSinceRouterUptimeMs?.let { uptime < it } == true) {
                    offSinceElapsedMs = null; offSinceRouterUptimeMs = null
                }
                if (offSinceElapsedMs == null) offSinceRouterUptimeMs = uptime
                val since = offSinceElapsedMs ?: observed.also { offSinceElapsedMs = it }
                val routerSpaced = uptime == null || offSinceRouterUptimeMs == null || uptime - requireNotNull(offSinceRouterUptimeMs) >= CONFIRMED_LOSS_MS
                if (observed >= since && observed - since >= CONFIRMED_LOSS_MS && routerSpaced && phoneIdle) {
                    clear()
                    return RouterHomeAction.Resume(paused)
                }
            } else { offSinceElapsedMs = null; offSinceRouterUptimeMs = null }
            val healthy = lastHealthyAtElapsedMs
            if (healthy != null && nowElapsedMs >= healthy && nowElapsedMs - healthy >= MISSING_PROOF_GRACE_MS && phoneIdle) {
                clear()
                return RouterHomeAction.Resume(paused)
            }
            return RouterHomeAction.None
        }
        if (wifiIsPaired && fresh && connectedPhone != null && connectedPhone.profileId == selectedProfileId &&
            connectedPhone.profileRevision == profileRevision) {
            val ownership = RouterAutomaticPause(connectedPhone, pairedId)
            pause = ownership
            lastHealthyAtElapsedMs = requireNotNull(proof).verifiedAtElapsedMs
            offSinceElapsedMs = null
            offSinceRouterUptimeMs = null
            return RouterHomeAction.Pause(ownership)
        }
        return RouterHomeAction.None
    }

    fun manualPhoneAction() { clear(); manualOverride = true }
    fun pauseFailed(owner: RouterAutomaticPause) { if (pause == owner) clear() }
    fun resumeFailed(owner: RouterAutomaticPause, nowElapsedMs: Long) {
        if (pause == null && !manualOverride && observedPairingId == owner.pairingId && nowElapsedMs >= 0) {
            pause = owner
            lastHealthyAtElapsedMs = (nowElapsedMs - MISSING_PROOF_GRACE_MS).coerceAtLeast(0)
        }
    }

    private fun clear() { pause = null; lastHealthyAtElapsedMs = null; offSinceElapsedMs = null; offSinceRouterUptimeMs = null }
    private companion object {
        const val FRESH_PROOF_MS = 5000L
        const val CONFIRMED_LOSS_MS = 2000L
        const val MISSING_PROOF_GRACE_MS = 30000L
    }
}
