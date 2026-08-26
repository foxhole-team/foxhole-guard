package com.foxhole.guard.core.security

import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AppLockTimeout
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LockState {
    UNLOCKED,
    LOCKED_SYSTEM,
    LOCKED_PASSWORD,
}

sealed interface UnlockOutcome {
    data object Success : UnlockOutcome

    class WrongPassword(
        val failedAttempts: Int,
        val nextDelayMs: Long,
        val resetOffered: Boolean,
    ) : UnlockOutcome

    class Corrupted(
        val reason: String,
    ) : UnlockOutcome
}

class AuthAttemptNotice(
    val failedAttempts: Int,
    val lastFailedAt: Long,
)

// Relocking zeroizes Java key copies; the already-open native SQLCipher/VPN session may keep serving.
class AppLockManager internal constructor(
    private val keybox: SecureKeybox,
    private val session: SecureSessionHolder,
    private val appLockMode: () -> AppLockMode,
    private val lockTimeout: () -> AppLockTimeout,
    private val journal: (GuardEvent) -> Unit,
    private val elapsedRealtime: () -> Long,
) {
    private val lockStateFlow = MutableStateFlow(initialLockState())
    val lockState: StateFlow<LockState> = lockStateFlow.asStateFlow()

    private val authNoticeFlow = MutableStateFlow<AuthAttemptNotice?>(null)

    val pendingAuthNotice: StateFlow<AuthAttemptNotice?> = authNoticeFlow.asStateFlow()

    private var backgroundedAtElapsed: Long? = null
    private var unlockedSession: UnlockedKeyboxSession? = null

    fun currentLockState(): LockState = lockStateFlow.value

    internal val checkpoint: KeyboxCheckpoint?
        get() = unlockedSession?.checkpoint

    private fun initialLockState(): LockState =
        when (appLockMode()) {
            AppLockMode.OFF -> LockState.UNLOCKED
            AppLockMode.SYSTEM -> LockState.LOCKED_SYSTEM
            AppLockMode.PASSWORD -> if (keybox.exists()) LockState.LOCKED_PASSWORD else LockState.UNLOCKED
        }

    fun refreshFromSettings() {
        if (appLockMode() == AppLockMode.OFF) {
            unlockedSession?.destroy()
            unlockedSession = null
            session.clearJavaCopies()
            backgroundedAtElapsed = null
            lockStateFlow.value = LockState.UNLOCKED
            return
        }
        val target = initialLockState()
        if (target == LockState.UNLOCKED && lockStateFlow.value != LockState.UNLOCKED) {
            lockStateFlow.value = LockState.UNLOCKED
        } else if (target != LockState.UNLOCKED && lockStateFlow.value == LockState.UNLOCKED && !session.isUnlocked) {
            lockStateFlow.value = target
        }
    }

    fun onAppBackgrounded() {
        if (lockStateFlow.value == LockState.UNLOCKED && appLockMode() != AppLockMode.OFF) {
            backgroundedAtElapsed = elapsedRealtime()
        }
    }

    fun onScreenOff() {
        if (lockTimeout() == AppLockTimeout.AFTER_REBOOT) {
            return
        }
        if (lockStateFlow.value == LockState.UNLOCKED && appLockMode() != AppLockMode.OFF) {
            relock()
        }
    }

    fun onAppForegrounded() {
        val backgroundedAt = backgroundedAtElapsed ?: return
        backgroundedAtElapsed = null
        val timeout = lockTimeout()
        if (timeout == AppLockTimeout.AFTER_REBOOT) {
            return
        }
        val awayMs = elapsedRealtime() - backgroundedAt
        if (awayMs >= timeout.minutes * MILLIS_PER_MINUTE) {
            relock()
        }
    }

    private fun relock() {
        when (appLockMode()) {
            AppLockMode.OFF -> Unit
            AppLockMode.SYSTEM -> lockStateFlow.value = LockState.LOCKED_SYSTEM
            AppLockMode.PASSWORD -> {
                unlockedSession?.destroy()
                unlockedSession = null
                session.clearJavaCopies()
                lockStateFlow.value = LockState.LOCKED_PASSWORD
            }
        }
    }

    fun unlockWithSystemAuth() {
        if (appLockMode() != AppLockMode.SYSTEM) {
            return
        }
        publishAuthNoticeFromAttempts()
        keybox.resetAttempts()
        journal(GuardEvent(type = GuardEventType.UNLOCK_OK))
        lockStateFlow.value = LockState.UNLOCKED
    }

    fun recordPromptAuthFailure() {
        val attempts = keybox.recordFailedAttempt()
        journal(GuardEvent(type = GuardEventType.UNLOCK_FAILED, attempt = attempts.failedAttempts))
    }

    fun unlockWithPassword(password: ByteArray): UnlockOutcome =
        when (val outcome = keybox.unlock(password)) {
            is KeyboxUnlockOutcome.Success -> {
                installSession(outcome.session)
                publishAuthNotice(outcome.priorFailedAttempts, outcome.priorLastFailedAt)
                journal(GuardEvent(type = GuardEventType.UNLOCK_OK))
                UnlockOutcome.Success
            }
            is KeyboxUnlockOutcome.WrongPassword -> {
                journal(GuardEvent(type = GuardEventType.UNLOCK_FAILED, attempt = outcome.failedAttempts))
                UnlockOutcome.WrongPassword(
                    failedAttempts = outcome.failedAttempts,
                    nextDelayMs = outcome.nextDelayMs,
                    resetOffered = outcome.failedAttempts >= RESET_OFFER_THRESHOLD,
                )
            }
            is KeyboxUnlockOutcome.Corrupted -> UnlockOutcome.Corrupted(outcome.reason)
            KeyboxUnlockOutcome.Missing -> {
                lockStateFlow.value = LockState.UNLOCKED
                UnlockOutcome.Success
            }
        }

    fun unlockWithBiometricMasterKey(masterKey: ByteArray): Boolean =
        when (val outcome = keybox.unlockWithMasterKey(masterKey)) {
            is KeyboxUnlockOutcome.Success -> {
                installSession(outcome.session)
                publishAuthNotice(outcome.priorFailedAttempts, outcome.priorLastFailedAt)
                journal(GuardEvent(type = GuardEventType.UNLOCK_OK))
                true
            }
            is KeyboxUnlockOutcome.Missing -> {
                lockStateFlow.value = LockState.UNLOCKED
                true
            }
            else -> false
        }

    private fun publishAuthNotice(
        failedAttempts: Int,
        lastFailedAt: Long,
    ) {
        if (failedAttempts > 0) {
            authNoticeFlow.value = AuthAttemptNotice(failedAttempts = failedAttempts, lastFailedAt = lastFailedAt)
        }
    }

    private fun publishAuthNoticeFromAttempts() {
        val attempts = keybox.readAttempts()
        publishAuthNotice(attempts.failedAttempts, attempts.lastFailedWallClock)
    }

    fun consumeAuthNotice() {
        authNoticeFlow.value = null
    }

    private fun installSession(unlocked: UnlockedKeyboxSession) {
        unlockedSession?.destroy()
        unlockedSession = unlocked
        session.install(
            dataKey = unlocked.dataKey,
            guardPrivateKey = unlocked.guardPrivateKey,
            guardPublicKey = unlocked.guardPublicKey,
        )
        backgroundedAtElapsed = null
        lockStateFlow.value = LockState.UNLOCKED
    }

    internal fun adoptFreshSession(unlocked: UnlockedKeyboxSession) {
        installSession(unlocked)
    }

    internal fun copyMasterKeyOrNull(): ByteArray? = unlockedSession?.copyMasterKey()

    fun updateJournalCheckpoint(
        seq: Long,
        headHash: String,
    ) {
        unlockedSession?.updateJournalCheckpoint(seq, headHash)
    }

    fun failedAttempts(): Int = keybox.readAttempts().failedAttempts

    fun initialUnlockBackoffMs(): Long = keybox.remainingBackoffMs()

    companion object {
        const val RESET_OFFER_THRESHOLD = 10
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
