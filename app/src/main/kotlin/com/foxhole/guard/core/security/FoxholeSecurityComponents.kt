package com.foxhole.guard.core.security

import android.content.Context
import com.foxhole.core.model.AppLockMode
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.readFastStoredAppLockMode
import com.foxhole.guard.guardian.AndroidGuardClock
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardInventorySnapshotStore
import com.foxhole.guard.guardian.GuardJournal
import com.foxhole.guard.guardian.GuardJournalReport
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.guardian.GuardJournalVerifier
import com.foxhole.guard.guardian.GuardSentinel
import java.io.File

/**
 * Assembles the security core singletons. Split out of the main graph modules so the
 * lazysodium dependency and the keybox/journal wiring stay in one place. Everything
 * here works before the DB is available (that is the whole point) and needs only the
 * app context + settings repository.
 */
class FoxholeSecurityComponents(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val recordSecurityDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val secureDir = File(appContext.filesDir, "secure")
    private val guardDir = File(appContext.filesDir, "guard")
    private val journalDir = File(guardDir, "journal")

    val guardCrypto: GuardCrypto by lazy { LazySodiumGuardCrypto() }

    val secureSessionHolder: SecureSessionHolder by lazy { SecureSessionHolder() }

    private val guardClock by lazy { AndroidGuardClock(appContext) }

    internal val keybox: SecureKeybox by lazy {
        SecureKeybox(
            keyboxFile = File(secureDir, KEYBOX_FILE_NAME),
            attemptsFile = File(secureDir, ATTEMPTS_FILE_NAME),
            attemptsCipher = AndroidKeystoreFileCipher("foxhole.keybox.attempts"),
            // Hardware wrap for the whole document: a 6-digit PIN must not be brute
            // forceable off-device. Legacy plain-JSON boxes migrate on first read.
            keyboxCipher = AndroidKeystoreFileCipher("foxhole.keybox"),
            crypto = guardCrypto,
        )
    }

    internal val biometricGate: BiometricKeyboxGate by lazy {
        BiometricKeyboxGate(
            bioFile = File(secureDir, BIO_FILE_NAME),
            recordDiagnostic = recordSecurityDiagnostic,
        )
    }

    internal val inventorySnapshotStore: GuardInventorySnapshotStore by lazy {
        GuardInventorySnapshotStore(
            file = File(guardDir, INVENTORY_FILE_NAME),
            cipher = AndroidKeystoreFileCipher("foxhole.guard.inventory"),
        )
    }

    internal val guardJournal: GuardJournal by lazy {
        GuardJournal(
            directory = journalDir,
            crypto = guardCrypto,
            clock = guardClock,
            publicKeyProvider = { secureSessionHolder.guardPublicKeyOrNull() ?: keybox.guardPublicKeyOrNull() },
            checkpointSeqProvider = { appLockManager.checkpoint?.seq ?: keybox.checkpointOrNull()?.seq ?: -1L },
        )
    }

    val appLockManager: AppLockManager by lazy {
        AppLockManager(
            keybox = keybox,
            session = secureSessionHolder,
            appLockMode = ::effectiveLockMode,
            lockTimeout = { settingsRepository.settings.value.appLock.lockTimeout },
            journal = ::journalEvent,
            elapsedRealtime = guardClock::elapsedRealtimeMs,
        )
    }

    val guardSentinel: GuardSentinel by lazy {
        GuardSentinel(
            context = appContext,
            clock = guardClock,
            inventoryStore = inventorySnapshotStore,
            journal = ::journalEvent,
            isActive = ::isEventMonitoringActive,
            lastJournalRecordAt = guardJournal::lastRecordWallClockMs,
        )
    }

    fun guardHostingMode() = settingsRepository.settings.value.appLock.guardHosting

    val appLockSetupCoordinator: AppLockSetupCoordinator by lazy {
        AppLockSetupCoordinator(
            context = appContext,
            settingsRepository = settingsRepository,
            keybox = keybox,
            appLockManager = appLockManager,
            crypto = guardCrypto,
            journal = ::journalEvent,
            biometricGate = biometricGate,
        )
    }

    fun journalEvent(event: GuardEvent): Boolean = guardJournal.append(event)

    fun keyboxExists(): Boolean = keybox.exists()

    /** Action-gate credential check: no attempt counter, no session install. */
    fun verifyKeyboxPassword(password: ByteArray): Boolean = keybox.verifyPassword(password)

    /**
     * True when the active keybox still opens with the legacy free-form password
     * (pre-PIN install): the unlock screen falls back to the text field for it.
     */
    fun legacyPasswordCredential(): Boolean =
        keybox.credentialKindOrNull() == KeyboxDocument.CREDENTIAL_PASSWORD

    fun isPasswordProtectionActive(): Boolean = effectiveLockMode() == AppLockMode.PASSWORD

    /**
     * The event-monitoring service: the sealed guard journal, the install watcher and
     * its heartbeat. It rides the PIN (the journal is sealed to the keybox keypair) but
     * is separately switchable, so a PIN can guard entry alone.
     */
    fun isEventMonitoringActive(): Boolean =
        isPasswordProtectionActive() && settingsRepository.settings.value.appLock.eventMonitoringEnabled

    /**
     * True while the profile DB must not be touched: password protection is on but the
     * dataKey has not been unlocked yet (cold start after reboot, or re-locked). Callers
     * that would open/read the SQLCipher DB skip and retry after the next unlock.
     */
    fun isDatabaseLockedForBackground(): Boolean =
        isPasswordProtectionActive() && !secureSessionHolder.dataKeyAvailable.value

    val dataKeyAvailable get() = secureSessionHolder.dataKeyAvailable

    /**
     * Fail-closed lock mode: a keybox on disk means PASSWORD regardless of what the
     * (tamperable) settings say. Before hydration the fast-store mirror answers.
     */
    private fun effectiveLockMode(): AppLockMode =
        when {
            keybox.exists() -> AppLockMode.PASSWORD
            settingsRepository.hydrated.value -> settingsRepository.settings.value.appLock.mode
            else -> readFastStoredAppLockMode(appContext)
        }

    fun verifyGuardJournal(): GuardJournalReport? {
        val publicKey = secureSessionHolder.guardPublicKeyOrNull() ?: return null
        val privateKey = secureSessionHolder.copyGuardPrivateKeyOrNull() ?: return null
        return try {
            GuardJournalVerifier(journalDir, guardCrypto)
                .verify(
                    publicKey = publicKey,
                    privateKey = privateKey,
                    checkpoint = appLockManager.checkpoint ?: keybox.checkpointOrNull(),
                )
                .also(::anchorCheckpointToVerifiedHead)
        } finally {
            privateKey.zeroize()
        }
    }

    /**
     * Drops every process-local guard owner/cache after the backing files were factory-reset.
     * Generic file deletion alone is insufficient: the journal keeps an in-memory chain head and
     * the lock manager owns zeroizable key copies for the lifetime of the process.
     */
    fun resetAfterFactoryReset() {
        guardSentinel.clearHosts()
        guardJournal.deleteAll()
        inventorySnapshotStore.delete()
        biometricGate.clear()
        appLockManager.refreshFromSettings()
    }

    /**
     * Advance the password-anchored keybox checkpoint to the freshly verified head. This is what
     * arms rollback/truncation detection — GuardJournalVerifier compares the next run against this
     * anchor — and what lets GuardJournal.pruneCheckpointedFiles() drop pre-checkpoint files (with
     * no checkpoint the journal would also grow past MAX_FILES). Only on a clean chain: a dirty
     * verify keeps the previous anchor so the anomaly stays visible. Re-anchoring rides the GCM AAD,
     * so it needs the unlocked master key (no Argon2) and no-ops once the session is gone.
     */
    private fun anchorCheckpointToVerifiedHead(report: GuardJournalReport) {
        if (report.status != GuardJournalStatus.OK) return
        val headSeq = report.headSeq ?: return
        val headHash = report.headHash ?: return
        val current = appLockManager.checkpoint
        if (current?.seq == headSeq && current.headHash == headHash) return
        appLockManager.updateJournalCheckpoint(headSeq, headHash)
    }

    private companion object {
        const val KEYBOX_FILE_NAME = "keybox.bin"
        const val ATTEMPTS_FILE_NAME = "attempts.bin"
        const val BIO_FILE_NAME = "keybox.bio"
        const val INVENTORY_FILE_NAME = "inventory.bin"
    }
}
