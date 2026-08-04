package com.foxhole.guard.core.backup

import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.InstalledAppInventoryAudit
import com.foxhole.core.model.Settings
import com.foxhole.core.model.migrateStoredProfileSourceToken
import com.foxhole.core.model.migrateStoredProtocolToken
import com.foxhole.core.model.storedProtocolHintOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The manual backup file: plain JSON (the user is warned it carries profile secrets in the
// clear), forward-tolerant on read (unknown fields ignored), versioned on write.

const val BACKUP_DOCUMENT_FORMAT = "foxhole-guard-backup"
const val BACKUP_DOCUMENT_FORMAT_VERSION = 1

val backupJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        prettyPrint = true
        // Write every field, including runtime defaults (e.g. usageTrackingStartedAt): a backup
        // must be self-contained and round-trip exactly, not re-derive live defaults on restore.
        encodeDefaults = true
    }

/**
 * One profile inside the backup: enough to re-import it through the ordinary import pipeline.
 * Subscriptions carry their URL (restore re-fetches), local configs carry the raw input or the
 * resolved config JSON. Protocol names are stored as plain strings so a backup from a newer app
 * with unknown protocols still parses — the restore preview flags them instead.
 */
@Serializable
data class BackupProfilePayload(
    val name: String,
    val sourceType: String,
    val protocolHints: List<String> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val isActive: Boolean = false,
    val subscriptionUrl: String? = null,
    val rawInput: String? = null,
    val resolvedConfigJson: String? = null,
    val insecureTlsConsentGranted: Boolean = false,
)

@Serializable
data class BackupDocument(
    val format: String = BACKUP_DOCUMENT_FORMAT,
    val formatVersion: Int = BACKUP_DOCUMENT_FORMAT_VERSION,
    val createdAt: Long = 0,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val settingsSchemaVersion: Int? = null,
    val settings: Settings? = null,
    val profiles: List<BackupProfilePayload> = emptyList(),
)

/**
 * Device-local runtime state must not travel between installs: the ids inside these fields point
 * into THIS device's profile database and would dangle after a restore elsewhere.
 */
fun Settings.sanitizedForBackup(): Settings =
    copy(
        lastActiveProfile = null,
        smartProfilePreferences = emptyList(),
        profileTrafficTotals = emptyList(),
        installedAppInventoryAudit = InstalledAppInventoryAudit(),
        // The keybox never leaves the device; restoring an appLock=PASSWORD flag onto a
        // device with no keybox would soft-brick the app, so the security block resets.
        appLock = AppLockSettings(),
    )

fun encodeBackupDocument(document: BackupDocument): String =
    backupJson.encodeToString(BackupDocument.serializer(), document) + "\n"

sealed interface BackupParseResult {
    data class Success(val document: BackupDocument) : BackupParseResult

    enum class Failure : BackupParseResult {
        NOT_A_BACKUP,
        UNSUPPORTED_FORMAT_VERSION,
        MALFORMED,
    }
}

fun decodeBackupDocument(payload: String): BackupParseResult {
    val document =
        try {
            backupJson.decodeFromString(BackupDocument.serializer(), payload)
        } catch (_: SerializationException) {
            return BackupParseResult.Failure.MALFORMED
        } catch (_: IllegalArgumentException) {
            return BackupParseResult.Failure.MALFORMED
        }
    return when {
        document.format != BACKUP_DOCUMENT_FORMAT -> BackupParseResult.Failure.NOT_A_BACKUP
        document.formatVersion > BACKUP_DOCUMENT_FORMAT_VERSION ->
            BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION
        else -> BackupParseResult.Success(document.migratedProfileTokens())
    }
}

/** foxhole_guard_backup_all_2026-07-11_0615.json — the suffix names what the file carries. */
fun backupFileName(
    profilesIncluded: Boolean,
    settingsIncluded: Boolean,
    now: Long = System.currentTimeMillis(),
): String {
    val scope =
        when {
            profilesIncluded && settingsIncluded -> "all"
            profilesIncluded -> "profiles"
            else -> "settings"
        }
    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(now))
    return "foxhole_guard_backup_${scope}_$stamp.json"
}

/** The restore preview's per-profile verdict: what it is and whether this build can run it. */
data class BackupProfileCompatibility(
    val payload: BackupProfilePayload,
    val knownProtocolNames: List<String>,
    val unknownProtocolNames: List<String>,
    val importable: Boolean,
)

fun BackupProfilePayload.compatibility(): BackupProfileCompatibility {
    val known = mutableListOf<String>()
    val unknown = mutableListOf<String>()
    protocolHints.forEach { hint ->
        val resolved = storedProtocolHintOrNull(hint)
        if (resolved != null) known += resolved.name else unknown += hint
    }
    val hasImportSource =
        !subscriptionUrl.isNullOrBlank() || !rawInput.isNullOrBlank() || !resolvedConfigJson.isNullOrBlank()
    return BackupProfileCompatibility(
        payload = this,
        knownProtocolNames = known,
        unknownProtocolNames = unknown,
        importable = hasImportSource,
    )
}

/**
 * The raw input the ordinary import pipeline should re-import this profile from.
 *
 * The stored resolved config wins so restore works offline and byte-identically; the
 * subscription URL is a last resort for legacy backups without a stored config — it is
 * kept as metadata (see relink in ProfileBackupSupport) so manual refresh still works.
 */
fun BackupProfilePayload.restoreRawInput(): String? =
    resolvedConfigJson?.takeIf { it.isNotBlank() }
        ?: rawInput?.takeIf { it.isNotBlank() }
        ?: subscriptionUrl?.takeIf { it.isNotBlank() }

private fun BackupDocument.migratedProfileTokens(): BackupDocument =
    copy(
        profiles =
        profiles.map { profile ->
            profile.copy(
                sourceType = migrateStoredProfileSourceToken(profile.sourceType),
                protocolHints = profile.protocolHints.map(::migrateStoredProtocolToken),
            )
        },
    )
