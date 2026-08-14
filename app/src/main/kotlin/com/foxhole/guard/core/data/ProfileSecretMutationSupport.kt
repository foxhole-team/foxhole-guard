package com.foxhole.guard.core.data

import kotlinx.coroutines.sync.withLock

internal suspend fun <T> executeSecretFirstMutation(
    secretStore: ProfileSecretStore,
    stagedWrites: List<StagedProfileSecretWrite>,
    cleanupSecretRefsAfterSuccess: List<String> = emptyList(),
    onCleanupFailure: (secretRef: String, error: Throwable) -> Unit = { _, _ -> },
    mutation: suspend () -> T,
): T {
    val stagedSecretRefs = stagedWrites.map(StagedProfileSecretWrite::secretRef).toSet()
    val writtenSecretRefs = mutableListOf<String>()
    try {
        stagedWrites.forEach { write ->
            secretStore.write(write.secretRef, write.value)
            writtenSecretRefs += write.secretRef
        }
        val result = mutation()
        cleanupSecretRefsAfterSuccess
            .distinct()
            .filterNot(stagedSecretRefs::contains)
            .forEach { secretRef ->
                cleanupProfileSecretRef(
                    secretStore = secretStore,
                    secretRef = secretRef,
                    onCleanupFailure = onCleanupFailure,
                )
            }
        return result
    } catch (error: Throwable) {
        writtenSecretRefs
            .asReversed()
            .forEach { secretRef ->
                cleanupProfileSecretRef(
                    secretStore = secretStore,
                    secretRef = secretRef,
                    onCleanupFailure = onCleanupFailure,
                )
            }
        throw error
    }
}

private suspend fun cleanupProfileSecretRef(
    secretStore: ProfileSecretStore,
    secretRef: String,
    onCleanupFailure: (secretRef: String, error: Throwable) -> Unit,
) {
    runCatching { secretStore.delete(secretRef) }
        .fold(
            onSuccess = { deleted ->
                if (!deleted) {
                    onCleanupFailure(secretRef, IllegalStateException("profile secret cleanup returned false"))
                }
            },
            onFailure = { error ->
                onCleanupFailure(secretRef, error)
            },
        )
}

suspend fun ProfileRepository.cleanupOrphanProfileSecrets(): Int {
    return secretMutationMutex.withLock {
        val activeSecretRefs = dao.getAllProfiles().map(ProfileEntity::secretRef).toSet()
        val deletedCount = secretStore.deleteOrphans(activeSecretRefs)
        if (deletedCount > 0) {
            diagnosticsLogger.record("profile", "orphan profile secret cleanup removed $deletedCount entries")
        }
        deletedCount
    }
}

internal fun ProfileRepository.recordSecretCleanupFailure(
    secretRef: String,
    error: Throwable,
) {
    diagnosticsLogger.recordFailure(
        "profile",
        "profile secret cleanup failed for $secretRef: ${error.message ?: error.javaClass.simpleName}",
    )
}

internal suspend fun ProfileRepository.cleanupProfileSecret(secretRef: String) {
    runCatching { secretStore.delete(secretRef) }
        .onSuccess { deleted ->
            if (!deleted) {
                recordSecretCleanupFailure(
                    secretRef,
                    IllegalStateException("profile secret cleanup returned false"),
                )
            }
        }.onFailure { error ->
            recordSecretCleanupFailure(secretRef, error)
        }
}
