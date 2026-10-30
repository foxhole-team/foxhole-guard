package com.foxhole.guard.router

internal data class RouterUpdatePollStep(val delayMs: Long, val timeoutMs: Long, val statusOnly: Boolean)

// One foreground transaction epoch. Network/connection flips never reset its budgets.
internal class RouterUpdatePollBudget(private var startedAtMs: Long? = null, private val selectedRouterId: String?) {
    private var recoveryStartedAtMs: Long? = null
    private var recoveryAttempts = 0

    // First visible monitoring starts the epoch; navigation never renews it.
    fun begin(nowMs: Long): Long = startedAtMs ?: nowMs.also { startedAtMs = it }

    fun next(state: RouterControlState, nowMs: Long): RouterUpdatePollStep? {
        val started = begin(nowMs)
        if (nowMs < started || state.selectedRouterId != selectedRouterId || state.preferences?.enabled != true ||
            state.stage == RouterControlStage.PAIRING_REQUIRED || state.error in setOf(RouterWizardError.PIN,
                RouterWizardError.IDENTITY, RouterWizardError.LOCKED, RouterWizardError.LOCAL_NETWORK_PERMISSION) ||
            state.updates.failure == RouterUpdateFailureReason.AUTHENTICATION_FAILED) return null
        val owned = state.updates.pending != null || state.updates.job?.running == true
        val automatic = state.updates.status?.activeAutomaticJob?.running == true
        if (!owned && !automatic) return null
        val totalRemaining = 300_000L - (nowMs - started)
        val recovering = state.stage != RouterControlStage.CONNECTED || state.updates.unreachable ||
            state.updates.reconnecting || state.updates.pending != null
        if (!recovering) {
            if (totalRemaining <= 1500L) return null
            return RouterUpdatePollStep(1500, minOf(30_500, totalRemaining - 1500), false)
        }
        // Automatic jobs have no caller-owned identity; disconnected unknown startup stays manual.
        if (!owned) return null
        val recoveryStart = recoveryStartedAtMs ?: nowMs.also { recoveryStartedAtMs = it }
        val remaining = minOf(totalRemaining, 90_000L - (nowMs - recoveryStart))
        if (recoveryAttempts >= 10) return null
        val delay = when (recoveryAttempts) { 0 -> 2000L; 1 -> 4000L; 2 -> 8000L; else -> 10_000L }
        if (remaining <= delay) return null
        recoveryAttempts++
        return RouterUpdatePollStep(delay, minOf(30_500, remaining - delay), true)
    }
}
