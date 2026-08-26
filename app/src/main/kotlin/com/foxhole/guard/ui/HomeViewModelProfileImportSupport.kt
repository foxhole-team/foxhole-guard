package com.foxhole.guard.ui
import android.app.Application
import android.content.ClipData
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.guard.R
import com.foxhole.guard.core.data.InsecureTlsImportWarning
import com.foxhole.guard.core.data.InsecureTlsProfileConsentRequiredException
import com.foxhole.guard.core.data.ProfileImportPayloadTooLargeException
import com.foxhole.guard.core.data.ProfileRefreshResult
import com.foxhole.guard.core.data.findProfileMatchingRawImport
import com.foxhole.guard.core.data.rawInputImportPreview
import com.foxhole.guard.core.data.rawInputInsecureTlsWarning
import com.foxhole.guard.core.data.requireLocalProfileImportWithinLimit
import com.foxhole.guard.core.data.subscriptionProtocolsPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun HomeViewModel.onPasteFromClipboard() {
    val text = clipboard.primaryClip?.firstTextItem(getApplication())
    if (text.isNullOrBlank()) {
        snackbars.tryEmit(infoBanner(R.string.clipboard_empty))
        return
    }
    importProfileRaw(text)
}

internal fun HomeViewModel.importProfileRaw(value: String) {
    profileImportInProgressMutable.value = true
    viewModelScope.launch {
        val boundedValue =
            try {
                withContext(Dispatchers.Default) {
                    value
                        .takeUnless(String::isBlank)
                        ?.let(::requireLocalProfileImportWithinLimit)
                }
            } catch (_: ProfileImportPayloadTooLargeException) {
                profileImportInProgressMutable.value = false
                snackbars.tryEmit(errorBanner(R.string.profile_import_too_large))
                return@launch
            }
        if (boundedValue == null) {
            profileImportInProgressMutable.value = false
            snackbars.tryEmit(errorBanner(R.string.profile_import_failed))
            return@launch
        }
        val preview = container.profileRepository.rawInputImportPreview(boundedValue)
        if (preview != null) {
            val tlsWarning = runCatching {
                container.profileRepository.rawInputInsecureTlsWarning(boundedValue)
            }.getOrNull()
            val duplicate = container.profileRepository.findProfileMatchingRawImport(boundedValue)
            val insecureTlsLabels = tlsWarning?.issues?.map { issue -> issue.protocolLabel }.orEmpty()
            val duplicateIsSubscription =
                duplicate?.sourceType == com.foxhole.core.model.ProfileSourceType.SUBSCRIPTION_URL
            profileImportInProgressMutable.value = false
            profileImportConfirmationMutable.value =
                ProfileImportConfirmationState(
                    rawInput = boundedValue,
                    preview = preview,
                    insecureTls = tlsWarning != null,
                    insecureTlsProtocolLabels = insecureTlsLabels,
                    canExcludeInsecureTls = tlsWarning?.canExcludeAndApply == true,
                    subscriptionInspectionInProgress = preview.subscription,
                    duplicateProfileId = duplicate?.id,
                    duplicateProfileName = duplicate?.name,
                    duplicateIsSubscription = duplicateIsSubscription,
                )
            if (preview.subscription && preview.protocolHints.isEmpty()) {
                val fetched = container.profileRepository.subscriptionProtocolsPreview(boundedValue)
                profileImportConfirmationMutable.update { current ->
                    val matching = current?.takeIf { it.rawInput == boundedValue } ?: return@update current
                    if (fetched != null) {
                        matching.copy(
                            preview = fetched,
                            insecureTls = fetched.insecureTlsProtocolLabels.isNotEmpty(),
                            insecureTlsProtocolLabels = fetched.insecureTlsProtocolLabels,
                            canExcludeInsecureTls = fetched.canExcludeInsecureTls,
                            subscriptionInspectionInProgress = false,
                            subscriptionInspectionFailed = false,
                        )
                    } else {
                        matching.copy(
                            subscriptionInspectionInProgress = false,
                            subscriptionInspectionFailed = true,
                        )
                    }
                }
            }
            return@launch
        }
        importRawWithInsecureTlsDecision(
            value = boundedValue,
            allowInsecureTlsForProfile = false,
            excludeInsecureTlsOptions = false,
            precheckInsecureTlsWarning = true,
        )
    }
}

