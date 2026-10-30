package com.foxhole.guard.core.backup

import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.ProfileEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal interface ProfileBackupCheckpointAccess {
    suspend fun profiles(): List<ProfileEntity>
    suspend fun readSecret(ref: String): StoredProfileSecret?
    suspend fun writeSecret(ref: String, secret: StoredProfileSecret)
    suspend fun replaceProfiles(rows: List<ProfileEntity>)
    suspend fun cleanupSecret(ref: String)
}

internal suspend fun captureProfileCheckpoint(access: ProfileBackupCheckpointAccess): ProfileBackupRestoreCheckpoint {
    val entities = access.profiles()
    val secrets = entities.associate { entity ->
        entity.secretRef to (
            access.readSecret(entity.secretRef)
                ?: error("profile secret is unreadable for profile ${entity.id}")
            )
    }
    return ProfileBackupRestoreCheckpoint(entities, secrets)
}

internal class BackupRestoreRollbackFailures(val failures: List<Throwable>) :
    Exception("backup rollback settlement failed")

internal suspend fun restoreProfileCheckpointSettled(
    access: ProfileBackupCheckpointAccess,
    checkpoint: ProfileBackupRestoreCheckpoint,
    touchedRefs: Set<String>,
    restoreSettings: suspend () -> Unit,
) {
    val failures = mutableListOf<Throwable>()
    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) { failures += error }
    }
    try {
        withContext(NonCancellable) {
            checkpoint.secrets.forEach { (ref, secret) -> attempt { access.writeSecret(ref, secret) } }
            attempt { access.replaceProfiles(checkpoint.entities) }
            (touchedRefs - checkpoint.secrets.keys).forEach { ref -> attempt { access.cleanupSecret(ref) } }
            attempt(restoreSettings)
        }
    } catch (handoffFailure: Throwable) {
        if (failures.none { it === handoffFailure }) failures += handoffFailure
    }
    if (failures.isNotEmpty()) throw BackupRestoreRollbackFailures(failures.toList())
}
