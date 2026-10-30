package com.foxhole.guard.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.core.backup.BackupDocument
import com.foxhole.guard.core.backup.BackupImportPreparation
import com.foxhole.guard.core.backup.BackupParseResult
import com.foxhole.guard.core.backup.MAX_ENCRYPTED_BACKUP_FILE_BYTES
import com.foxhole.guard.core.backup.OwnedBackupPassword
import com.foxhole.guard.core.backup.PendingBackupExport
import com.foxhole.guard.core.backup.PreparedBackupImport
import com.foxhole.guard.core.backup.captureBackupExportSnapshot
import com.foxhole.guard.core.backup.launchOwnedBackupExport
import com.foxhole.guard.core.backup.launchOwnedBackupWork
import com.foxhole.guard.core.backup.preparePortableBackupImportOnce
import com.foxhole.guard.core.backup.restoreBackupDocument
import com.foxhole.guard.core.backup.sanitizedForBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

internal const val MAX_BACKUP_FILE_BYTES = MAX_ENCRYPTED_BACKUP_FILE_BYTES

internal suspend fun HomeViewModel.prepareBackupDocumentInternal(
    includeProfiles: Boolean,
    includeSettings: Boolean,
): BackupDocument {
    val snapshot = container.profileRepository.captureBackupExportSnapshot(includeProfiles, includeSettings)
    return BackupDocument(
        createdAt = System.currentTimeMillis(),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        settingsSchemaVersion = snapshot.settingsSchemaVersion,
        settings = snapshot.settings?.sanitizedForBackup(),
        profiles = snapshot.profiles,
    )
}

internal fun HomeViewModel.exportBackup(
    uri: Uri,
    request: PendingBackupExport,
): Job =
    viewModelScope.launchOwnedBackupExport(request.transfer()) { operation ->
        val app = getApplication<Application>()
        try {
            val document = prepareBackupDocumentInternal(
                operation.selection.includeProfiles,
                operation.selection.includeSettings,
            )
            val payload = withContext(Dispatchers.Default) {
                operation.encode(document, securityComponents.guardCrypto)
            }
            withContext(Dispatchers.IO) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                try {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(bytes)
                    } ?: error("output stream unavailable")
                } finally {
                    bytes.fill(0)
                }
            }
            emitInfo(app.getString(R.string.backup_saved_banner))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            container.diagnosticsLogger.recordFailure("backup", "export failed: ${error.javaClass.simpleName}")
            snackbars.tryEmit(errorBanner(R.string.backup_save_failed))
        }
    }

internal fun HomeViewModel.prepareBackupImport(
    uri: Uri,
    onPrepared: (PreparedBackupImport?) -> Unit,
): Job =
    viewModelScope.launch {
        val app = getApplication<Application>()
        val prepared = try {
            when (
                val result = preparePortableBackupImportOnce {
                    withContext(Dispatchers.IO) {
                        backupReadOrNull {
                            app.contentResolver.openInputStream(uri)?.use(InputStream::readBackupUtf8Capped)
                        }
                    }
                }
            ) {
                is BackupImportPreparation.Ready -> result.prepared
                is BackupImportPreparation.Failure -> {
                    emitBackupParseFailure(result.reason)
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            container.diagnosticsLogger.recordFailure("backup", "read failed: ${error.javaClass.simpleName}")
            emitBackupParseFailure(BackupParseResult.Failure.MALFORMED)
            null
        }
        coroutineContext.ensureActive()
        onPrepared(prepared)
    }

internal fun HomeViewModel.unlockPreparedBackup(
    prepared: PreparedBackupImport.Encrypted,
    password: CharArray,
    onDocument: (BackupDocument?) -> Unit,
): Job =
    viewModelScope.launchOwnedBackupWork(OwnedBackupPassword.take(password)) { operation ->
        val document = try {
            when (
                val result = withContext(Dispatchers.Default) {
                    operation.usePassword { prepared.unlock(it, securityComponents.guardCrypto) }
                }
            ) {
                is BackupParseResult.Success -> result.document
                is BackupParseResult.Failure -> {
                    emitBackupParseFailure(result)
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            container.diagnosticsLogger.recordFailure("backup", "decode failed: ${error.javaClass.simpleName}")
            emitBackupParseFailure(BackupParseResult.Failure.MALFORMED)
            null
        }
        coroutineContext.ensureActive()
        onDocument(document)
    }

private fun HomeViewModel.emitBackupParseFailure(reason: BackupParseResult.Failure) {
    val message = when (reason) {
        BackupParseResult.Failure.NOT_A_BACKUP -> R.string.backup_parse_not_a_backup
        BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION -> R.string.backup_parse_newer_version
        BackupParseResult.Failure.UNSUPPORTED_SETTINGS_SCHEMA -> R.string.backup_parse_newer_settings
        BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION -> R.string.backup_parse_unsupported_encryption
        BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT -> R.string.backup_parse_wrong_password
        BackupParseResult.Failure.MALFORMED -> R.string.backup_parse_malformed
    }
    snackbars.tryEmit(errorBanner(message))
}

internal fun HomeViewModel.reportBackupPickerFailure(exporting: Boolean, error: Exception) {
    container.diagnosticsLogger.recordFailure("backup", "picker failed: ${error.javaClass.simpleName}")
    val banner = if (exporting) R.string.backup_save_failed else R.string.backup_parse_malformed
    snackbars.tryEmit(errorBanner(banner))
}

internal fun HomeViewModel.reportBackupExportCancelled() {
    snackbars.tryEmit(infoBanner(R.string.backup_export_cancelled_banner))
}

internal fun HomeViewModel.restoreBackup(document: BackupDocument) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        try {
            val profileResults = container.profileRepository.restoreBackupDocument(document)
            if (document.settings != null) {
                syncLocalGuardWithPermissionRequest()
            }
            val restored = profileResults.count { it.restored }
            val failed = profileResults.count { !it.restored }
            profileResults.filter { !it.restored }.forEach { result ->
                container.diagnosticsLogger.recordFailure(
                    "backup",
                    "profile restore failed: ${result.name}: ${result.error}",
                )
            }
            val message =
                when {
                    document.profiles.isEmpty() -> app.getString(R.string.backup_restore_done_settings_only)
                    failed == 0 -> app.getString(R.string.backup_restore_done, restored)
                    else -> app.getString(R.string.backup_restore_done_partial, restored, failed)
                }
            emitInfo(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            container.diagnosticsLogger.recordFailure("backup", "restore failed: ${error.javaClass.simpleName}")
            snackbars.tryEmit(errorBanner(R.string.backup_restore_failed))
        }
    }
}

internal inline fun <T> backupReadOrNull(block: () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (tooLarge: BackupFileTooLargeException) {
        throw tooLarge
    } catch (_: Exception) {
        null
    }

internal fun InputStream.readBackupUtf8Capped(maxBytes: Int = MAX_BACKUP_FILE_BYTES): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, BACKUP_READ_BUFFER_BYTES))
    val buffer = ByteArray(BACKUP_READ_BUFFER_BYTES)
    var total = 0
    var finished = false
    while (!finished) {
        val remainingProbe = maxBytes + 1 - total
        if (remainingProbe <= 0) throw BackupFileTooLargeException()
        val read = read(buffer, 0, minOf(buffer.size, remainingProbe))
        if (read < 0) {
            finished = true
        } else if (read > 0) {
            total += read
            if (total > maxBytes) throw BackupFileTooLargeException()
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal class BackupFileTooLargeException : IllegalArgumentException("backup file too large")

private const val BACKUP_READ_BUFFER_BYTES = 64 * 1024
