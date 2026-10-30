package com.foxhole.guard.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun <T> executeSecretFirstMutation(
    secretStore: ProfileSecretStore,
    stagedWrites: List<StagedProfileSecretWrite>,
    cleanupSecretRefsAfterSuccess: List<String> = emptyList(),
    onCleanupFailure: (secretRef: String, error: Throwable) -> Unit = { _, _ -> },
    mutation: suspend () -> T,
): T {
    val stagedSecretRefs = stagedWrites.map(StagedProfileSecretWrite::secretRef).toSet()
    val writtenSecretRefs = mutableListOf<String>()
    val result = try {
        stagedWrites.forEach { write ->
            secretStore.write(write.secretRef, write.value)
            writtenSecretRefs += write.secretRef
        }
        mutation()
    } catch (error: Throwable) {
        settleProfileSecretCleanup(secretStore, writtenSecretRefs.asReversed(), onCleanupFailure, error)
        throw error
    }
    // A retired-secret failure must not delete newly committed references.
    settleProfileSecretCleanup(
        secretStore,
        cleanupSecretRefsAfterSuccess.distinct().filterNot(stagedSecretRefs::contains),
        onCleanupFailure,
        null,
    )?.let { throw it }
    currentCoroutineContext().ensureActive()
    return result
}

private suspend fun settleProfileSecretCleanup(
    store: ProfileSecretStore,
    refs: List<String>,
    onCleanupFailure: (String, Throwable) -> Unit,
    operationFailure: Throwable?,
): Throwable? {
    var primary = operationFailure
    try {
        withContext(NonCancellable) {
            refs.forEach { ref ->
                try {
                    cleanupProfileSecretRef(store, ref, onCleanupFailure)
                } catch (error: Throwable) {
                    if (primary == null) {
                        primary = error
                    } else if (primary !== error) primary!!.addSuppressed(error)
                }
            }
        }
    } catch (handoffFailure: Throwable) {
        if (primary == null) {
            primary = handoffFailure
        } else if (primary !== handoffFailure) primary!!.addSuppressed(handoffFailure)
    }
    return primary
}

private suspend fun cleanupProfileSecretRef(
    secretStore: ProfileSecretStore,
    secretRef: String,
    onCleanupFailure: (secretRef: String, error: Throwable) -> Unit,
) {
    val failure = try {
        if (secretStore.delete(secretRef)) {
            null
        } else {
            IllegalStateException("profile secret cleanup returned false")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error
    }
    if (failure != null) onCleanupFailure(secretRef, failure)
}

suspend fun ProfileRepository.cleanupOrphanProfileSecrets(): Int = withProfileMutation { owner ->
    owner.secrets(secretMutationMutex) {
        val refs = dao.getAllProfiles().map(ProfileEntity::secretRef).toSet()
        val removed = secretStore.deleteOrphans(refs)
        if (removed > 0) {
            owner.markLocalCommit()
            diagnosticsLogger.record("profile", "orphan profile secret cleanup removed $removed entries")
        }
        removed
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

internal suspend fun ProfileRepository.cleanupProfileSecret(secretRef: String) =
    withProfileMutation { owner ->
        owner.secrets(secretMutationMutex) {
            owner.recordingSecrets(secretStore).delete(secretRef)
            owner.markLocalCommit()
        }
    }
