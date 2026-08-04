package com.foxhole.guard.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.foxhole.core.model.Profile
import com.foxhole.core.profile.ProfileExportChoice
import com.foxhole.core.profile.exportableProfileChoices

data class ProfileExportSelectionRequest(
    val profileId: Long,
    val selectionKeys: Set<String>,
)

internal data class ProfilesExportSelectionState(
    val selectedKeysByProfileId: Map<Long, Set<String>> = emptyMap(),
    val expandedSmartProfileIds: Set<Long> = emptySet(),
)

internal val ProfilesExportSelectionStateSaver: Saver<ProfilesExportSelectionState, Any> =
    listSaver(
        save = { state ->
            listOf(
                state.selectedKeysByProfileId
                    .toSortedMap()
                    .map { (profileId, selectionKeys) ->
                        "$profileId:${selectionKeys.sorted().joinToString(separator = ",")}"
                    },
                state.expandedSmartProfileIds.sorted(),
            )
        },
        restore = { restored ->
            val selectedEntries =
                (restored.getOrNull(0) as? List<*>)
                    .orEmpty()
                    .mapNotNull { entry -> entry as? String }
                    .mapNotNull { entry ->
                        val separatorIndex = entry.indexOf(':')
                        if (separatorIndex <= 0) {
                            return@mapNotNull null
                        }
                        val profileId = entry.substring(0, separatorIndex).toLongOrNull() ?: return@mapNotNull null
                        val selectionKeys =
                            entry
                                .substring(separatorIndex + 1)
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .toSet()
                        profileId to selectionKeys
                    }.filter { (_, selectionKeys) -> selectionKeys.isNotEmpty() }
                    .toMap()
            val expandedProfileIds =
                (restored.getOrNull(1) as? List<*>)
                    .orEmpty()
                    .mapNotNull { entry ->
                        when (entry) {
                            is Long -> entry
                            is Int -> entry.toLong()
                            else -> null
                        }
                    }.toSet()
            ProfilesExportSelectionState(
                selectedKeysByProfileId = selectedEntries,
                expandedSmartProfileIds = expandedProfileIds,
            )
        },
    )

internal fun ProfilesExportSelectionState.selectedKeys(profileId: Long): Set<String> =
    selectedKeysByProfileId[profileId].orEmpty()

internal fun ProfilesExportSelectionState.isReady(): Boolean =
    selectedKeysByProfileId.values.any { selectionKeys -> selectionKeys.isNotEmpty() }

internal fun ProfilesExportSelectionState.requests(): List<ProfileExportSelectionRequest> =
    selectedKeysByProfileId
        .asSequence()
        .filter { (_, selectionKeys) -> selectionKeys.isNotEmpty() }
        .sortedBy(Map.Entry<Long, Set<String>>::key)
        .map { (profileId, selectionKeys) ->
            ProfileExportSelectionRequest(
                profileId = profileId,
                selectionKeys = selectionKeys.toSortedSet(),
            )
        }.toList()

internal fun ProfilesExportSelectionState.toggleSingleProfile(profile: Profile): ProfilesExportSelectionState {
    val exportChoices = exportableProfileChoices(profile)
    require(exportChoices.size == 1) { "single profile toggle requires exactly one export choice" }
    val selectionKey = exportChoices.single().selectionKey
    val nextSelection =
        if (selectionKey in selectedKeys(profile.id)) {
            emptySet()
        } else {
            setOf(selectionKey)
        }
    return withSelection(profile.id, nextSelection)
}

internal fun ProfilesExportSelectionState.toggleSmartProfileAll(profile: Profile): ProfilesExportSelectionState {
    val exportChoices = exportableProfileChoices(profile)
    require(exportChoices.size > 1) { "smart profile toggle requires multiple export choices" }
    val allKeys = exportChoices.map(ProfileExportChoice::selectionKey).toSet()
    val nextSelection =
        if (selectedKeys(profile.id) == allKeys) {
            emptySet()
        } else {
            allKeys
        }
    return withSelection(profile.id, nextSelection)
}

internal fun ProfilesExportSelectionState.toggleSmartProfileChoice(
    profile: Profile,
    selectionKey: String,
): ProfilesExportSelectionState {
    val validSelectionKeys = exportableProfileChoices(profile).map(ProfileExportChoice::selectionKey).toSet()
    require(selectionKey in validSelectionKeys) { "unknown export selection key" }
    val currentSelection = selectedKeys(profile.id)
    val nextSelection =
        if (selectionKey in currentSelection) {
            currentSelection - selectionKey
        } else {
            currentSelection + selectionKey
        }
    return withSelection(profile.id, nextSelection)
}

internal fun ProfilesExportSelectionState.toggleSmartProfileExpanded(profileId: Long): ProfilesExportSelectionState =
    copy(
        expandedSmartProfileIds =
        if (profileId in expandedSmartProfileIds) {
            expandedSmartProfileIds - profileId
        } else {
            expandedSmartProfileIds + profileId
        },
    )

internal fun ProfilesExportSelectionState.isSmartProfileExpanded(profileId: Long): Boolean =
    profileId in expandedSmartProfileIds

internal fun ProfilesExportSelectionState.smartProfileSelectionState(profile: Profile): SmartProfileExportSelectionState {
    val totalChoices = exportableProfileChoices(profile).size
    val selectedCount = selectedKeys(profile.id).size
    return when {
        selectedCount <= 0 -> SmartProfileExportSelectionState.NONE
        selectedCount >= totalChoices -> SmartProfileExportSelectionState.ALL
        else -> SmartProfileExportSelectionState.PARTIAL
    }
}

internal fun ProfilesExportSelectionState.pruneTo(profiles: List<Profile>): ProfilesExportSelectionState {
    val validSelectionKeysByProfileId =
        profiles.associate { profile ->
            profile.id to exportableProfileChoices(profile).map(ProfileExportChoice::selectionKey).toSet()
        }
    val normalizedSelections =
        selectedKeysByProfileId
            .mapNotNull { (profileId, selectionKeys) ->
                val validSelectionKeys = validSelectionKeysByProfileId[profileId] ?: return@mapNotNull null
                val normalizedSelection = selectionKeys.intersect(validSelectionKeys)
                profileId.takeIf { normalizedSelection.isNotEmpty() }?.let { it to normalizedSelection }
            }.toMap()
    val validProfileIds = validSelectionKeysByProfileId.keys
    return copy(
        selectedKeysByProfileId = normalizedSelections,
        expandedSmartProfileIds = expandedSmartProfileIds.intersect(validProfileIds),
    )
}

private fun ProfilesExportSelectionState.withSelection(
    profileId: Long,
    selectionKeys: Set<String>,
): ProfilesExportSelectionState =
    copy(
        selectedKeysByProfileId =
        if (selectionKeys.isEmpty()) {
            selectedKeysByProfileId - profileId
        } else {
            selectedKeysByProfileId + (profileId to selectionKeys.toSortedSet())
        },
    )

internal enum class SmartProfileExportSelectionState {
    NONE,
    PARTIAL,
    ALL,
}
