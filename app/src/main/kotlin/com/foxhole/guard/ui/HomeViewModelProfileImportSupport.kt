package com.foxhole.guard.ui
import android.app.Application
import android.content.ClipData
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.DiagnosticSanitizer
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
        // Every add parks on the confirmation sheet first (type + protocol + domain preview, and
        // the insecure-TLS consent folded in when the payload carries it). A payload that does
        // not even parse skips the sheet and lets the ordinary import path surface its own human
        // error message.
        val preview = container.profileRepository.rawInputImportPreview(boundedValue)
        if (preview != null) {
            val tlsWarning = runCatching {
                container.profileRepository.rawInputInsecureTlsWarning(boundedValue)
            }.getOrNull()
            // The same source stored earlier flips the sheet into the duplicate prompt
            // (already-added, offering cancel or refresh) instead of importing a twin.
            val duplicate = container.profileRepository.findProfileMatchingRawImport(boundedValue)
            profileImportInProgressMutable.value = false
            profileImportConfirmationMutable.value =
                ProfileImportConfirmationState(
                    rawInput = boundedValue,
                    preview = preview,
                    insecureTls = tlsWarning != null,
                    insecureTlsProtocolLabels =
                    tlsWarning?.issues?.map { issue -> issue.protocolLabel }.orEmpty(),
                    canExcludeInsecureTls = tlsWarning?.canExcludeAndApply == true,
                    duplicateProfileId = duplicate?.id,
                    duplicateProfileName = duplicate?.name,
                    duplicateIsSubscription =
                    duplicate?.sourceType == com.foxhole.core.model.ProfileSourceType.SUBSCRIPTION_URL,
                )
            // A subscription's protocols are inside its BODY, which the preview above deliberately
            // does not fetch — so the sheet opens instantly on a dash and the protocols land in it
            // a moment later. Only replace the preview if this very sheet is still the one open.
            if (preview.subscription && preview.protocolHints.isEmpty()) {
                val fetched = container.profileRepository.subscriptionProtocolsPreview(boundedValue)
                if (fetched != null && fetched.protocolHints.isNotEmpty()) {
                    profileImportConfirmationMutable.update { current ->
                        current?.takeIf { it.rawInput == boundedValue }?.copy(
                            preview = fetched,
                            insecureTls = fetched.insecureTlsProtocolLabels.isNotEmpty(),
                            insecureTlsProtocolLabels = fetched.insecureTlsProtocolLabels,
                            canExcludeInsecureTls = fetched.canExcludeInsecureTls,
                        )
                            ?: current
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

/** Consent captured in the add-profile sheet: run the import with the TLS decision applied. */
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

/**
 * Refresh on the duplicate-import prompt: subscriptions re-fetch the stored profile (the fox
 * narrates it), plain configs just re-activate the existing profile — either way no twin is
 * created.
 */
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

/**
 * Whether the import never got as far as reading a document.
 *
 * A subscription that could not be fetched says nothing about the link, so it
 * must not be reported as a bad link. The cause chain is the reliable signal —
 * OkHttp raises `IOException` subclasses for connect, DNS and TLS failures —
 * and the message is checked as well because the fetch path re-wraps some of
 * them, which is how a connect timeout reached the user as a parse complaint.
 */
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

/**
 * Bounded because a cause chain can be circular, and a hang while composing an
 * error message is a worse failure than the one being described.
 */
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

        // The link was never read, so nothing about it is known to be wrong.
        //
        // Found on a phone with no working data: a ten-second connect timeout to
        // port 443 came out of here as "this does not look like a valid VPN
        // configuration", which sends the user to re-check a link that is fine
        // and says nothing about the one thing that is not. Matched on both the
        // cause chain and the message because the fetch layer re-wraps some of
        // these before they arrive.
        isNetworkFailure(throwable, message) ->
            app.getString(R.string.profile_import_network_unreachable)

        // Never surface parser internals ("unexpected token...", stack-trace-ish strings) to the
        // user: anything uncurated collapses into one human sentence; the raw cause still goes to
        // the diagnostics log.
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
    val reloaded =
        activeRuntime &&
            container.connectionController.reload(profileId).also { reloadRequested ->
                if (reloadRequested) {
                    scheduleDashboardRefreshAfterRuntimeReload()
                }
            }
    val app = getApplication<Application>()
    val message = profileRefreshSuccessMessage(app, refreshResult)
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh in-app notification emitted profileId=$profileId " +
            "name=${refreshResult.profile.name} hotReloadRequested=$reloaded",
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
    val refreshResult = container.connectionController.refreshProfileWithReport(
        profileId = profileId,
        excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        allowInsecureTlsForProfile = allowInsecureTlsForProfile,
    )
    val activeRuntime =
        container.connectionController.snapshot.value.profileId == profileId &&
            container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
    if (activeRuntime) {
        requestReconnect(profileId)
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
    val sanitizedMessage = DiagnosticSanitizer.sanitize(throwable.message.orEmpty())
    container.diagnosticsLogger.record(
        "profile",
        "profile refresh failed profileId=$profileId: ${throwable.javaClass.simpleName}: $sanitizedMessage",
    )
    emitError(app.getString(R.string.profile_refresh_failed))
}

internal suspend fun HomeViewModel.handleProfileImportFailure(
    rawInput: String,
    throwable: Throwable,
) {
    val sanitizedMessage = DiagnosticSanitizer.sanitize(throwable.message.orEmpty())
    container.diagnosticsLogger.record(
        "profile",
        "profile import failed: ${throwable.javaClass.simpleName}: $sanitizedMessage",
    )
    emitError(profileImportFailureMessage(rawInput, throwable))
}

private fun ClipData.firstTextItem(application: Application): String? =
    if (itemCount > 0) {
        getItemAt(0).coerceToText(application).toString()
    } else {
        null
    }
