package com.foxhole.guard.core.backup

import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.PreparedProfileImport
import com.foxhole.guard.core.data.PreparedProfileImportChangedException
import com.foxhole.guard.core.data.ProfileEntity
import com.foxhole.guard.core.data.ProfileMutationLease
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.ProfileSecretUndo
import com.foxhole.guard.core.data.ValidatedProfileImport
import com.foxhole.guard.core.data.applyPreparedImport
import com.foxhole.guard.core.data.prepareProfileImport
import com.foxhole.guard.core.data.validateImportForCommit
import com.foxhole.guard.core.settings.SettingsRestoreSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun ProfileRepository.collectBackupProfiles(): List<BackupProfilePayload> =
    mutationCoordinator.readOwned { owner -> owner.secrets(secretMutationMutex) { collectBackupProfilesLocked() } }

private suspend fun ProfileRepository.collectBackupProfilesLocked(): List<BackupProfilePayload> {
    return dao.getAllProfiles().map { entity ->
        val secret = secretStore.read(entity.secretRef)
            ?: error("profile secret is unreadable for profile ${entity.id}")
        val optionHints = secret.protocolOptions.map { option -> option.protocolHint.name }
        BackupProfilePayload(
            backupId = entity.id,
            name = entity.name,
            sourceType = entity.sourceType,
            protocolHints = optionHints.ifEmpty { listOf(entity.protocolHint) },
            selectedProtocolOptionId = secret.selectedProtocolOptionId,
            protocolOptionEnabled = secret.protocolOptions.associate { option -> option.id to option.enabled },
            isActive = entity.isActive,
            subscriptionUrl = secret.subscriptionUrl,
            rawInput = secret.rawInput,
            resolvedConfigJson = secret.resolvedConfigJson,
            insecureTlsConsentGranted = secret.insecureTlsConsentGranted == true,
        )
    }
}

internal data class BackupExportSnapshot(
    val profiles: List<BackupProfilePayload>,
    val settings: Settings?,
    val settingsSchemaVersion: Int,
)

internal suspend fun ProfileRepository.captureBackupExportSnapshot(
    includeProfiles: Boolean,
    includeSettings: Boolean,
): BackupExportSnapshot {
    settingsRepository.current()
    return mutationCoordinator.readOwned { owner ->
        owner.secrets(secretMutationMutex) {
            settingsRepository.readRestoreSnapshot(owner) { current ->
                BackupExportSnapshot(
                    profiles = if (includeProfiles) collectBackupProfilesLocked() else emptyList(),
                    settings = current.takeIf { includeSettings },
                    settingsSchemaVersion = current.schemaVersion,
                )
            }
        }
    }
}

data class BackupProfileRestoreResult(
    val name: String,
    val restored: Boolean,
    val backupId: Long? = null,
    val restoredId: Long? = null,
    val error: String? = null,
)

internal data class ProfileBackupRestoreCheckpoint(
    val entities: List<ProfileEntity>,
    val secrets: Map<String, StoredProfileSecret>,
)

/** Rollback retains the original failure and cannot be cancelled after apply has touched storage. */
internal suspend fun <Checkpoint, Result> executeFailAtomicBackupRestore(
    capture: suspend () -> Checkpoint,
    apply: suspend () -> Result,
    rollback: suspend (Checkpoint) -> Unit,
): Result {
    val checkpoint = capture()
    return try {
        apply()
    } catch (primary: Throwable) {
        val rollbackFailure =
            runCatching {
                withContext(NonCancellable) { rollback(checkpoint) }
            }.exceptionOrNull()
        val failures = (rollbackFailure as? BackupRestoreRollbackFailures)?.failures ?: listOfNotNull(rollbackFailure)
        failures.forEach { if (it !== primary) primary.addSuppressed(it) }
        throw primary
    }
}

private data class PreparedBackupProfile(
    val payload: BackupProfilePayload,
    val prepared: PreparedProfileImport?,
    val failure: BackupProfileRestoreResult?,
)

