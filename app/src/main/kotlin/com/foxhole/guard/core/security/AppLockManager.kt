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

/**
 * Failed entries recorded since the previous successful unlock, published right
 * after the next success so the UI can announce them once.
 */
class AuthAttemptNotice(
    val failedAttempts: Int,
    val lastFailedAt: Long,
)

/**
 * Owns the LOCKED/UNLOCKED state machine (task_new.md app lock). The PIN is asked
 * once at cold start and re-asked only when the app spent longer than the chosen
 * timeout in the background (AFTER_REBOOT = never on a live process; key material
 * cannot survive process death anyway, so a cold start always locks). While
 * password-locked the process stays alive and the VPN keeps running - only the
 * Java-side keys are zeroed until the next unlock re-installs them.
 */
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

    /** Non-null once after an unlock that followed failed attempts; consume to clear. */
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

    /** Re-evaluate at process start / after a settings change (enable/disable). */
    fun refreshFromSettings() {
        // Disabling password protection must also destroy the manager-owned master-key session.
        // Merely publishing UNLOCKED left both that session and SecureSessionHolder's Java copies
        // resident until process death, even though the keybox had already been deleted.
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
            // Protection was turned off elsewhere: nothing to gate on anymore.
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

    /**
     * The device screen turned off (ACTION_SCREEN_OFF) — the strongest "user stepped away and the
     * device is now secured" signal, and one that fires reliably even when the process is about to
     * be Doze-frozen (a coroutine timeout timer would not). Zeroize the in-memory keys now instead
     * of deferring to the next foreground, which could be much later or never. The only opt-out is
     * AFTER_REBOOT, where the user explicitly asked to keep the session for the whole process life;
     * every other timeout still gets its grace window while the screen stays on (active app
     * switching), it just does not survive the screen going dark. Only the Java-side key copies are
     * dropped — the native SQLCipher key stays resident, so an active VPN keeps running.
     */
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

    /**
     * SYSTEM level is a UI gate only: no keybox, no key material. It still shares the
     * attempts file so failed prompts survive process death and feed the notice.
     */
    fun unlockWithSystemAuth() {
        if (appLockMode() != AppLockMode.SYSTEM) {
            return
        }
        publishAuthNoticeFromAttempts()
        keybox.resetAttempts()
        journal(GuardEvent(type = GuardEventType.UNLOCK_OK))
        lockStateFlow.value = LockState.UNLOCKED
    }

    /**
     * A rejected biometric/device-credential prompt (SYSTEM mode or the PIN screen's
     * biometric button). Counted in the shared attempts file and journaled; the
     * Keystore-side backoff already throttles the prompt itself.
     */
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
                // Keybox vanished under us: treat as no protection.
                lockStateFlow.value = LockState.UNLOCKED
                UnlockOutcome.Success
            }
        }

    /**
     * Biometric unlock: opens the box with the hardware-unsealed master key. Takes
     * ownership of [masterKey] (the session keeps it on success, it is zeroed on
     * failure). False = stale/broken blob — the caller silently falls back to the PIN
     * without recording a failed attempt.
     */
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

    /**
     * Adopts the session produced by first enabling a password (create keybox), so
     * enabling protection unlocks in place instead of forcing an immediate re-entry.
     */
    internal fun adoptFreshSession(unlocked: UnlockedKeyboxSession) {
        installSession(unlocked)
    }

    /** Private master-key copy for biometric enrolment; null while locked. Caller zeroes it. */
    internal fun copyMasterKeyOrNull(): ByteArray? = unlockedSession?.copyMasterKey()

    fun updateJournalCheckpoint(
        seq: Long,
        headHash: String,
    ) {
        unlockedSession?.updateJournalCheckpoint(seq, headHash)
    }

    fun failedAttempts(): Int = keybox.readAttempts().failedAttempts

    /**
     * Backoff still owed from persisted failed attempts, so the unlock screen keeps throttling
     * across a process restart instead of letting a kill/relaunch reset the countdown to zero.
     */
    fun initialUnlockBackoffMs(): Long = keybox.remainingBackoffMs()

    companion object {
        const val RESET_OFFER_THRESHOLD = 10
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
