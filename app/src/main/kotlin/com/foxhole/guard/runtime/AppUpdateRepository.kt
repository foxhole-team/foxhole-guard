package com.foxhole.guard.runtime

import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AppUpdateRepository(
    private val client: AppUpdateClient,
    private val currentVersionCode: Long,
    private val downloadDirectory: File,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
    private val apkVerifier: (File, AppUpdateManifest) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val currentVersionName: String = AppUpdateBuildSignals.installedVersionName,
    private val verifierDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val stateMutable = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = stateMutable.asStateFlow()
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var operationOwner = Any()
    private var activeOperationJob: Job? = null
    private val verifiedArtifacts = linkedSetOf<File>()

    suspend fun check(): AppUpdateCheck = operationMutex.withLock {
        coroutineScope {
            val operation = beginOperation(AppUpdateState.Busy(RemoteUpdatePhase.CHECKING))
            try {
                val result = client.check(currentVersionCode, currentVersionName)
                finishOwned(operation, result.asState(operation.previousState))
                diagnosticsLogger?.record("update", "app update check -> ${appUpdateDiagnosticLabel(result)}")
                result
            } catch (cancelled: CancellationException) {
                finishOwned(operation, operation.previousState)
                throw cancelled
            }
        }
    }

    suspend fun checkInBackground(): AppUpdateCheck {
        val observedOwner = synchronized(stateLock) { operationOwner }
        val result = client.check(currentVersionCode, currentVersionName)
        synchronized(stateLock) {
            if (operationOwner === observedOwner && !stateMutable.value.isUpdating() && result !is AppUpdateCheck.Failed) {
                operationOwner = Any()
                stateMutable.value = result.asState(stateMutable.value)
            }
        }
        diagnosticsLogger?.record("update", "background app update check -> ${appUpdateDiagnosticLabel(result)}")
        return result
    }

    suspend fun download(update: AppUpdateCheck.Available): Result<File> = operationMutex.withLock {
        coroutineScope {
            if (!update.installable || !update.manifest.apkName.isSafeApkBasename()) {
                val refusal = IllegalStateException("update has no valid installable artifact name")
                val operation = beginOperation(AppUpdateState.Busy(RemoteUpdatePhase.DOWNLOADING))
                finishOwned(operation, AppUpdateState.Failed(AppUpdateFailure.NO_ARTIFACT, refusal.appUpdateReason()))
                return@coroutineScope Result.failure(refusal)
            }
            val operation = beginOperation(AppUpdateState.Busy(RemoteUpdatePhase.DOWNLOADING))
            var artifact: File? = null
            var retained = false
            try {
                if (!downloadDirectory.isDirectory && !downloadDirectory.mkdirs()) {
                    throw IOException(
                        "update directory unavailable"
                    )
                }
                val candidate = File.createTempFile(".foxhole-update-candidate-", ".apk", downloadDirectory)
                artifact = candidate
                val apk = client.download(update, candidate) { downloaded, total ->
                    updateOwned(operation, AppUpdateState.Downloading(update, downloaded, total))
                }.getOrThrow()
                currentCoroutineContext().ensureActive()
                if (!updateOwned(
                        operation,
                        AppUpdateState.Busy(RemoteUpdatePhase.VERIFYING)
                    )
                ) {
                    throw IOException("update operation replaced")
                }
                withContext(verifierDispatcher) { apkVerifier(apk, update.manifest).getOrThrow() }
                currentCoroutineContext().ensureActive()
                val obsolete = publishVerifiedOwned(operation, update, apk) ?: throw IOException("update operation replaced")
                retained = true
                pruneVerifiedArtifacts(obsolete)
                diagnosticsLogger?.record("update", "app update download -> Downloaded")
                Result.success(apk)
            } catch (cancelled: CancellationException) {
                finishOwned(operation, restoredDownloadState(operation.previousState, update))
                throw cancelled
            } catch (error: Exception) {
                finishOwned(operation, AppUpdateState.Failed(error.asAppUpdateFailure(), error.appUpdateReason()))
                diagnosticsLogger?.record("update", "app update download -> Failed")
                Result.failure(error)
            } finally {
                if (!retained) artifact?.delete()
            }
        }
    }

    fun reset() {
        val job = synchronized(stateLock) {
            val active = activeOperationJob
            activeOperationJob = null
            operationOwner = Any()
            stateMutable.value = AppUpdateState.Idle
            active
        }
        job?.cancel()
    }

    private suspend fun beginOperation(state: AppUpdateState): Operation {
        val job = currentCoroutineContext()[Job]
        return synchronized(stateLock) {
            val operation = Operation(Any(), stateMutable.value)
            operationOwner = operation.token
            activeOperationJob = job
            stateMutable.value = state
            operation
        }
    }

    private fun updateOwned(operation: Operation, state: AppUpdateState): Boolean = synchronized(stateLock) {
        if (operationOwner !== operation.token) {
            false
        } else {
            stateMutable.value = state
            true
        }
    }

    private fun finishOwned(operation: Operation, state: AppUpdateState): Boolean = synchronized(stateLock) {
        if (operationOwner !== operation.token) {
            false
        } else {
            operationOwner = Any()
            activeOperationJob = null
            stateMutable.value = state
            true
        }
    }

    private fun publishVerifiedOwned(operation: Operation, update: AppUpdateCheck.Available, apk: File): List<File>? = synchronized(
        stateLock
    ) {
        if (operationOwner !== operation.token) {
            null
        } else {
            (operation.previousState as? AppUpdateState.Downloaded)?.apk?.let(verifiedArtifacts::add)
            val obsolete = verifiedArtifacts.filter { it != apk }
            verifiedArtifacts.clear()
            verifiedArtifacts.add(apk)
            operationOwner = Any()
            activeOperationJob = null
            stateMutable.value = AppUpdateState.Downloaded(update, apk)
            obsolete
        }
    }

    private fun pruneVerifiedArtifacts(files: List<File>) {
        files.forEach { file ->
            try {
                if (file.parentFile?.canonicalFile == downloadDirectory.canonicalFile) file.delete()
            } catch (_: IOException) {
                // A cache cleanup failure must not invalidate a verified download.
            }
        }
    }

    private data class Operation(val token: Any, val previousState: AppUpdateState)
}

