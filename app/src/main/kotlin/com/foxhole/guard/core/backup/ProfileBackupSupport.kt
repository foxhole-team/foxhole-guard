package com.foxhole.guard.core.backup

import com.foxhole.core.model.ProfileSourceType
import com.foxhole.guard.core.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Bridges the profile store into the backup document and back. Export reads the Room rows plus
// their encrypted secrets; restore replays each payload through the ordinary import pipeline so
// every parser/validation/duplicate rule keeps applying.

suspend fun ProfileRepository.collectBackupProfiles(): List<BackupProfilePayload> =
    withContext(Dispatchers.IO) {
        dao.getAllProfiles().mapNotNull { entity ->
            val secret = secretStore.read(entity.secretRef) ?: return@mapNotNull null
            val optionHints = secret.protocolOptions.map { option -> option.protocolHint.name }
            BackupProfilePayload(
                name = entity.name,
                sourceType = entity.sourceType,
                protocolHints = optionHints.ifEmpty { listOf(entity.protocolHint) },
                selectedProtocolOptionId = secret.selectedProtocolOptionId,
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
    val error: String? = null,
)

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
            BackupProfileRestoreResult(name = payload.name, restored = false, error = "empty payload")
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
                payload.selectedProtocolOptionId?.let { optionId ->
                    runCatching { selectProfileProtocolOption(profile.id, optionId) }
                }
                BackupProfileRestoreResult(name = payload.name, restored = true)
            }.getOrElse { error ->
                BackupProfileRestoreResult(
                    name = payload.name,
                    restored = false,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
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
