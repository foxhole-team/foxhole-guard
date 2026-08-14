package com.foxhole.guard.ui

import android.app.Application
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.model.storedProtocolHintOrNull
import com.foxhole.guard.R
import com.foxhole.guard.core.data.ProfileEditorConfigUpdate
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.allowsInsecureTlsForStoredProfileRuntime
import com.foxhole.guard.core.data.createDraftProfile
import com.foxhole.guard.core.data.requireLocalProfileImportWithinLimit
import com.foxhole.guard.core.data.updateProfileEditor
import com.foxhole.guard.core.data.withInsecureTlsMarkers
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.sync.withLock

/**
 * The edit surface behind the structured profile editor (`ui/cli/profiles/CliProfileEditor*`).
 *
 * Field edits are submitted as one repository batch: every changed protocol is validated before
 * the encrypted profile secret is written, so a later invalid card cannot partially save an earlier
 * one. Changing the *set* of protocols is done here against the profile secret, mirroring
 * `ProfileRepository.setProfileProtocolOptionEnabled`: serialize on the repository's secret mutex,
 * re-write the entity's protocolHint afterwards purely to fire Room's invalidation (the secret is
 * invisible to `observeProfiles()`), and keep the "at least one protocol, selection never dangling"
 * invariant the selection helpers rely on.
 */
internal data class ProfileProtocolConfigEdit(
    val protocolOptionId: String?,
    val configJson: String,
)

internal suspend fun HomeViewModel.saveProfileProtocolConfigs(
    profileId: Long,
    edits: List<ProfileProtocolConfigEdit>,
): Boolean {
    if (edits.isEmpty()) {
        return true
    }
    return runCatching {
        container.profileRepository.updateProfileEditor(
            profileId = profileId,
            profileName = null,
            edits = edits.map { edit ->
                ProfileEditorConfigUpdate(
                    protocolOptionId = edit.protocolOptionId,
                    editedJson = edit.configJson,
                )
            },
        )
    }.onSuccess {
        val application = getApplication<Application>()
        if (isLiveProfile(profileId)) {
            emitInfo(application.getString(R.string.reconnect_required))
        } else {
            emitSuccess(application.getString(R.string.profile_config_saved))
        }
    }.onFailure { error ->
        emitError(
            getApplication<Application>().userFacingErrorMessage(error, R.string.profile_config_save_failed),
        )
    }.isSuccess
}

/**
 * Commits the visible editor as one validated action: all protocol configs are sanitized before
 * the encrypted secret is replaced, and the profile name joins the same editor result.
 */
internal suspend fun HomeViewModel.saveProfileEditorChanges(
    profileId: Long,
    profileName: String,
    edits: List<ProfileProtocolConfigEdit>,
): Boolean {
    if (profileName.isBlank()) {
        emitError(getApplication<Application>().getString(R.string.profile_rename_failed))
        return false
    }
    return runCatching {
        container.profileRepository.updateProfileEditor(
            profileId = profileId,
            profileName = profileName,
            edits = edits.map { edit ->
                ProfileEditorConfigUpdate(
                    protocolOptionId = edit.protocolOptionId,
                    editedJson = edit.configJson,
                )
            },
        )
    }.onSuccess { updated ->
        if (updated.isActive) {
            startupActiveProfileMutable.value = updated
        }
        val application = getApplication<Application>()
        if (isLiveProfile(profileId)) {
            emitInfo(application.getString(R.string.reconnect_required))
        } else {
            emitSuccess(application.getString(R.string.profile_config_saved))
        }
    }.onFailure { error ->
        emitError(
            getApplication<Application>().userFacingErrorMessage(error, R.string.profile_config_save_failed),
        )
    }.isSuccess
}

/**
 * Validates and saves text from the full-screen manual editor in one transaction.
 *
 * Stored FoxCore JSON takes the lossless sanitizer path. If the text is another supported local
 * format (a share link or WireGuard text), the ordinary importer normalizes it first. Subscriptions
 * and multi-profile bundles remain import actions rather than silently replacing one protocol.
 */
internal suspend fun HomeViewModel.saveManualProfileConfig(
    profileId: Long,
    profileName: String,
    protocolOptionId: String?,
    rawText: String,
): Boolean {
    val normalized =
        runCatching {
            container.profileRepository.normalizeManualProfileConfig(
                profileId = profileId,
                rawText = rawText,
            )
        }.onFailure { error ->
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    error,
                    R.string.profile_config_save_failed,
                ),
            )
        }.getOrNull() ?: return false
    return saveProfileEditorChanges(
        profileId = profileId,
        profileName = profileName,
        edits = listOf(ProfileProtocolConfigEdit(protocolOptionId, normalized)),
    )
}

