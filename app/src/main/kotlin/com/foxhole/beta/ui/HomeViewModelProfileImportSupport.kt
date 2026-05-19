package com.foxhole.beta.ui

import android.app.Application
import android.content.ClipData
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.data.InsecureTlsImportWarning
import com.foxhole.beta.core.data.InsecureTlsProfileConsentRequiredException
import com.foxhole.beta.core.data.ProfileImportPayloadTooLargeException
import com.foxhole.beta.core.data.requireLocalProfileImportWithinLimit
import kotlinx.coroutines.launch

internal fun HomeViewModel.onPasteFromClipboardInternal() {
    val text = clipboard.primaryClip?.firstTextItem(getApplication())
    if (text.isNullOrBlank()) {
        snackbars.tryEmit(infoBanner(R.string.clipboard_empty))
        return
    }
    importProfileRaw(text)
}

internal fun HomeViewModel.importProfileRawInternal(value: String) {
    if (value.isBlank()) {
        snackbars.tryEmit(errorBanner(R.string.profile_import_failed))
        return
    }
    val boundedValue =
        try {
            requireLocalProfileImportWithinLimit(value)
        } catch (_: ProfileImportPayloadTooLargeException) {
            snackbars.tryEmit(errorBanner(R.string.profile_import_too_large))
            return
        }
    importRaw(boundedValue)
}

internal fun HomeViewModel.importRawInternal(value: String) {
    viewModelScope.launch {
        val warning = container.profileRepository.rawInputInsecureTlsWarning(value)
        if (warning != null) {
            insecureTlsImportWarningMutable.value = warning.toUiState(rawInput = value)
            return@launch
        }
        importRawWithInsecureTlsDecision(
            value = value,
            allowInsecureTlsForProfile = false,
            excludeInsecureTlsOptions = false,
        )
    }
}

internal fun HomeViewModel.confirmInsecureTlsImportInternal(excludeInsecureTlsOptions: Boolean = false) {
    val pending = insecureTlsImportWarningMutable.value ?: return
    insecureTlsImportWarningMutable.value = null
    viewModelScope.launch {
        val refreshProfileId = pending.profileId
        if (refreshProfileId != null) {
            refreshProfileWithInsecureTlsDecision(
                profileId = refreshProfileId,
                allowInsecureTlsForProfile = !excludeInsecureTlsOptions,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        } else {
            importRawWithInsecureTlsDecision(
                value = pending.rawInput,
                allowInsecureTlsForProfile = !excludeInsecureTlsOptions,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        }
    }
}

internal fun HomeViewModel.dismissInsecureTlsImportWarningInternal() {
    insecureTlsImportWarningMutable.value = null
}

private suspend fun HomeViewModel.importRawWithInsecureTlsDecision(
    value: String,
    allowInsecureTlsForProfile: Boolean,
    excludeInsecureTlsOptions: Boolean,
) {
    runCatching {
        container.profileRepository.importProfile(
            rawInput = value,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        )
    }.onSuccess { imported ->
        container.connectionController.setActiveProfile(imported.id)
        startupActiveProfileMutable.value = imported.copy(isActive = true)
        emitSuccess(getApplication<Application>().getString(R.string.profile_imported))
    }.onFailure { error ->
        if (error is InsecureTlsProfileConsentRequiredException) {
            val warning = error.warning ?: container.profileRepository.rawInputInsecureTlsWarning(value)
            insecureTlsImportWarningMutable.value =
                warning?.toUiState(rawInput = value) ?: InsecureTlsImportWarningState(rawInput = value)
        } else {
            handleProfileImportFailure(value, error)
        }
    }
}

private fun InsecureTlsImportWarning.toUiState(rawInput: String): InsecureTlsImportWarningState =
    InsecureTlsImportWarningState(
        rawInput = rawInput,
        protocolLabels = issues.map { issue -> issue.protocolLabel },
        canExcludeAndApply = canExcludeAndApply,
    )

private fun InsecureTlsImportWarning.toRefreshUiState(profileId: Long): InsecureTlsImportWarningState =
    InsecureTlsImportWarningState(
        rawInput = "",
        profileId = profileId,
        protocolLabels = issues.map { issue -> issue.protocolLabel },
        canExcludeAndApply = canExcludeAndApply,
    )

internal fun HomeViewModel.profileImportFailureMessageInternal(
    rawInput: String,
    throwable: Throwable,
): String {
    val app = getApplication<Application>()
    val message = throwable.message.orEmpty()
    val trimmed = rawInput.trim()
    // Keep subscription-link failures readable instead of surfacing parser internals.
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            message.contains("only https subscriptions are allowed", ignoreCase = true) ->
            app.getString(R.string.profile_import_https_only)

        trimmed.startsWith("https://", ignoreCase = true) &&
            message.contains("unsupported subscription payload", ignoreCase = true) ->
            app.getString(R.string.profile_import_subscription_invalid)

        throwable is InsecureTlsProfileConsentRequiredException ||
            message.contains("insecure tls is not allowed", ignoreCase = true) ->
            app.getString(R.string.profile_import_insecure_tls_required)

        throwable is ProfileImportPayloadTooLargeException ->
            app.getString(R.string.profile_import_too_large)

        else -> throwable.message ?: app.getString(R.string.profile_import_failed)
    }
}

internal suspend fun HomeViewModel.refreshProfileAndMaybeReconnectInternal(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean = false,
    allowInsecureTlsForProfile: Boolean = false,
) {
    val refreshedProfile =
        container.connectionController.refreshProfile(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )
    val reconnected = reconnectProfileIfRequested(profileId, reconnectNow = true)
    val app = getApplication<Application>()
    val message =
        if (reconnected) {
            app.getString(R.string.profile_refreshed_reconnecting)
        } else {
            app.getString(R.string.profile_refreshed)
        }
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh in-app notification emitted profileId=$profileId name=${refreshedProfile.name} reconnecting=$reconnected",
    )
    emitSuccess(message)
}

internal suspend fun HomeViewModel.refreshProfileWithInsecureTlsDecision(
    profileId: Long,
    allowInsecureTlsForProfile: Boolean,
    excludeInsecureTlsOptions: Boolean,
) {
    runCatching {
        refreshProfileAndMaybeReconnect(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )
    }.onFailure { error ->
        if (error is InsecureTlsProfileConsentRequiredException) {
            insecureTlsImportWarningMutable.value =
                error.warning?.toRefreshUiState(profileId)
                    ?: InsecureTlsImportWarningState(rawInput = "", profileId = profileId)
        } else {
            handleProfileRefreshFailure(profileId, error)
        }
    }
}

internal suspend fun HomeViewModel.handleProfileRefreshFailureInternal(
    profileId: Long,
    throwable: Throwable,
) {
    val app = getApplication<Application>()
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh failed profileId=$profileId: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
    )
    emitError(app.getString(R.string.profile_refresh_failed))
}

internal suspend fun HomeViewModel.handleProfileImportFailureInternal(
    rawInput: String,
    throwable: Throwable,
) {
    emitError(profileImportFailureMessage(rawInput, throwable))
}

private fun ClipData.firstTextItem(application: Application): String? =
    if (itemCount > 0) {
        getItemAt(0).coerceToText(application).toString()
    } else {
        null
    }
