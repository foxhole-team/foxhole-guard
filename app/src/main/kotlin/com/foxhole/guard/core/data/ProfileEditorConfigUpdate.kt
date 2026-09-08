package com.foxhole.guard.core.data

import com.foxhole.core.model.Profile
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal data class ProfileEditorConfigUpdate(
    val protocolOptionId: String?,
    val editedJson: String,
)

internal suspend fun ProfileRepository.updateProfileEditor(
    profileId: Long,
    profileName: String?,
    edits: List<ProfileEditorConfigUpdate>,
    expectedRevision: String? = null,
    onSavedRevision: (String) -> Unit = {},
): Profile {
    val trimmedName = profileName?.trim()
    require(trimmedName == null || trimmedName.isNotBlank()) { "profile name is blank" }
    val updated = secretMutationMutex.withLock {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val targetName = trimmedName ?: entity.name
        val originalSecret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        requireProfileEditorRevision(originalSecret, entity.name, expectedRevision)
        val settings = settingsRepository.current()
        val effectiveAllowInsecureTls =
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = settings.expert.allowInsecureTls,
                secret = originalSecret,
            )
        val savedSecret = persistValidatedProfileEditorChanges(
            originalSecret = originalSecret,
            edits = edits,
            json = json,
            sanitizer = { raw ->
                withContext(Dispatchers.IO) {
                    parser.sanitizeResolvedConfig(
                        raw = raw,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowInsecureTls = effectiveAllowInsecureTls,
                    )
                }
            },
            writeSecret = { secret -> secretStore.write(entity.secretRef, secret) },
            updateMetadata = {
                if (targetName != entity.name) dao.updateName(profileId, targetName)
            },
            onRollbackFailure = { rollbackError ->
                diagnosticsLogger.recordFailure(
                    "profile",
                    "profile editor secret rollback failed: " +
                        (rollbackError.message ?: rollbackError.javaClass.simpleName),
                )
            },
        )
        onSavedRevision(profileEditorRevision(savedSecret, targetName))
        requireProfile(profileId)
    }
    if (updated.isActive) persistCachedActiveProfile(updated)
    diagnosticsLogger.record("profile", "profile editor changes validated and saved")
    return updated
}

internal suspend fun persistValidatedProfileEditorChanges(
    originalSecret: StoredProfileSecret,
    edits: List<ProfileEditorConfigUpdate>,
    json: Json,
    sanitizer: suspend (String) -> String,
    writeSecret: suspend (StoredProfileSecret) -> Unit,
    updateMetadata: suspend () -> Unit,
    onRollbackFailure: (Throwable) -> Unit,
): StoredProfileSecret {
    // map() must finish for every edit before nextSecret is built or either store is touched.
    val sanitizedEdits =
        edits.map { edit -> edit.copy(editedJson = sanitizer(edit.editedJson)) }
    val nextSecret =
        sanitizedEdits
            .fold(originalSecret) { secret, edit ->
                secret.withUpdatedResolvedConfigJson(
                    sanitized = edit.editedJson,
                    protocolOptionIdOverride = edit.protocolOptionId,
                )
            }.withInsecureTlsMarkers(json)
    val secretChanged = nextSecret != originalSecret
    withContext(NonCancellable) {
        try {
            if (secretChanged) writeSecret(nextSecret)
            updateMetadata()
        } catch (error: Throwable) {
            if (secretChanged) {
                runCatching { writeSecret(originalSecret) }.onFailure(onRollbackFailure)
            }
            throw error
        }
    }
    return nextSecret
}

internal fun profileEditorRevision(secret: StoredProfileSecret, name: String): String {
    val bytes = (name + "\u0000" + Json.encodeToString(StoredProfileSecret.serializer(), secret)).encodeToByteArray()
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } finally {
        bytes.fill(0)
    }
}

internal fun requireProfileEditorRevision(secret: StoredProfileSecret, name: String, expected: String?) {
    require(expected == null || expected == profileEditorRevision(secret, name)) {
        "profile changed; reload the editor before saving"
    }
}

internal suspend fun ProfileRepository.editorRevision(profileId: Long): String = secretMutationMutex.withLock {
    val entity = dao.getById(profileId) ?: error("profile not found")
    val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
    profileEditorRevision(secret, entity.name)
}
