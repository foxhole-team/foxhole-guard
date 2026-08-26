package com.foxhole.guard.core.security

import android.content.Context
import com.foxhole.core.model.AppLockMode
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.markAppLockPasswordSet
import com.foxhole.guard.core.settings.updateAppLockMode
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PasswordSetupResult {
    data object Success : PasswordSetupResult

    data object WrongPassword : PasswordSetupResult

    data object Failed : PasswordSetupResult
}

class AppLockSetupCoordinator internal constructor(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val keybox: SecureKeybox,
    private val appLockManager: AppLockManager,
    private val crypto: GuardCrypto,
    private val journal: (GuardEvent) -> Unit,
    private val biometricGate: BiometricKeyboxGate,
) {
    private val appContext = context.applicationContext
    private val keystoreSource = KeystoreDatabaseKeySource(appContext, keyboxExists = keybox::exists)

    /** Enables the custom password. Runs Argon2id off the main thread. */
    suspend fun enablePasswordProtection(password: CharArray): PasswordSetupResult {
        if (keybox.exists()) {
            return PasswordSetupResult.Success
        }
        val dataKey =
            keystoreSource.readExistingOrNull()
                ?: runCatching { keystoreSource.acquirePassphrase() }.getOrElse {
                    return PasswordSetupResult.Failed
                }
        val passwordBytes = encodeUtf8(password)
        return try {
            withContext(Dispatchers.Default) {
                val kdf = KdfCalibration.calibrate(crypto)
                val session = keybox.create(passwordBytes, dataKey, kdf)
                journal(GuardEvent(type = GuardEventType.GUARD_ENABLED))

                keystoreSource.delete()
                appLockManager.adoptFreshSession(session)
            }
            settingsRepository.updateAppLockMode(AppLockMode.PASSWORD)
            settingsRepository.markAppLockPasswordSet()
            PasswordSetupResult.Success
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PasswordSetupResult.Failed
        } finally {
            dataKey.zeroize()
            passwordBytes.zeroize()
        }
    }

    /** Disables the custom password: requires it, then returns the key to the Keystore. */
    suspend fun disablePasswordProtection(password: CharArray): PasswordSetupResult {
        journal(GuardEvent(type = GuardEventType.GUARD_DISABLE_REQUESTED))
        val passwordBytes = encodeUtf8(password)
        return try {
            withContext(Dispatchers.Default) { disableWithVerifiedPassword(passwordBytes) }
        } finally {
            passwordBytes.zeroize()
        }
    }

    suspend fun disablePasswordProtectionWithMasterKey(masterKey: ByteArray): PasswordSetupResult {
        journal(GuardEvent(type = GuardEventType.GUARD_DISABLE_REQUESTED))
        return withContext(Dispatchers.Default) {
            teardownFromOutcome(keybox.unlockWithMasterKey(masterKey))
        }
    }

    private suspend fun disableWithVerifiedPassword(passwordBytes: ByteArray): PasswordSetupResult =
        teardownFromOutcome(keybox.unlock(passwordBytes))

    private suspend fun teardownFromOutcome(outcome: KeyboxUnlockOutcome): PasswordSetupResult {
        when (outcome) {
            is KeyboxUnlockOutcome.Success -> {
                val session = outcome.session
                try {
                    keystoreSource.writeBack(session.dataKey)
                    journal(GuardEvent(type = GuardEventType.GUARD_DISABLED))
                    keybox.delete()
                    biometricGate.clear()
                } finally {
                    session.destroy()
                }
                settingsRepository.updateAppLockMode(AppLockMode.OFF)
                settingsRepository.markAppLockPasswordSet(null)
                appLockManager.refreshFromSettings()
                return PasswordSetupResult.Success
            }
            is KeyboxUnlockOutcome.WrongPassword -> return PasswordSetupResult.WrongPassword
            is KeyboxUnlockOutcome.Corrupted -> return PasswordSetupResult.Failed
            KeyboxUnlockOutcome.Missing -> return PasswordSetupResult.Success
        }
    }

    /** Changes the PIN (accepts the legacy password as `current`); the app must already be unlocked. */
    suspend fun changePassword(
        current: CharArray,
        next: CharArray,
    ): PasswordSetupResult {
        val currentBytes = encodeUtf8(current)
        val nextBytes = encodeUtf8(next)
        return try {
            withContext(Dispatchers.Default) {
                when (val outcome = keybox.unlock(currentBytes)) {
                    is KeyboxUnlockOutcome.Success -> {
                        outcome.session.changePassword(nextBytes)

                        biometricGate.clear()

                        appLockManager.adoptFreshSession(outcome.session)
                        PasswordSetupResult.Success
                    }
                    is KeyboxUnlockOutcome.WrongPassword -> PasswordSetupResult.WrongPassword
                    is KeyboxUnlockOutcome.Corrupted -> PasswordSetupResult.Failed
                    KeyboxUnlockOutcome.Missing -> PasswordSetupResult.Failed
                }
            }
        } finally {
            currentBytes.zeroize()
            nextBytes.zeroize()
        }
    }

    fun reconcileKeySources() {
        if (keybox.exists() && keystoreSource.exists()) {
            keystoreSource.delete()
        }
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) {
            java.util.Arrays.fill(buffer.array(), 0)
        }
        return bytes
    }
}