/**
 * Registers one more protocol on the profile. A profile that never had options is converted on the
 * way in: its current resolved config becomes option #1 so the new protocol joins a real list
 * instead of overwriting the profile. The config is sanitized through the importer first, so an
 * invalid paste fails here rather than at connect time.
 */
internal suspend fun HomeViewModel.addProfileProtocolOption(
    profileId: Long,
    displayName: String,
    protocolHint: ProtocolHint,
    configJson: String,
): Boolean =
    runCatching {
        val repository = container.profileRepository
        val entity = repository.dao.getById(profileId) ?: error("profile not found")
        val secret = repository.secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val sanitized = repository.sanitizeForProfile(secret, configJson)
        val existing = secret.materializedProtocolOptions(entity.protocolHint)
        val option =
            StoredProfileProtocolOption(
                id = existing.freeProtocolOptionId(protocolHint),
                displayName = displayName.trim().ifBlank { protocolHint.name.lowercase() },
                protocolHint = protocolHint,
                normalizedConfigJson = sanitized,
            )
        val updated =
            secret
                .copy(
                    protocolOptions = existing + option,
                    selectedProtocolOptionId = secret.selectedProtocolOptionId ?: existing.first().id,
                ).withInsecureTlsMarkers(repository.json)
        repository.persistProtocolOptions(profileId, entity.secretRef, updated, entity.protocolHint)
    }.onSuccess {
        emitSuccess(getApplication<Application>().getString(R.string.cli_prof_edit_added))
    }.onFailure { error ->
        emitError(getApplication<Application>().userFacingErrorMessage(error, R.string.cli_prof_edit_add_failed))
    }.isSuccess

/**
 * Drops one protocol. Refuses the last one (the same floor `protocolOptionEnabledUpdate` enforces),
 * and when the removed option was the selected one the selection moves to the first enabled
 * survivor together with the mirrored top-level config.
 */
internal suspend fun HomeViewModel.removeProfileProtocolOption(
    profileId: Long,
    optionId: String,
): Boolean =
    runCatching {
        val repository = container.profileRepository
        val entity = repository.dao.getById(profileId) ?: error("profile not found")
        val secret = repository.secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val remaining = secret.protocolOptions.filterNot { option -> option.id == optionId }
        require(remaining.isNotEmpty() && remaining.size < secret.protocolOptions.size) {
            getApplication<Application>().getString(R.string.cli_prof_edit_last_protocol)
        }
        val reselected =
            if (secret.selectedProtocolOptionId == optionId) {
                remaining.firstOrNull(StoredProfileProtocolOption::enabled) ?: remaining.first()
            } else {
                null
            }
        val updated =
            secret
                .copy(
                    protocolOptions = remaining,
                    selectedProtocolOptionId = reselected?.id ?: secret.selectedProtocolOptionId,
                    resolvedConfigJson = reselected?.normalizedConfigJson ?: secret.resolvedConfigJson,
                ).withInsecureTlsMarkers(repository.json)
        repository.persistProtocolOptions(
            profileId = profileId,
            secretRef = entity.secretRef,
            secret = updated,
            fallbackProtocolHint = reselected?.protocolHint?.name ?: entity.protocolHint,
        )
    }.onSuccess {
        emitSuccess(getApplication<Application>().getString(R.string.cli_prof_edit_removed))
    }.onFailure { error ->
        emitError(getApplication<Application>().userFacingErrorMessage(error, R.string.cli_prof_edit_remove_failed))
    }.isSuccess

/**
 * Creates a new profile from a blank protocol template. An empty server is a valid editor draft but
 * deliberately invalid import input, so this path persists a local draft directly and leaves the
 * ordinary import parser strict. Returns the created profile, or null after reporting the failure.
 *
 * The direct draft path intentionally has no import deduplication. Every blank template of a given
 * type is the same bytes, so the duplicate guard used to hand the first profile back instead of
 * creating a fresh editor draft. A template is not an imported source; there is nothing to be a
 * duplicate of.
 */
internal suspend fun HomeViewModel.createProfileFromTemplate(type: String): Profile? =
    runCatching {
        val normalizedType = type.trim().lowercase()
        val protocolHint = com.foxhole.guard.ui.cli.profiles.cliProtocolHintForType(normalizedType)
        require(protocolHint != ProtocolHint.CUSTOM_CONFIG) { "unsupported profile template" }
        val config =
            com.foxhole.guard.ui.cli.profiles.cliEditorJson.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                com.foxhole.guard.ui.cli.profiles.cliNewProtocolConfig(
                    template = kotlinx.serialization.json.JsonObject(emptyMap()),
                    outbound = com.foxhole.guard.ui.cli.profiles.cliBlankOutbound(normalizedType),
                ),
            )
        container.profileRepository.createDraftProfile(
            name = normalizedType,
            protocolHint = protocolHint,
            configJson = config,
        )
    }.onFailure { error ->
        emitError(getApplication<Application>().userFacingErrorMessage(error, R.string.cli_prof_edit_add_failed))
    }.getOrNull()