internal fun HomeViewModel.importRawWithTlsConsentInternal(
    value: String,
    excludeInsecureTlsOptions: Boolean,
) {
    profileImportInProgressMutable.value = true
    viewModelScope.launch {
        importRawWithInsecureTlsDecision(
            value = value,
            allowInsecureTlsForProfile = !excludeInsecureTlsOptions,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        )
    }
}

internal fun HomeViewModel.importRaw(value: String) {
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

internal fun HomeViewModel.excludeInsecureTlsAndImport() =
    confirmInsecureTlsImport(
        excludeInsecureTlsOptions = true,
    )

internal fun HomeViewModel.confirmInsecureTlsImport(excludeInsecureTlsOptions: Boolean = false) {
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

internal fun HomeViewModel.dismissInsecureTlsImportWarning() {
    insecureTlsImportWarningMutable.value = null
}

internal fun HomeViewModel.updateDuplicateProfileFromImportInternal(pending: ProfileImportConfirmationState) {
    val profileId = pending.duplicateProfileId ?: return
    viewModelScope.launch {
        container.connectionController.setActiveProfile(profileId)
        startupActiveProfileMutable.value =
            container.profileRepository.getProfile(profileId)?.copy(isActive = true)
        if (pending.duplicateIsSubscription) {
            refreshProfileWithInsecureTlsDecision(
                profileId = profileId,
                allowInsecureTlsForProfile = false,
                excludeInsecureTlsOptions = false,
            )
        } else {
            emitSuccess(getApplication<Application>().getString(R.string.profile_refreshed))
        }
    }
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
    } catch (error: InsecureTlsProfileConsentRequiredException) {
        val warning = error.warning ?: container.profileRepository.rawInputInsecureTlsWarning(value)
        insecureTlsImportWarningMutable.value =
            warning?.toUiState(rawInput = value) ?: InsecureTlsImportWarningState(rawInput = value)
    } catch (error: Throwable) {
        handleProfileImportFailure(value, error)
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

internal fun isNetworkFailure(
    throwable: Throwable,
    message: String,
): Boolean {
    val causeChain = generateSequence(throwable, Throwable::cause).take(MAX_CAUSE_DEPTH)
    if (causeChain.any { it is IOException }) {
        return true
    }
    return NETWORK_FAILURE_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }
}

private const val MAX_CAUSE_DEPTH = 16

private val NETWORK_FAILURE_MARKERS =
    listOf(
        "failed to connect",
        "unable to resolve host",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "timeout",
        "timed out",
    )

internal fun HomeViewModel.profileImportFailureMessage(
    rawInput: String,
    throwable: Throwable,
): String {
    val app = getApplication<Application>()
    val message = throwable.message.orEmpty()
    val trimmed = rawInput.trim()
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

        isNetworkFailure(throwable, message) ->
            app.getString(R.string.profile_import_network_unreachable)

        else -> app.getString(R.string.profile_import_invalid_config)
    }
}

internal suspend fun HomeViewModel.refreshProfileAndMaybeReconnect(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean = false,
    allowInsecureTlsForProfile: Boolean = false,
) {
    val refreshResult =
        container.connectionController.refreshProfileWithReport(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )
    val activeRuntime =
        container.connectionController.snapshot.value.profileId == profileId &&
            container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
    val runtimeAction =
        profileRefreshRuntimeAction(
            activeRuntime = activeRuntime,
            previousProfileId = profileId,
            refreshedProfileId = refreshResult.profile.id,
        )
    val reloaded =
        when (runtimeAction) {
            ProfileRefreshRuntimeAction.RELOAD ->
                container.connectionController.reload(refreshResult.profile.id).also { reloadRequested ->
                    if (reloadRequested) {
                        scheduleDashboardRefreshAfterRuntimeReload()
                    }
                }
            ProfileRefreshRuntimeAction.RECONNECT -> {
                requestReconnect(refreshResult.profile.id)
                false
            }
            ProfileRefreshRuntimeAction.NONE -> false
        }
    val app = getApplication<Application>()
    val message = profileRefreshSuccessMessage(app, refreshResult)
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh in-app notification emitted profileId=$profileId " +
            "refreshedProfileId=${refreshResult.profile.id} name=${refreshResult.profile.name} " +
            "runtimeAction=${runtimeAction.name.lowercase()} hotReloadRequested=$reloaded",
    )
    emitSuccess(message)
}

