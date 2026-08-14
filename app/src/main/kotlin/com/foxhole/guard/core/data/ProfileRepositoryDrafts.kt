package com.foxhole.guard.core.data

import androidx.room.withTransaction
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Persists an intentionally incomplete local profile for the structured editor. A draft must not
 * go through the strict import parser: it correctly rejects an empty server, while the editor needs
 * that exact empty field as its starting point. Runtime updates still pass through the ordinary
 * sanitizer, so an unfinished draft cannot become a tunnel by bypassing validation here.
 */
suspend fun ProfileRepository.createDraftProfile(
    name: String,
    protocolHint: ProtocolHint,
    configJson: String,
): Profile =
    profileImportMutex.withLock {
        require(protocolHint != ProtocolHint.CUSTOM_CONFIG) { "a draft requires a supported protocol" }
        val normalizedConfig = configJson.trim().also { require(it.isNotEmpty()) { "draft config is empty" } }
        val secretRef = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val firstProfile = dao.count() == 0
        val entity =
            ProfileEntity(
                name = name.trim().ifBlank { protocolHint.name.lowercase() },
                sourceType = ProfileSourceType.RAW_CONFIG_JSON.name,
                secretRef = secretRef,
                protocolHint = protocolHint.name,
                lastUpdatedAt = now,
                lastEtag = null,
                isActive = firstProfile,
            )
        val id =
            executeSerializedSecretFirstMutation(
                secretStore = secretStore,
                stagedWrites =
                listOf(
                    StagedProfileSecretWrite(
                        secretRef = secretRef,
                        value =
                        StoredProfileSecret(
                            rawInput = normalizedConfig,
                            resolvedConfigJson = normalizedConfig,
                        ),
                    ),
                ),
                onCleanupFailure = ::recordSecretCleanupFailure,
            ) {
                database.withTransaction {
                    if (firstProfile) dao.clearActive()
                    dao.insert(entity)
                }
            }
        val saved = requireProfile(id)
        if (saved.isActive) persistCachedActiveProfile(saved)
        diagnosticsLogger.record("profile", "blank profile draft created")
        saved
    }
