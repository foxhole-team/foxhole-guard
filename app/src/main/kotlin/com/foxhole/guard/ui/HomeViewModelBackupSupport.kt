package com.foxhole.guard.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.Settings
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.core.backup.BackupDocument
import com.foxhole.guard.core.backup.BackupParseResult
import com.foxhole.guard.core.backup.MAX_ENCRYPTED_BACKUP_FILE_BYTES
import com.foxhole.guard.core.backup.ProfileBackupRestoreCheckpoint
import com.foxhole.guard.core.backup.captureBackupRestoreCheckpoint
import com.foxhole.guard.core.backup.collectBackupProfiles
import com.foxhole.guard.core.backup.decryptBackupDocument
import com.foxhole.guard.core.backup.encryptBackupDocument
import com.foxhole.guard.core.backup.executeFailAtomicBackupRestore
import com.foxhole.guard.core.backup.mergeRestoredSettings
import com.foxhole.guard.core.backup.restoreBackupProfiles
import com.foxhole.guard.core.backup.restoreBackupRestoreCheckpoint
import com.foxhole.guard.core.backup.sanitizedForBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

// Manual backup/restore: assembles the backup document, writes/reads it through SAF, and replays
// a restore through the ordinary settings/profile pipelines. Extension functions only (see the
// HomeViewModel file-split convention).

// The encrypted ciphertext is capped at 20 MiB, but base64 expands it by roughly one third. Keep
// the outer SAF read cap large enough for every backup this build can successfully export.
internal const val MAX_BACKUP_FILE_BYTES = MAX_ENCRYPTED_BACKUP_FILE_BYTES

internal suspend fun HomeViewModel.prepareBackupDocumentInternal(
    includeProfiles: Boolean,
    includeSettings: Boolean,
): BackupDocument {
    val settings = container.settingsRepository.current()
    return BackupDocument(
        createdAt = System.currentTimeMillis(),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        settingsSchemaVersion = settings.schemaVersion,
        settings = if (includeSettings) settings.sanitizedForBackup() else null,
        profiles = if (includeProfiles) container.profileRepository.collectBackupProfiles() else emptyList(),
    )
}

internal fun HomeViewModel.exportBackup(
    uri: Uri,
    includeProfiles: Boolean,
    includeSettings: Boolean,
    password: CharArray,
) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        try {
            try {
                val document = prepareBackupDocumentInternal(includeProfiles, includeSettings)
                val payload =
                    withContext(Dispatchers.Default) {
                        encryptBackupDocument(
                            document = document,
                            password = password,
                            crypto = securityComponents.guardCrypto,
                        )
                    }
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("output stream unavailable")
                }
                emitInfo(app.getString(R.string.backup_saved_banner))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.recordFailure("backup", "export failed: ${error.message}")
                snackbars.tryEmit(errorBanner(R.string.backup_save_failed))
            }
        } finally {
            password.fill('\u0000')
        }
    }
}

/** Parses the picked file; failures land as a banner and return null so the screen stays put. */
internal suspend fun HomeViewModel.parseBackup(
    uri: Uri,
    password: CharArray,
): BackupDocument? {
    val app = getApplication<Application>()
    try {
        val payload =
            try {
                withContext(Dispatchers.IO) {
                    backupReadOrNull {
                        app.contentResolver.openInputStream(uri)?.use(InputStream::readBackupUtf8Capped)
                    }
                }
            } catch (tooLarge: BackupFileTooLargeException) {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_malformed))
                throw tooLarge
            }
        if (payload == null) {
            snackbars.tryEmit(errorBanner(R.string.backup_parse_malformed))
            return null
        }
        val result =
            withContext(Dispatchers.Default) {
                decryptBackupDocument(
                    payload = payload,
                    password = password,
                    crypto = securityComponents.guardCrypto,
                )
            }
        return when (result) {
            is BackupParseResult.Success -> result.document
            BackupParseResult.Failure.NOT_A_BACKUP -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_not_a_backup))
                null
            }
            BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_newer_version))
                null
            }
            BackupParseResult.Failure.UNSUPPORTED_SETTINGS_SCHEMA -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_newer_settings))
                null
            }
            BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_unsupported_encryption))
                null
            }
            BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_wrong_password))
                null
            }
            BackupParseResult.Failure.MALFORMED -> {
                snackbars.tryEmit(errorBanner(R.string.backup_parse_malformed))
                null
            }
        }
    } finally {
        password.fill('\u0000')
    }
}

internal fun HomeViewModel.restoreBackup(document: BackupDocument) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        try {
            val profileResults =
                executeFailAtomicBackupRestore(
                    capture = {
                        BackupRestoreCheckpoint(
                            profiles = container.profileRepository.captureBackupRestoreCheckpoint(),
                            settings = container.settingsRepository.current(),
                        )
                    },
                    apply = {
                        val restoredProfiles = container.profileRepository.restoreBackupProfiles(document)
                        document.settings?.let { backupSettings ->
                            val currentSettings = container.settingsRepository.current()
                            // Capture the destination's installed-app identity set BEFORE publishing
                            // imported quarantine=true. The one settings write stays atomic.
                            val quarantineBaseline =
                                if (
                                    backupSettings.expert.newAppQuarantineEnabled &&
                                    currentSettings.expert.quarantineKnownApplications.isEmpty()
                                ) {
                                    container.settingsRepository.captureKnownApplicationsForQuarantine()
                                } else {
                                    null
                                }
                            container.settingsRepository.update { current ->
                                mergeRestoredSettings(
                                    backup = backupSettings,
                                    current = current,
                                    quarantineKnownApplicationsOverride = quarantineBaseline,
                                )
                            }
                        }
                        restoredProfiles
                    },
                    rollback = ::rollbackBackupRestore,
                )
            if (document.settings != null) {
                // Runtime reconciliation begins only after the persisted transaction commits.
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
        } catch (error: Throwable) {
            container.diagnosticsLogger.recordFailure("backup", "restore failed: ${error.message}")
            snackbars.tryEmit(errorBanner(R.string.backup_restore_failed))
        }
    }
}

private data class BackupRestoreCheckpoint(
    val profiles: ProfileBackupRestoreCheckpoint,
    val settings: Settings,
)

private suspend fun HomeViewModel.rollbackBackupRestore(checkpoint: BackupRestoreCheckpoint) {
    var failure: Throwable? = null
    try {
        container.profileRepository.restoreBackupRestoreCheckpoint(checkpoint.profiles)
    } catch (error: Throwable) {
        failure = error
    }
    try {
        container.settingsRepository.restoreExactCheckpoint(checkpoint.settings)
    } catch (error: Throwable) {
        failure?.addSuppressed(error) ?: run { failure = error }
    }
    failure?.let { throw it }
}

internal inline fun <T> backupReadOrNull(block: () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (tooLarge: BackupFileTooLargeException) {
        throw tooLarge
    } catch (_: Throwable) {
        null
    }

/** Reads at most MAX+1 bytes, so an oversized provider cannot force an unbounded allocation. */
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
