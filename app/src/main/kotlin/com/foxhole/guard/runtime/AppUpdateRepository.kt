package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Drives [AppUpdateClient] for builds installed from GitHub: check, download, hand the verified APK
 * to the installer. F-Droid and Play builds never reach here — the caller gates on
 * [com.foxhole.guard.BuildConfig.UPDATE_CHANNEL].
 *
 * The downloaded APK lands in a dedicated cache directory that is wiped before each download, so a
 * stale package from an earlier attempt can never be the one that gets installed.
 */
class AppUpdateRepository(
    private val client: AppUpdateClient,
    private val currentVersionCode: Long,
    private val downloadDirectory: File,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
    private val apkVerifier: (File, AppUpdateManifest) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val currentVersionName: String = AppUpdateBuildSignals.installedVersionName,
) {
    private val stateMutable = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = stateMutable.asStateFlow()

    suspend fun check(): AppUpdateCheck {
        stateMutable.value = AppUpdateState.Busy(RemoteUpdatePhase.CHECKING)
        val result = client.check(currentVersionCode, currentVersionName)
        stateMutable.value =
            when (result) {
                is AppUpdateCheck.Available -> AppUpdateState.Available(result)
                AppUpdateCheck.UpToDate -> AppUpdateState.UpToDate
                is AppUpdateCheck.Failed -> AppUpdateState.Failed(result.failure, result.reason)
            }
        diagnosticsLogger?.record("update", "app update check -> ${appUpdateDiagnosticLabel(result)}")
        return result
    }

    suspend fun checkInBackground(): AppUpdateCheck {
        val result = client.check(currentVersionCode, currentVersionName)
        when (result) {
            is AppUpdateCheck.Available -> stateMutable.value = AppUpdateState.Available(result)
            AppUpdateCheck.UpToDate -> stateMutable.value = AppUpdateState.UpToDate
            is AppUpdateCheck.Failed -> Unit
        }
        diagnosticsLogger?.record("update", "background app update check -> ${appUpdateDiagnosticLabel(result)}")
        return result
    }

    /**
     * Downloads [update] and returns the verified file. The digest is checked inside
     * [AppUpdateClient.download] and the package identity/signature by [apkVerifier]; a failure in
     * either means nothing installable was produced, so [AppUpdateState.Downloaded] — the only state
     * the install action accepts — is unreachable for an artifact that did not pass both.
     */
    suspend fun download(update: AppUpdateCheck.Available): Result<File> {
        if (!update.installable) {
            val refusal = IllegalStateException("release ${update.versionName} carries no installable package")
            stateMutable.value = AppUpdateState.Failed(AppUpdateFailure.NO_ARTIFACT, refusal.appUpdateReason())
            return Result.failure(refusal)
        }
        stateMutable.value = AppUpdateState.Busy(RemoteUpdatePhase.DOWNLOADING)
        downloadDirectory.deleteRecursively()
        val target = File(downloadDirectory, update.manifest.apkName)
        val result =
            client
                .download(update, target) { downloaded, total ->
                    stateMutable.value = AppUpdateState.Downloading(update, downloaded, total)
                }.mapCatching { apk ->
                    stateMutable.value = AppUpdateState.Busy(RemoteUpdatePhase.VERIFYING)
                    apkVerifier(apk, update.manifest)
                        .onFailure { apk.delete() }
                        .getOrThrow()
                    apk
                }
        stateMutable.value =
            result.fold(
                onSuccess = { file -> AppUpdateState.Downloaded(update, file) },
                onFailure = { error -> AppUpdateState.Failed(error.asAppUpdateFailure(), error.appUpdateReason()) },
            )
        diagnosticsLogger?.record("update", "app update download -> ${stateMutable.value.javaClass.simpleName}")
        return result
    }

    fun reset() {
        stateMutable.value = AppUpdateState.Idle
    }
}

private fun appUpdateDiagnosticLabel(result: AppUpdateCheck): String =
    when (result) {
        is AppUpdateCheck.Available -> "Available(behind=${result.versionsBehind}, severity=${result.severity})"
        AppUpdateCheck.UpToDate -> "UpToDate"
        is AppUpdateCheck.Failed -> "Failed(${result.failure})"
    }

sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    data class Busy(val phase: RemoteUpdatePhase) : AppUpdateState

    data object UpToDate : AppUpdateState

    data class Available(val update: AppUpdateCheck.Available) : AppUpdateState

    data class Downloading(
        val update: AppUpdateCheck.Available,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateState

    data class Downloaded(
        val update: AppUpdateCheck.Available,
        val apk: File,
    ) : AppUpdateState

    data class Failed(
        val failure: AppUpdateFailure,
        val reason: String,
    ) : AppUpdateState
}

val AppUpdateState.appUpdateSeverity: AppUpdateSeverity
    get() =
        when (this) {
            is AppUpdateState.Available -> update.severity
            is AppUpdateState.Downloading -> update.severity
            is AppUpdateState.Downloaded -> update.severity
            else -> AppUpdateSeverity.NONE
        }
