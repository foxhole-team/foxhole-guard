package com.foxhole.guard.ui

import android.app.Application
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.model.storedProtocolHintOrNull
import com.foxhole.guard.R
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.allowsInsecureTlsForStoredProfileRuntime
import com.foxhole.guard.core.data.withInsecureTlsMarkers
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.sync.withLock

/**
 * The edit surface behind the structured profile editor (`ui/cli/profiles/CliProfileEditor*`).
 *
 * Field edits themselves never come through here — they ride the existing
 * `updateResolvedConfig`, one call per changed protocol, batched by [saveProfileProtocolConfigs] so
 * a multi-protocol save reports once. What the repository has no API for is changing the *set* of
 * protocols, so adding and removing an option is done here against the profile secret, mirroring
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
        edits.forEach { edit ->
            container.profileRepository.updateResolvedConfig(profileId, edit.configJson, edit.protocolOptionId)
        }
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
