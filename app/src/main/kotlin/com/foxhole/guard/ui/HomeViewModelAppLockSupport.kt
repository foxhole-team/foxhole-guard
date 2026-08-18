package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.guard.R
import com.foxhole.guard.core.security.LockState
import com.foxhole.guard.core.security.UnlockOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Arrays

internal fun HomeViewModel.startAuthAttemptNoticeSupervision() {
    viewModelScope.launch {
        appLockManager.pendingAuthNotice.collect { notice ->
            if (notice == null || notice.failedAttempts <= 0) {
                return@collect
            }
            appLockManager.consumeAuthNotice()
            container.settingsRepository.hydrated.first { hydrated -> hydrated }
            if (container.settingsRepository.settings.value.appLock.authAttemptNoticeEnabled) {
                val message =
                    getApplication<android.app.Application>().resources.getQuantityString(
                        R.plurals.auth_attempt_notice_banner,
                        notice.failedAttempts,
                        notice.failedAttempts,
                    )
                snackbars.tryEmit(FoxholeBannerEvent(message = message, tone = FoxholeBannerTone.ERROR))
            }
        }
    }
}

internal fun HomeViewModel.startGuardJournalCheckpointSupervision() {
    viewModelScope.launch {
        var wasUnlocked = appLockManager.currentLockState() == LockState.UNLOCKED
        appLockManager.lockState.collect { state ->
            val nowUnlocked = state == LockState.UNLOCKED
            if (nowUnlocked && !wasUnlocked) {
                withContext(Dispatchers.IO) {
                    runCatching { securityComponents.verifyGuardJournal() }
                }
            }
            wasUnlocked = nowUnlocked
        }
    }
}

fun HomeViewModel.initialUnlockBackoffSeconds(): Int =
    ((appLockManager.initialUnlockBackoffMs() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()

private const val MILLIS_PER_SECOND = 1_000L

internal suspend fun HomeViewModel.unlockWithPassword(password: CharArray): UnlockOutcome {
    val passwordBytes = encodeCharsToUtf8(password)
    return try {
        // Argon2id is intentionally expensive: never run it on the main thread.
        withContext(Dispatchers.Default) {
            appLockManager.unlockWithPassword(passwordBytes)
        }
    } finally {
        Arrays.fill(passwordBytes, 0)
    }
}

private fun encodeCharsToUtf8(chars: CharArray): ByteArray {
    val buffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) {
        Arrays.fill(buffer.array(), 0)
    }
    return bytes
}