/** Parses a pasted share URI into an addable protocol, or fails with the importer's own message. */
internal suspend fun HomeViewModel.parseProtocolShareUri(shareUri: String): ParsedProtocolShareUri {
    val repository = container.profileRepository
    val expert = repository.settingsRepository.current().expert
    val parsed =
        repository.parser.parseUserInput(
            input = shareUri.trim(),
            allowPrivateOutboundHosts = expert.allowPrivateOutboundHosts,
            allowInsecureTls = expert.allowInsecureTls,
        )
    val configJson =
        parsed.protocolOptions.firstOrNull()?.normalizedConfigJson
            ?: parsed.normalizedConfigJson
            ?: error("share uri carries no config")
    return ParsedProtocolShareUri(
        displayName = parsed.displayName,
        protocolHint = parsed.protocolOptions.firstOrNull()?.protocolHint ?: parsed.protocolHint,
        configJson = configJson,
    )
}

internal data class ParsedProtocolShareUri(
    val displayName: String,
    val protocolHint: ProtocolHint,
    val configJson: String,
)

private fun HomeViewModel.isLiveProfile(profileId: Long): Boolean =
    controlUiState.value.activeProfile?.id == profileId &&
        controlUiState.value.connection.state in ACTIVE_CONNECTION_STATES

private suspend fun ProfileRepository.normalizeManualProfileConfig(
    profileId: Long,
    rawText: String,
): String {
    val raw = requireLocalProfileImportWithinLimit(rawText.trim())
    require(raw.isNotEmpty()) { "profile config is empty" }
    val entity = dao.getById(profileId) ?: error("profile not found")
    val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
    val expert = settingsRepository.current().expert
    val allowInsecureTls =
        allowsInsecureTlsForStoredProfileRuntime(
            allowInsecureTlsGlobally = expert.allowInsecureTls,
            secret = secret,
        )
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            parser.sanitizeResolvedConfig(
                raw = raw,
                allowPrivateOutboundHosts = expert.allowPrivateOutboundHosts,
                allowInsecureTls = allowInsecureTls,
            )
        }.getOrElse {
            val parsed =
                parser.parseUserInput(
                    input = raw,
                    allowPrivateOutboundHosts = expert.allowPrivateOutboundHosts,
                    allowInsecureTls = allowInsecureTls,
                )
            require(parsed.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
                "subscription URLs must be imported"
            }
            require(parsed.protocolOptions.size <= 1) { "multi-protocol text must be imported" }
            parsed.protocolOptions.singleOrNull()?.normalizedConfigJson
                ?: parsed.normalizedConfigJson
                ?: error("profile text carries no VPN configuration")
        }
    }
}

private suspend fun ProfileRepository.sanitizeForProfile(
    secret: StoredProfileSecret,
    configJson: String,
): String {
    val expert = settingsRepository.current().expert
    return parser.sanitizeResolvedConfig(
        raw = configJson,
        allowPrivateOutboundHosts = expert.allowPrivateOutboundHosts,
        allowInsecureTls =
        allowsInsecureTlsForStoredProfileRuntime(
            allowInsecureTlsGlobally = expert.allowInsecureTls,
            secret = secret,
        ),
    )
}

/** The option list of a profile that may still be single-config: its config becomes option #1. */
private fun StoredProfileSecret.materializedProtocolOptions(
    protocolHint: String,
): List<StoredProfileProtocolOption> =
    protocolOptions.ifEmpty {
        val hint = storedProtocolHintOrNull(protocolHint) ?: ProtocolHint.UNKNOWN
        listOf(
            StoredProfileProtocolOption(
                id = hint.name.lowercase(),
                displayName = hint.name.lowercase(),
                protocolHint = hint,
                normalizedConfigJson = resolvedConfigJson ?: error("profile config is missing"),
            ),
        )
    }

private fun List<StoredProfileProtocolOption>.freeProtocolOptionId(protocolHint: ProtocolHint): String {
    val base = protocolHint.name.lowercase()
    val taken = map(StoredProfileProtocolOption::id).toSet()
    if (base !in taken) {
        return base
    }
    return generateSequence(2) { it + 1 }.map { index -> "${base}_$index" }.first { it !in taken }
}

private suspend fun ProfileRepository.persistProtocolOptions(
    profileId: Long,
    secretRef: String,
    secret: StoredProfileSecret,
    fallbackProtocolHint: String,
) {
    secretMutationMutex.withLock { secretStore.write(secretRef, secret) }
    // The enabled/option list lives in the secret, which observeProfiles() never reads; re-writing
    // the entity's protocolHint is what makes Room re-emit so the editor and the list refresh.
    dao.updateProtocolHint(profileId, fallbackProtocolHint)
    diagnosticsLogger.record("profile", "profile protocol options changed")
}
