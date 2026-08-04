package com.foxhole.guard.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.core.backup.BackupDocument
import com.foxhole.guard.core.backup.BackupParseResult
import com.foxhole.guard.core.backup.collectBackupProfiles
import com.foxhole.guard.core.backup.decodeBackupDocument
import com.foxhole.guard.core.backup.encodeBackupDocument
import com.foxhole.guard.core.backup.restoreBackupProfiles
import com.foxhole.guard.core.backup.sanitizedForBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Manual backup/restore: assembles the backup document, writes/reads it through SAF, and replays
// a restore through the ordinary settings/profile pipelines. Extension functions only (see the
// HomeViewModel file-split convention).

private const val MAX_BACKUP_FILE_BYTES = 20L * 1024 * 1024

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
) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            val document = prepareBackupDocumentInternal(includeProfiles, includeSettings)
            val payload = encodeBackupDocument(document)
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                } ?: error("output stream unavailable")
            }
        }.onSuccess {
            emitInfo(app.getString(R.string.backup_saved_banner))
        }.onFailure { error ->
            container.diagnosticsLogger.record("backup", "export failed: ${error.message}")
            snackbars.tryEmit(errorBanner(R.string.backup_save_failed))
        }
    }
}

/** Parses the picked file; failures land as a banner and return null so the screen stays put. */
internal suspend fun HomeViewModel.parseBackup(uri: Uri): BackupDocument? {
    val app = getApplication<Application>()
    val payload =
        withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    check(bytes.size <= MAX_BACKUP_FILE_BYTES) { "backup file too large" }
                    bytes.toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }
    if (payload == null) {
        snackbars.tryEmit(errorBanner(R.string.backup_parse_malformed))
        return null
    }
    return when (val result = decodeBackupDocument(payload)) {
        is BackupParseResult.Success -> result.document
        BackupParseResult.Failure.NOT_A_BACKUP -> {
            snackbars.tryEmit(errorBanner(R.string.backup_parse_not_a_backup))
            null
        }
        BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION -> {
            snackbars.tryEmit(errorBanner(R.string.backup_parse_newer_version))
            null
        }
        BackupParseResult.Failure.MALFORMED -> {
            snackbars.tryEmit(errorBanner(R.string.backup_parse_malformed))
            null
        }
    }
}

internal fun HomeViewModel.restoreBackup(document: BackupDocument) {
    viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            document.settings?.let { backupSettings ->
                container.settingsRepository.update { current ->
                    // Device-local runtime state stays local: the backup was sanitized on write,
                    // but an old/foreign file must not wipe this device's counters either.
                    backupSettings.copy(
                        lastActiveProfile = current.lastActiveProfile,
                        smartProfilePreferences = current.smartProfilePreferences,
                        profileTrafficTotals = current.profileTrafficTotals,
                        installedAppInventoryAudit = current.installedAppInventoryAudit,
                        usageTrackingStartedAt = current.usageTrackingStartedAt,
                    )
                }
                syncLocalGuardWithPermissionRequest()
            }
            val profileResults = container.profileRepository.restoreBackupProfiles(document)
            profileResults
        }.onSuccess { profileResults ->
            val restored = profileResults.count { it.restored }
            val failed = profileResults.count { !it.restored }
            profileResults.filter { !it.restored }.forEach { result ->
                container.diagnosticsLogger.record(
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
        }.onFailure { error ->
            container.diagnosticsLogger.record("backup", "restore failed: ${error.message}")
            snackbars.tryEmit(errorBanner(R.string.backup_restore_failed))
        }
    }
}
