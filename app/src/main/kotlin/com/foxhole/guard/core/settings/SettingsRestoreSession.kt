package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.guard.core.data.ProfileMutationCoordinator
import com.foxhole.guard.core.data.ProfileMutationLease
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SettingsRestoreSession(
    val checkpoint: Settings,
    private val requireOwner: () -> Unit,
    private val write: suspend (Settings) -> Unit,
    private val publish: (Settings) -> Unit,
) {
    private var working = checkpoint
    private var active = true

    fun current(): Settings {
        checkActive()
        return working
    }

    fun update(transform: (Settings) -> Settings) {
        checkActive()
        working = withAdvancedQuarantinePolicyRevision(working, transform(working).normalized())
    }

    fun restoreWorking(value: Settings) {
        checkActive()
        working = value
    }

    suspend fun persist() {
        checkActive()
        write(working)
    }

    fun publishCommitted() {
        checkActive()
        publish(working)
    }

    suspend fun rollback() {
        checkActive()
        write(checkpoint)
        working = checkpoint
        publish(checkpoint)
    }

    fun close() {
        active = false
    }

    private fun checkActive() {
        check(active) { "restore settings owner is no longer current" }
        requireOwner()
    }
}

internal suspend fun <T> readSettingsSnapshotUnderOwner(
    lock: Mutex,
    coordinator: ProfileMutationCoordinator,
    owner: ProfileMutationLease,
    current: () -> Settings,
    block: suspend (Settings) -> T,
): T = lock.withLock(owner) {
    owner.requireOwner(coordinator)
    block(current())
}