private data class PreparedQuarantineBaseline(
    val revision: Long,
    val audit: com.foxhole.core.model.InstalledAppInventoryAudit,
    val identities: List<KnownApplicationIdentity>?,
)

suspend fun ProfileRepository.restoreBackupDocument(document: BackupDocument): List<BackupProfileRestoreResult> {
    val initial = settingsRepository.current()
    val baseline = prepareQuarantineBaseline(document, initial)
    val prepared = document.profiles.map { payload -> prepareBackupProfile(payload) }
    return withProfileMutation { owner ->
        owner.secrets(secretMutationMutex) {
            settingsRepository.withRestoreSettings(owner) { session ->
                val validated = prepared.map { item -> item.prepared?.let { validateImportForCommit(it, owner) } }
                val checkpoint = captureProfileCheckpoint(checkpointAccess(owner))
                executeFailAtomicBackupRestore(
                    capture = { checkpoint },
                    apply = {
                        val results = owner.restoreTransaction(database) {
                            val imported = prepared.mapIndexed { index, item ->
                                item.failure ?: restorePreparedProfile(item.payload, requireNotNull(validated[index]), owner, session, index)
                            }
                            val activeIndex = document.profiles.indexOfFirst(BackupProfilePayload::isActive)
                            imported.getOrNull(activeIndex)?.restoredId?.let { setActiveProfileLocked(it, owner) }
                            document.settings?.let { backupSettings ->
                                val current = session.current()
                                val override = baselineForCommit(baseline, current, backupSettings)
                                session.update { live -> mergeRestoredSettings(backupSettings, live, override) }
                            }
                            session.persist()
                            imported
                        }
                        session.publishCommitted()
                        owner.markRestoreCommitted()
                        results
                    },
                    rollback = { captured ->
                        owner.markRestoreRolledBack()
                        try {
                            restoreProfileCheckpointSettled(
                                checkpointAccess(owner),
                                captured,
                                owner.touchedSecrets(),
                                session::rollback
                            )
                        } finally {
                            owner.markRestoreRolledBack()
                        }
                    },
                )
            }
        }
    }
}

suspend fun ProfileRepository.restoreBackupProfiles(document: BackupDocument): List<BackupProfileRestoreResult> =
    restoreBackupDocument(document.copy(settings = null))

