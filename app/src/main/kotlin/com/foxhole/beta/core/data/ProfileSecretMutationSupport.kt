package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.StoredProfileSecret

internal data class StagedProfileSecretWrite(
    val secretRef: String,
    val value: StoredProfileSecret,
)

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
