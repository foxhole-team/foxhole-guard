package com.foxhole.beta.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.foxhole.beta.core.profile.PreparedProfileExport
import com.foxhole.beta.core.profile.ProfileExportPayload
import com.foxhole.beta.core.profile.ProfileExportRequest
import com.foxhole.beta.core.profile.createProfileExportArtifact
import com.foxhole.beta.core.profile.exportableProfileChoices
import java.io.File

internal suspend fun HomeViewModel.createProfileExportInternal(
    profileId: Long,
    selectionKeys: Set<String>,
): PreparedProfileExport = createProfileExportInternal(listOf(ProfileExportRequest(profileId, selectionKeys)))

internal suspend fun HomeViewModel.createProfileExportInternal(
    requests: List<ProfileExportRequest>,
): PreparedProfileExport {
    require(requests.isNotEmpty()) { "at least one profile export request is required" }
    val payloads = buildList {
        requests.forEach { request ->
            addAll(resolveProfileExportPayloads(request))
        }
    }
    return createProfileExportArtifact(
        targetDir = File(getApplication<Application>().cacheDir, PROFILE_EXPORT_DIR_NAME),
        payloads = payloads,
    )
}

internal fun HomeViewModel.exportProfileShareIntentInternal(document: PreparedProfileExport): Intent {
    val uri =
        FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            document.file,
        )
    return Intent(Intent.ACTION_SEND).apply {
        type = document.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, getApplication<Application>().getString(com.foxhole.beta.R.string.profile_export_share_subject))
        putExtra(Intent.EXTRA_TEXT, getApplication<Application>().getString(com.foxhole.beta.R.string.profile_export_share_text))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private suspend fun HomeViewModel.resolveProfileExportPayloads(request: ProfileExportRequest): List<ProfileExportPayload> {
    val profile = container.profileRepository.getProfile(request.profileId) ?: error("profile not found")
    val exportChoices = exportableProfileChoices(profile).filter { it.selectionKey in request.selectionKeys }
    require(exportChoices.isNotEmpty()) { "at least one protocol must be selected for ${profile.name}" }
    return exportChoices.map { choice ->
        ProfileExportPayload(
            sourceProfileName = profile.name,
            displayName = choice.displayName,
            protocolOptionId = choice.protocolOptionId,
            configJson = container.profileRepository.getResolvedConfig(request.profileId, choice.protocolOptionId),
        )
    }
}

private const val PROFILE_EXPORT_DIR_NAME = "profile-export"
