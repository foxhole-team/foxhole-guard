package com.foxhole.guard.core.backup

import androidx.room.withTransaction
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.ProfileEntity
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.cleanupProfileSecret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Bridges the profile store into the backup document and back. Export reads the Room rows plus
// their encrypted secrets; restore replays each payload through the ordinary import pipeline so
// every parser/validation/duplicate rule keeps applying.

suspend fun ProfileRepository.collectBackupProfiles(): List<BackupProfilePayload> =
    withContext(Dispatchers.IO) {
        dao.getAllProfiles().map { entity ->
            val secret = secretStore.read(entity.secretRef)
                ?: error("profile secret is unreadable for profile ${entity.id}")
            val optionHints = secret.protocolOptions.map { option -> option.protocolHint.name }
            BackupProfilePayload(
                backupId = entity.id,
                name = entity.name,
                sourceType = entity.sourceType,
                protocolHints = optionHints.ifEmpty { listOf(entity.protocolHint) },
                selectedProtocolOptionId = secret.selectedProtocolOptionId,
                protocolOptionEnabled = secret.protocolOptions.associate { option -> option.id to option.enabled },
                isActive = entity.isActive,
                subscriptionUrl = secret.subscriptionUrl,
                rawInput = secret.rawInput,
                resolvedConfigJson = secret.resolvedConfigJson,
                insecureTlsConsentGranted = secret.insecureTlsConsentGranted == true,
            )
        }
    }

data class BackupProfileRestoreResult(
    val name: String,
    val restored: Boolean,
    val backupId: Long? = null,
    val restoredId: Long? = null,
    val error: String? = null,
)

internal data class ProfileBackupRestoreCheckpoint(
    val entities: List<ProfileEntity>,
    val secrets: Map<String, StoredProfileSecret>,
)

internal suspend fun ProfileRepository.captureBackupRestoreCheckpoint(): ProfileBackupRestoreCheckpoint =
    profileImportMutex.withLock {
        secretMutationMutex.withLock {
            val entities = dao.getAllProfiles()
            val secrets =
                entities.associate { entity ->
                    val secret =
                        secretStore.read(entity.secretRef)
                            ?: error("profile secret is unreadable for profile ${entity.id}")
                    entity.secretRef to secret
                }
            ProfileBackupRestoreCheckpoint(entities, secrets)
        }
    }

/** Restores the exact pre-import rows/secrets with the repository's ordinary lock order. */
internal suspend fun ProfileRepository.restoreBackupRestoreCheckpoint(
    checkpoint: ProfileBackupRestoreCheckpoint,
) {
    profileImportMutex.withLock {
        secretMutationMutex.withLock {
            val refsAfterPartialRestore = dao.getAllProfiles().map(ProfileEntity::secretRef).toSet()
            checkpoint.secrets.forEach { (secretRef, secret) -> secretStore.write(secretRef, secret) }
            database.withTransaction {
                dao.deleteAll()
                checkpoint.entities.forEach { entity -> dao.insert(entity) }
            }
            (refsAfterPartialRestore - checkpoint.secrets.keys).forEach { secretRef ->
                cleanupProfileSecret(secretRef)
            }
        }
    }
    persistCachedActiveProfile(
        checkpoint.entities.firstOrNull(ProfileEntity::isActive)?.let { entity ->
            resolveDomainProfile(entity)
        },
    )
}

/** Cancellation is the only non-commit outcome after apply begins; rollback cannot be cancelled. */
internal suspend fun <Checkpoint, Result> executeFailAtomicBackupRestore(
    capture: suspend () -> Checkpoint,
    apply: suspend () -> Result,
    rollback: suspend (Checkpoint) -> Unit,
): Result {
    val checkpoint = capture()
    return try {
        apply()
    } catch (cancelled: CancellationException) {
        val rollbackFailure =
            runCatching {
                withContext(NonCancellable) { rollback(checkpoint) }
            }.exceptionOrNull()
        rollbackFailure?.let(cancelled::addSuppressed)
        throw cancelled
    }
}

/**
 * Replays the backup's profiles through [ProfileRepository.importProfile] from the stored
 * resolved config (offline, exact) with the subscription URL re-attached afterwards so manual
 * refresh keeps working; only legacy payloads without a stored config fall back to a network
 * fetch. Failures are collected per profile — one broken profile must not abort the rest.
 */
suspend fun ProfileRepository.restoreBackupProfiles(
    document: BackupDocument,
): List<BackupProfileRestoreResult> =
    document.profiles.map { payload ->
        val rawInput = payload.restoreRawInput()
        if (rawInput == null) {
            BackupProfileRestoreResult(
                name = payload.name,
                restored = false,
                backupId = payload.backupId,
                error = "empty payload",
            )
        } else {
            val fetchesFromNetwork = rawInput == payload.subscriptionUrl
            runCatching {
                val profile =
                    importProfile(
                        rawInput = rawInput,
                        // A network fetch names the profile from the fetched payload; any offline
                        // restore keeps the backed-up name.
                        preferredName = payload.name.takeUnless { fetchesFromNetwork },
                        allowInsecureTlsForProfile = payload.insecureTlsConsentGranted,
                    )
                if (!fetchesFromNetwork) {
                    payload.subscriptionUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        relinkSubscription(profile.id, url)
                    }
                }
                payload.protocolOptionEnabled
                    .filterValues { enabled -> !enabled }
                    .keys
                    .forEach { optionId ->
                        setProfileProtocolOptionEnabled(profile.id, optionId, false)
                    }
                payload.selectedProtocolOptionId?.let { optionId ->
                    selectProfileProtocolOption(profile.id, optionId)
                }
                BackupProfileRestoreResult(
                    name = payload.name,
                    restored = true,
                    backupId = payload.backupId,
                    restoredId = profile.id,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                BackupProfileRestoreResult(
                    name = payload.name,
                    restored = false,
                    backupId = payload.backupId,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }.also { results ->
        val activeIndex = document.profiles.indexOfFirst(BackupProfilePayload::isActive)
        val activeRestoredId = results.getOrNull(activeIndex)?.restoredId
        if (activeRestoredId != null) {
            setActiveProfile(activeRestoredId)
        }
    }

/**
 * Re-attaches the subscription identity to a profile that was restored offline from its stored
 * resolved config: the secret regains the URL (so refresh re-fetches from the source) and the
 * row regains the SUBSCRIPTION_URL source type. Secret first — a crash between the two writes
 * leaves a refreshable local profile, never a subscription row without a URL.
 */
private suspend fun ProfileRepository.relinkSubscription(
    profileId: Long,
    subscriptionUrl: String,
) {
    val entity = dao.getById(profileId) ?: return
    val secret = secretStore.read(entity.secretRef) ?: return
    if (secret.subscriptionUrl != subscriptionUrl) {
        secretStore.write(entity.secretRef, secret.copy(subscriptionUrl = subscriptionUrl))
    }
    if (entity.sourceType != ProfileSourceType.SUBSCRIPTION_URL.name) {
        dao.updateSourceType(profileId, ProfileSourceType.SUBSCRIPTION_URL.name)
    }
}
