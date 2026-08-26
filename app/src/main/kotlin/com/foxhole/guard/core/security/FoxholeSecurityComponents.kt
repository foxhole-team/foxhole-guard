package com.foxhole.guard.core.security

import android.content.Context
import com.foxhole.core.model.AppLockMode
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.readFastStoredAppLockMode
import com.foxhole.guard.core.settings.reconcileQuarantineFromGuardEvents
import com.foxhole.guard.guardian.AndroidGuardClock
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardInventorySnapshotStore
import com.foxhole.guard.guardian.GuardJournal
import com.foxhole.guard.guardian.GuardJournalReport
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.guardian.GuardJournalVerifier
import com.foxhole.guard.guardian.GuardSentinel
import java.io.File

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
            onReconciledPackageEvents = settingsRepository::reconcileQuarantineFromGuardEvents,
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

    fun legacyPasswordCredential(): Boolean =
        keybox.credentialKindOrNull() == KeyboxDocument.CREDENTIAL_PASSWORD

    fun isPasswordProtectionActive(): Boolean = effectiveLockMode() == AppLockMode.PASSWORD

    fun isEventMonitoringActive(): Boolean =
        settingsRepository.settings.value.anomaly.enabled &&
            isPasswordProtectionActive() &&
            settingsRepository.settings.value.appLock.eventMonitoringEnabled

    fun isDatabaseLockedForBackground(): Boolean =
        isPasswordProtectionActive() && !secureSessionHolder.dataKeyAvailable.value

    val dataKeyAvailable get() = secureSessionHolder.dataKeyAvailable

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

    fun resetAfterFactoryReset() {
        guardSentinel.clearHosts()
        guardJournal.deleteAll()
        inventorySnapshotStore.delete()
        biometricGate.clear()
        appLockManager.refreshFromSettings()
    }

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