internal suspend fun HomeViewModel.refreshProfileWithInsecureTlsDecision(
    profileId: Long,
    allowInsecureTlsForProfile: Boolean,
    excludeInsecureTlsOptions: Boolean,
    restartActiveRuntime: Boolean = false,
) {
    try {
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
    } catch (error: CancellationException) {
        throw error
    } catch (error: InsecureTlsProfileConsentRequiredException) {
        insecureTlsImportWarningMutable.value =
            error.warning?.toRefreshUiState(profileId)
                ?: InsecureTlsImportWarningState(rawInput = "", profileId = profileId)
    } catch (error: Throwable) {
        handleProfileRefreshFailure(profileId, error)
    }
}

private suspend fun HomeViewModel.refreshProfileAndRestartActiveRuntimeInternal(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean,
    allowInsecureTlsForProfile: Boolean,
) {
    val refreshResult = container.connectionController.refreshProfileWithReport(
        profileId = profileId,
        excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        allowInsecureTlsForProfile = allowInsecureTlsForProfile,
    )
    val activeRuntime =
        container.connectionController.snapshot.value.profileId == profileId &&
            container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
    if (activeRuntime) {
        requestReconnect(refreshResult.profile.id)
    }
    val app = getApplication<Application>()
    val message = profileRefreshSuccessMessage(app, refreshResult)
    emitSuccess(
        if (activeRuntime) {
            message + app.getString(R.string.profile_refresh_reconnecting_suffix)
        } else {
            message
        },
    )
}

internal enum class ProfileRefreshRuntimeAction {
    NONE,
    RELOAD,
    RECONNECT,
}

internal fun profileRefreshRuntimeAction(
    activeRuntime: Boolean,
    previousProfileId: Long,
    refreshedProfileId: Long,
): ProfileRefreshRuntimeAction =
    when {
        !activeRuntime -> ProfileRefreshRuntimeAction.NONE
        previousProfileId != refreshedProfileId -> ProfileRefreshRuntimeAction.RECONNECT
        else -> ProfileRefreshRuntimeAction.RELOAD
    }

private fun profileRefreshSuccessMessage(
    app: Application,
    result: ProfileRefreshResult,
): String {
    val changes = result.protocolChanges
    if (!changes.hasChanges) {
        return app.getString(R.string.profile_refreshed)
    }
    val none = app.getString(R.string.profile_protocol_changes_none)
    return app.getString(
        R.string.profile_refreshed_protocol_changes,
        changes.availableProtocolLabels.size,
        changes.addedProtocolLabels.joinToString().ifBlank { none },
        changes.removedProtocolLabels.joinToString().ifBlank { none },
        changes.unavailableProtocolLabels.joinToString().ifBlank { none },
    )
}

internal suspend fun HomeViewModel.handleProfileRefreshFailure(
    profileId: Long,
    throwable: Throwable,
) {
    val app = getApplication<Application>()
    val diagnosticMessage = throwable.message.orEmpty()
    container.diagnosticsLogger.recordFailure(
        "profile",
        "profile refresh failed profileId=$profileId: ${throwable.javaClass.simpleName}: $diagnosticMessage",
    )
    emitError(app.getString(R.string.profile_refresh_failed))
}

internal suspend fun HomeViewModel.handleProfileImportFailure(
    rawInput: String,
    throwable: Throwable,
) {
    val diagnosticMessage = throwable.message.orEmpty()
    container.diagnosticsLogger.recordFailure(
        "profile",
        "profile import failed: ${throwable.javaClass.simpleName}: $diagnosticMessage",
    )
    emitError(profileImportFailureMessage(rawInput, throwable))
}

private fun ClipData.firstTextItem(application: Application): String? =
    if (itemCount > 0) {
        getItemAt(0).coerceToText(application).toString()
    } else {
        null
    }
