package com.foxhole.beta.ui

import android.app.Application
import android.content.ClipData
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.data.InsecureTlsImportWarning
import com.foxhole.beta.core.data.InsecureTlsProfileConsentRequiredException
import com.foxhole.beta.core.data.ProfileImportPayloadTooLargeException
import com.foxhole.beta.core.data.requireLocalProfileImportWithinLimit
import kotlinx.coroutines.CancellationException
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
    profileImportInProgressMutable.value = true
    viewModelScope.launch {
        importRawWithInsecureTlsDecision(
            value = value,
            allowInsecureTlsForProfile = false,
            excludeInsecureTlsOptions = false,
            precheckInsecureTlsWarning = true,
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
            profileImportInProgressMutable.value = true
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
    precheckInsecureTlsWarning: Boolean = false,
) {
    profileImportInProgressMutable.value = true
    try {
        if (precheckInsecureTlsWarning) {
            val warning = container.profileRepository.rawInputInsecureTlsWarning(value)
            if (warning != null) {
                insecureTlsImportWarningMutable.value = warning.toUiState(rawInput = value)
                return
            }
        }
        val imported =
            container.profileRepository.importProfile(
                rawInput = value,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        container.connectionController.setActiveProfile(imported.id)
        startupActiveProfileMutable.value = imported.copy(isActive = true)
        emitSuccess(getApplication<Application>().getString(R.string.profile_imported))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (error is InsecureTlsProfileConsentRequiredException) {
            val warning = error.warning ?: container.profileRepository.rawInputInsecureTlsWarning(value)
            insecureTlsImportWarningMutable.value =
                warning?.toUiState(rawInput = value) ?: InsecureTlsImportWarningState(rawInput = value)
        } else {
            handleProfileImportFailure(value, error)
        }
    } finally {
        profileImportInProgressMutable.value = false
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
    val activeRuntime =
        container.connectionController.snapshot.value.profileId == profileId &&
            container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES
    val reloaded =
        activeRuntime &&
            container.connectionController.reload(profileId).also { reloadRequested ->
                if (reloadRequested) {
                    scheduleDashboardRefreshAfterRuntimeReload()
                }
            }
    val app = getApplication<Application>()
    val message = app.getString(R.string.profile_refreshed)
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh in-app notification emitted profileId=$profileId name=${refreshedProfile.name} hotReloadRequested=$reloaded",
    )
    emitSuccess(message)
}

internal suspend fun HomeViewModel.refreshProfileWithInsecureTlsDecision(
    profileId: Long,
    allowInsecureTlsForProfile: Boolean,
    excludeInsecureTlsOptions: Boolean,
    restartActiveRuntime: Boolean = false,
) {
    runCatching {
        if (restartActiveRuntime) {
            refreshProfileAndRestartActiveRuntimeInternal(
                profileId = profileId,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            )
        } else {
            refreshProfileAndMaybeReconnect(
                profileId = profileId,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            )
        }
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

private suspend fun HomeViewModel.refreshProfileAndRestartActiveRuntimeInternal(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean,
    allowInsecureTlsForProfile: Boolean,
) {
    container.connectionController.refreshProfile(
        profileId = profileId,
        excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        allowInsecureTlsForProfile = allowInsecureTlsForProfile,
    )
    val activeRuntime =
        container.connectionController.snapshot.value.profileId == profileId &&
            container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES
    if (activeRuntime) {
        requestReconnect(profileId)
    }
    val messageRes =
        if (activeRuntime) {
            R.string.profile_refreshed_reconnecting
        } else {
            R.string.profile_refreshed
        }
    emitSuccess(getApplication<Application>().getString(messageRes))
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
