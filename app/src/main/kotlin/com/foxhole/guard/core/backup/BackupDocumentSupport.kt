package com.foxhole.guard.core.backup

import com.foxhole.core.model.AppLockSettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.InstalledAppInventoryAudit
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
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

// The document serialized *inside* the password-encrypted portable envelope. Unknown fields are
// tolerated only within a schema version this build understands.

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
    val backupId: Long? = null,
    val name: String,
    val sourceType: String,
    val protocolHints: List<String> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val protocolOptionEnabled: Map<String, Boolean> = emptyMap(),
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
        ui =
        ui.copy(
            alphaNoticeShownVersionCode = 0,
            profileListOrder = emptyList(),
            trafficMapHistoryClearedAtMs = 0L,
        ),
        lastActiveProfile = null,
        smartProfilePreferences = emptyList(),
        profileTrafficTotals = emptyList(),
        installedAppInventoryAudit = InstalledAppInventoryAudit(),
        // A private-repository bearer token is destination-local even though the envelope is
        // encrypted: importing it on another phone silently broadens that phone's GitHub access.
        updateSources = updateSources.copy(appReleasesToken = ""),
        networkRules =
        networkRules.copy(
            useWifiProfile = false,
            wifiProfileId = null,
            wifiProtocolOptionId = null,
            useCellularProfile = false,
            cellularProfileId = null,
            cellularProtocolOptionId = null,
        ),
        dns = dns.copy(filtersUpdatedAt = null, filtersCheckedAt = null),
        privacyRoute =
        privacyRoute.copy(
            bridgesUpdatedAt = null,
            bridgesCheckedAt = null,
            bridgesLastUpdateSuccess = null,
        ),
        expert =
        expert.copy(
            // Pending blocks belong to this device and are carried by the destination merge. Do
            // not leave their derived BLOCK assignments behind after removing the pending list.
            appAssignments = expert.appAssignments - expert.pendingQuarantinePackages.toSet(),
            quarantineKnownApplications = emptyList(),
            pendingQuarantinePackages = emptyList(),
            pendingQuarantineAppDetails = emptyList(),
            localSurfaces =
            expert.localSurfaces.copy(
                auth = LocalAuthSettings(),
                lanAuth = LocalAuthSettings(),
            ),
        ),
        appTrafficUsageAccessConsent = false,
        usageTrackingStartedAt = 0L,
        // The keybox never leaves the device; restoring PASSWORD without its hardware-bound box
        // would soft-brick the app, so the file carries no app-lock state.
        appLock = AppLockSettings(),
    )

/** Merge transferable settings while preserving every credential/runtime fact owned by this device. */
fun mergeRestoredSettings(
    backup: Settings,
    current: Settings,
    quarantineKnownApplicationsOverride: List<KnownApplicationIdentity>? = null,
): Settings {
    val currentPending = current.expert.pendingQuarantinePackages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    val pendingAssignments = currentPending.associateWith { AppTunnelLane.BLOCK }
    val restoredExpert = backup.expert.copy(
        appAssignments = backup.expert.appAssignments + pendingAssignments,
        firewallEnabled = backup.expert.firewallEnabled || currentPending.isNotEmpty(),
        quarantineKnownApplications =
        quarantineKnownApplicationsOverride ?: current.expert.quarantineKnownApplications,
        pendingQuarantinePackages = currentPending,
        pendingQuarantineAppDetails = current.expert.pendingQuarantineAppDetails,
        blockedPackagesEnabled = backup.expert.blockedPackagesEnabled || currentPending.isNotEmpty(),
        blockAppsAlways = backup.expert.blockAppsAlways || currentPending.isNotEmpty(),
        localSurfaces = backup.expert.localSurfaces.copy(
            auth = current.expert.localSurfaces.auth,
            lanAuth = current.expert.localSurfaces.lanAuth,
        ),
    )
    return backup.copy(
        ui =
        backup.ui.copy(
            alphaNoticeShownVersionCode = current.ui.alphaNoticeShownVersionCode,
            profileListOrder = current.ui.profileListOrder,
            trafficMapHistoryClearedAtMs = current.ui.trafficMapHistoryClearedAtMs,
        ),
        updateSources =
        backup.updateSources.copy(
            appReleasesToken = current.updateSources.appReleasesToken,
        ),
        expert = restoredExpert,
        appLock = current.appLock,
        lastActiveProfile = current.lastActiveProfile,
        smartProfilePreferences = current.smartProfilePreferences,
        profileTrafficTotals = current.profileTrafficTotals,
        installedAppInventoryAudit = current.installedAppInventoryAudit,
        appTrafficUsageAccessConsent = current.appTrafficUsageAccessConsent,
        usageTrackingStartedAt = current.usageTrackingStartedAt,
    )
}

fun encodeBackupDocument(document: BackupDocument): String {
    // Enforce the secret boundary here as well as at the UI caller. A future exporter that builds a
    // BackupDocument directly must not be able to write the private-release PAT into plain JSON.
    val sanitized = document.sanitizedSettings()
    return backupJson.encodeToString(BackupDocument.serializer(), sanitized) + "\n"
}

sealed interface BackupParseResult {
    data class Success(val document: BackupDocument) : BackupParseResult

    enum class Failure : BackupParseResult {
        NOT_A_BACKUP,
        UNSUPPORTED_FORMAT_VERSION,
        UNSUPPORTED_SETTINGS_SCHEMA,
        UNSUPPORTED_ENCRYPTION,
        WRONG_PASSWORD_OR_CORRUPT,
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
        !document.hasCompatibleSettingsSchema() -> BackupParseResult.Failure.UNSUPPORTED_SETTINGS_SCHEMA
        else -> BackupParseResult.Success(document.migratedProfileTokens().sanitizedSettings())
    }
}

private fun BackupDocument.hasCompatibleSettingsSchema(): Boolean {
    val metadataSchema = settingsSchemaVersion
    val payloadSchema = settings?.schemaVersion
    if (metadataSchema != null && payloadSchema != null && metadataSchema != payloadSchema) return false
    return listOfNotNull(metadataSchema, payloadSchema).all { schema -> schema <= SETTINGS_SCHEMA_VERSION }
}

private fun BackupDocument.sanitizedSettings(): BackupDocument =
    copy(settings = settings?.sanitizedForBackup())

/** foxhole_guard_backup_all_2026-07-11_0615.foxhole-backup — encrypted, portable envelope. */
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
    return "foxhole_guard_backup_${scope}_$stamp.foxhole-backup"
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