private fun AppUpdateState.isUpdating(): Boolean = this is AppUpdateState.Busy || this is AppUpdateState.Downloading

private fun restoredDownloadState(previous: AppUpdateState, update: AppUpdateCheck.Available): AppUpdateState =
    previous.takeUnless { it.isUpdating() || it == AppUpdateState.Idle } ?: AppUpdateState.Available(update)

private fun AppUpdateCheck.Available.matchesArtifact(other: AppUpdateCheck.Available): Boolean {
    if (manifest.versionCode != other.manifest.versionCode) return false
    if (manifest.apkName != other.manifest.apkName) return false
    return manifest.apkSha256.equals(other.manifest.apkSha256, ignoreCase = true) && sizeBytes == other.sizeBytes
}

private fun AppUpdateCheck.asState(previous: AppUpdateState? = null): AppUpdateState = when (this) {
    is AppUpdateCheck.Available -> if (
        previous is AppUpdateState.Downloaded && previous.apk.isFile && matchesArtifact(previous.update)
    ) {
        previous.copy(update = this)
    } else {
        AppUpdateState.Available(this)
    }
    AppUpdateCheck.UpToDate -> AppUpdateState.UpToDate
    is AppUpdateCheck.Failed -> AppUpdateState.Failed(failure, reason)
}

internal fun String.isSafeApkBasename(): Boolean =
    isNotBlank() && length <= 255 && this != "." && this != ".." &&
        none { it.isISOControl() || it == '/' || it == '\\' } && endsWith(".apk", ignoreCase = true)

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
