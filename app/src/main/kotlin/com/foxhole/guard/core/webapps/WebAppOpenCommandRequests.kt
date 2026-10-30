package com.foxhole.guard.core.webapps

import com.foxhole.guard.core.security.LockState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal data class WebAppOpenCommand(
    val token: String,
    val appId: Long,
    val issuedAt: Long,
) {
    fun remainingLifetime(now: Long): Long =
        if (now < issuedAt) 0L else (EXPIRY_MS - (now - issuedAt)).coerceAtLeast(0L)

    companion object {
        const val EXPIRY_MS = 10 * 60 * 1_000L
    }
}

internal class WebAppOpenCommandRequests(
    private val now: () -> Long,
) {
    private var pending: WebAppOpenCommand? = null

    @Synchronized
    fun issue(appId: Long): WebAppOpenCommand {
        require(appId > 0L)
        return WebAppOpenCommand(UUID.randomUUID().toString(), appId, now()).also { pending = it }
    }

    @Synchronized
    fun consume(token: String?, appId: Long): WebAppOpenCommand? {
        val command = pending ?: return null
        if (command.remainingLifetime(now()) == 0L) {
            pending = null
            return null
        }
        if (command.token != token || command.appId != appId) return null
        pending = null
        return command
    }
}

internal suspend fun awaitWebAppCommandUnlock(
    command: WebAppOpenCommand,
    lockState: StateFlow<LockState>,
    now: () -> Long,
    isCurrent: () -> Boolean,
): Boolean {
    val remaining = command.remainingLifetime(now())
    if (remaining == 0L || !isCurrent()) return false
    return withTimeoutOrNull(remaining) {
        lockState.first { it == LockState.UNLOCKED }
        isCurrent() && lockState.value == LockState.UNLOCKED && command.remainingLifetime(now()) > 0L
    } == true
}
