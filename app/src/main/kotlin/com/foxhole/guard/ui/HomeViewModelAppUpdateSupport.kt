package com.foxhole.guard.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.runtime.AppUpdateCheck
import com.foxhole.guard.runtime.AppUpdateState
import kotlinx.coroutines.launch

// Application updates for GitHub-installed builds. F-Droid and Play ship their own updater, so
// every entry point here is gated on the build's update channel.

internal const val GITHUB_UPDATE_CHANNEL = "github"

internal val appUpdateChannelIsGithub: Boolean
    get() = BuildConfig.UPDATE_CHANNEL == GITHUB_UPDATE_CHANNEL

internal fun HomeViewModel.onAppUpdateCheckRequested() {
    if (!appUpdateChannelIsGithub || componentUpdates.appUpdateJob?.isActive == true) {
        return
    }
    componentUpdates.appUpdateJob =
        viewModelScope.launch {
            val result = container.appUpdateRepository.check()
            if (result is AppUpdateCheck.Failed) {
                snackbars.tryEmit(errorBanner(R.string.cli_updates_app_failed))
            }
        }
}

internal fun HomeViewModel.onAppUpdateDownloadRequested() {
    val available = (container.appUpdateRepository.state.value as? AppUpdateState.Available)?.update ?: return
    if (componentUpdates.appUpdateJob?.isActive == true) {
        return
    }
    componentUpdates.appUpdateJob =
        viewModelScope.launch {
            container.appUpdateRepository
                .download(available)
                .onFailure { snackbars.tryEmit(errorBanner(R.string.cli_updates_app_failed)) }
        }
}

/**
 * Hands the verified APK to the system installer. The user confirms the install in the platform
 * dialog — the app never installs silently, and without "install unknown apps" the system asks for
 * that permission itself, which is the honest place for that decision.
 */
internal fun HomeViewModel.onAppUpdateInstallRequested() {
    val downloaded = container.appUpdateRepository.state.value as? AppUpdateState.Downloaded ?: return
    val context: android.content.Context = getApplication()
    val uri =
        runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", downloaded.apk)
        }.getOrElse {
            snackbars.tryEmit(errorBanner(R.string.cli_updates_app_failed))
            return
        }
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
        .onFailure { snackbars.tryEmit(errorBanner(R.string.cli_updates_app_failed)) }
}

internal fun HomeViewModel.onAppUpdateDismissed() {
    componentUpdates.appUpdateJob?.cancel()
    componentUpdates.appUpdateJob = null
    container.appUpdateRepository.reset()
}
