package com.foxhole.guard.ui

import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.runtime.AppUpdateCheck
import com.foxhole.guard.runtime.AppUpdateFailure
import com.foxhole.guard.runtime.AppUpdateNotifier
import com.foxhole.guard.runtime.AppUpdatePolicy
import com.foxhole.guard.runtime.AppUpdateSeverity
import com.foxhole.guard.runtime.AppUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val GITHUB_UPDATE_CHANNEL = AppUpdatePolicy.GITHUB_CHANNEL

internal fun selfUpdateEnabledFor(channel: String): Boolean = AppUpdatePolicy.selfUpdateAllowed(channel)

internal val appUpdateChannelIsGithub: Boolean
    get() = selfUpdateEnabledFor(BuildConfig.UPDATE_CHANNEL)

internal fun AppUpdateFailure.appUpdateFailureLabelRes(): Int =
    when (this) {
        AppUpdateFailure.NETWORK -> R.string.cli_updates_app_failed_network
        AppUpdateFailure.RATE_LIMITED -> R.string.cli_updates_app_failed_rate_limited
        AppUpdateFailure.UNAUTHORIZED -> R.string.cli_updates_app_failed_unauthorized
        AppUpdateFailure.NOT_FOUND -> R.string.cli_updates_app_failed_not_found
        AppUpdateFailure.MALFORMED -> R.string.cli_updates_app_failed_malformed
        AppUpdateFailure.BLOCKED -> R.string.cli_updates_app_failed_blocked
        AppUpdateFailure.NO_ARTIFACT -> R.string.cli_updates_app_failed_no_artifact
        AppUpdateFailure.VERIFICATION -> R.string.cli_updates_app_failed_verification
        AppUpdateFailure.UNKNOWN -> R.string.cli_updates_app_failed
    }

internal fun appUpdateRequiredLabelRes(severity: AppUpdateSeverity): Int? =
    when (severity) {
        AppUpdateSeverity.NONE -> null
        AppUpdateSeverity.BEHIND_ONE -> R.string.cli_updates_app_required_one
        AppUpdateSeverity.BEHIND_TWO -> R.string.cli_updates_app_required_two
        AppUpdateSeverity.BEHIND_MANY -> R.string.cli_updates_app_required_many
    }

internal fun HomeViewModel.onAppUpdateCheckRequested() {
    if (!appUpdateChannelIsGithub || componentUpdates.appUpdateJob?.isActive == true) {
        return
    }
    componentUpdates.appUpdateJob =
        viewModelScope.launch {
            when (val result = container.appUpdateRepository.check()) {
                is AppUpdateCheck.Failed ->
                    snackbars.tryEmit(errorBanner(result.failure.appUpdateFailureLabelRes()))
                is AppUpdateCheck.Available -> announceRequiredAppUpdate(result)
                AppUpdateCheck.UpToDate -> Unit
            }
        }
}

private suspend fun HomeViewModel.announceRequiredAppUpdate(update: AppUpdateCheck.Available) {
    if (!update.severity.notifies) {
        return
    }
    val context: android.content.Context = getApplication()
    withContext(Dispatchers.IO) {
        runCatching { AppUpdateNotifier(context).notifyAvailable(update) }
    }
}

internal fun HomeViewModel.onAppUpdateDownloadRequested() {
    if (!appUpdateChannelIsGithub) return
    val available = (container.appUpdateRepository.state.value as? AppUpdateState.Available)?.update ?: return
    if (componentUpdates.appUpdateJob?.isActive == true) {
        return
    }
    if (!available.installable) {
        snackbars.tryEmit(errorBanner(AppUpdateFailure.NO_ARTIFACT.appUpdateFailureLabelRes()))
        return
    }
    componentUpdates.appUpdateJob =
        viewModelScope.launch {
            container.appUpdateRepository
                .download(available)
                .onFailure { error ->
                    val failure =
                        (container.appUpdateRepository.state.value as? AppUpdateState.Failed)?.failure
                            ?: error.asDownloadFailure()
                    snackbars.tryEmit(errorBanner(failure.appUpdateFailureLabelRes()))
                }
        }
}

private fun Throwable.asDownloadFailure(): AppUpdateFailure =
    if (this is java.io.IOException) AppUpdateFailure.NETWORK else AppUpdateFailure.UNKNOWN

internal fun HomeViewModel.onAppUpdateInstallRequested() {
    if (!appUpdateChannelIsGithub) return
    val downloaded = container.appUpdateRepository.state.value as? AppUpdateState.Downloaded ?: return
    val context: android.content.Context = getApplication()
    if (!context.packageManager.canRequestPackageInstalls()) {
        val permissionIntent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(permissionIntent) }
            .onFailure { snackbars.tryEmit(errorBanner(R.string.cli_updates_app_install_permission_failed)) }
        return
    }
    val uri =
        runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", downloaded.apk)
        }.getOrElse {
            snackbars.tryEmit(errorBanner(R.string.cli_updates_app_install_failed))
            return
        }
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
        .onFailure { snackbars.tryEmit(errorBanner(R.string.cli_updates_app_install_failed)) }
}

internal fun HomeViewModel.onAppUpdateDismissed() {
    componentUpdates.appUpdateJob?.cancel()
    componentUpdates.appUpdateJob = null
    container.appUpdateRepository.reset()
}
