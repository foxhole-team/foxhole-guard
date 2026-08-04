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
) {
    private val stateMutable = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = stateMutable.asStateFlow()

    suspend fun check(): AppUpdateCheck {
        stateMutable.value = AppUpdateState.Busy(RemoteUpdatePhase.CHECKING)
        val result = client.check(currentVersionCode)
        stateMutable.value =
            when (result) {
                is AppUpdateCheck.Available -> AppUpdateState.Available(result)
                AppUpdateCheck.UpToDate -> AppUpdateState.UpToDate
                is AppUpdateCheck.Failed -> AppUpdateState.Failed(result.reason)
            }
        diagnosticsLogger?.record("update", "app update check -> ${stateMutable.value.javaClass.simpleName}")
        return result
    }

    /**
     * Downloads [update] and returns the verified file. The digest is checked inside
     * [AppUpdateClient.download]; a failure here means nothing installable was produced.
     */
    suspend fun download(update: AppUpdateCheck.Available): Result<File> {
        stateMutable.value = AppUpdateState.Busy(RemoteUpdatePhase.DOWNLOADING)
        downloadDirectory.deleteRecursively()
        val target = File(downloadDirectory, update.manifest.apkName)
        val result =
            client.download(update, target) { downloaded, total ->
                stateMutable.value = AppUpdateState.Downloading(update, downloaded, total)
            }
        stateMutable.value =
            result.fold(
                onSuccess = { file -> AppUpdateState.Downloaded(update, file) },
                onFailure = { error -> AppUpdateState.Failed(error.message ?: error.javaClass.simpleName) },
            )
        diagnosticsLogger?.record("update", "app update download -> ${stateMutable.value.javaClass.simpleName}")
        return result
    }

    fun reset() {
        stateMutable.value = AppUpdateState.Idle
    }
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

    data class Failed(val reason: String) : AppUpdateState
}