private suspend fun ProfileRepository.prepareBackupProfile(payload: BackupProfilePayload): PreparedBackupProfile {
    val raw = payload.restoreRawInput()
    if (raw == null) {
        return PreparedBackupProfile(
            payload,
            null,
            BackupProfileRestoreResult(
                payload.name,
                false,
                payload.backupId,
                error = "empty payload",
            )
        )
    }
    return try {
        val fetched = raw == payload.subscriptionUrl
        PreparedBackupProfile(
            payload,
            prepareProfileImport(
                rawInput = raw,
                preferredName = payload.name.takeUnless { fetched },
                allowInsecureTlsForProfile = payload.insecureTlsConsentGranted,
            ),
            null
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        PreparedBackupProfile(
            payload,
            null,
            BackupProfileRestoreResult(
                payload.name,
                false,
                payload.backupId,
                error = error.message ?: error.javaClass.simpleName,
            )
        )
    }
}

private suspend fun ProfileRepository.restorePreparedProfile(
    payload: BackupProfilePayload,
    prepared: ValidatedProfileImport,
    owner: ProfileMutationLease,
    session: SettingsRestoreSession,
    index: Int,
): BackupProfileRestoreResult {
    val connection = database.openHelper.writableDatabase
    val savepoint = "backup_profile_$index"
    val settingsBefore = session.current()
    val effectsBefore = owner.effectCheckpoint()
    val undo = ProfileSecretUndo(secretStore)
    owner.bindUndo(undo)
    return try {
        connection.execSQL("SAVEPOINT $savepoint")
        val profile = applyPreparedImport(prepared)
        if (prepared.prepared.rawInput != payload.subscriptionUrl) {
            payload.subscriptionUrl?.takeIf(String::isNotBlank)?.let { relinkSubscriptionLocked(profile.id, it, owner) }
        }
        payload.protocolOptionEnabled.filterValues { !it }.keys.forEach { option ->
            setProfileProtocolOptionEnabledLocked(profile.id, option, false, owner)
        }
        payload.selectedProtocolOptionId?.let { selectProfileProtocolOptionLocked(profile.id, it, owner) }
        connection.execSQL("RELEASE SAVEPOINT $savepoint")
        BackupProfileRestoreResult(payload.name, true, payload.backupId, profile.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        try {
            withContext(NonCancellable) {
                connection.execSQL("ROLLBACK TO SAVEPOINT $savepoint")
                connection.execSQL("RELEASE SAVEPOINT $savepoint")
                undo.restore()
                session.restoreWorking(settingsBefore)
                owner.rollbackEffectsTo(effectsBefore)
            }
        } catch (rollbackFailure: Throwable) {
            if (rollbackFailure !== error) error.addSuppressed(rollbackFailure)
            throw error
        }
        BackupProfileRestoreResult(
            payload.name,
            false,
            payload.backupId,
            error = error.message ?: error.javaClass.simpleName
        )
    } finally {
        owner.unbindUndo(undo)
    }
}

private suspend fun ProfileRepository.prepareQuarantineBaseline(document: BackupDocument, current: Settings): PreparedQuarantineBaseline {
    val needed = document.settings?.expert?.newAppQuarantineEnabled == true && current.expert.quarantineKnownApplications.isEmpty()
    val identities = if (needed) settingsRepository.captureKnownApplicationsForQuarantine() else null
    val after = settingsRepository.current()
    if (after.expert.quarantinePolicyRevision != current.expert.quarantinePolicyRevision || after.installedAppInventoryAudit != current.installedAppInventoryAudit) {
        throw PreparedProfileImportChangedException()
    }
    return PreparedQuarantineBaseline(
        after.expert.quarantinePolicyRevision,
        after.installedAppInventoryAudit,
        identities
    )
}

private fun baselineForCommit(baseline: PreparedQuarantineBaseline, current: Settings, incoming: Settings): List<KnownApplicationIdentity>? {
    if (!incoming.expert.newAppQuarantineEnabled || current.expert.quarantineKnownApplications.isNotEmpty()) return null
    if (current.expert.quarantinePolicyRevision != baseline.revision || current.installedAppInventoryAudit != baseline.audit) {
        throw PreparedProfileImportChangedException()
    }
    return requireNotNull(baseline.identities) { "quarantine baseline preparation is unavailable" }
}

private suspend fun ProfileRepository.relinkSubscriptionLocked(profileId: Long, source: String, owner: ProfileMutationLease) {
    owner.requireOwner(mutationCoordinator)
    val entity = dao.getById(profileId) ?: error("profile not found")
    val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
    if (secret.subscriptionUrl != source) {
        owner.recordingSecrets(
            secretStore
        ).write(entity.secretRef, secret.copy(subscriptionUrl = source))
    }
    if (entity.sourceType != ProfileSourceType.SUBSCRIPTION_URL.name) {
        dao.updateSourceType(
            profileId,
            ProfileSourceType.SUBSCRIPTION_URL.name
        )
    }
}

private fun ProfileRepository.checkpointAccess(owner: ProfileMutationLease) = object : ProfileBackupCheckpointAccess {
    override suspend fun profiles(): List<ProfileEntity> {
        owner.requireOwner(
            mutationCoordinator
        )
        return dao.getAllProfiles()
    }
    override suspend fun readSecret(ref: String) = secretStore.read(ref)
    override suspend fun writeSecret(ref: String, secret: StoredProfileSecret) = secretStore.write(ref, secret)
    override suspend fun replaceProfiles(rows: List<ProfileEntity>) = owner.transaction(database) {
        dao.deleteAll()
        rows.forEach { dao.insert(it) }
    }
    override suspend fun cleanupSecret(ref: String) { secretStore.delete(ref) }
}
