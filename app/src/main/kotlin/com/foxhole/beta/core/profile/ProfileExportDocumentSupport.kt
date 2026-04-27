package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val CURRENT_PROFILE_EXPORT_SELECTION_KEY = "__current__"
const val PROFILE_EXPORT_DIR_NAME = "profile-export"

data class ProfileExportRequest(
    val profileId: Long,
    val selectionKeys: Set<String>,
)

data class ProfileExportChoice(
    val selectionKey: String,
    val protocolOptionId: String?,
    val displayName: String,
)

data class ProfileExportPayload(
    val sourceProfileName: String,
    val displayName: String,
    val protocolOptionId: String?,
    val configJson: String,
)

data class PreparedProfileExport(
    val file: File,
    val fileName: String,
    val mimeType: String,
)

fun exportableProfileChoices(profile: Profile): List<ProfileExportChoice> {
    val supportedOptions = MultiProtocolProfileSupport.supportedOptions(profile)
    if (supportedOptions.isNotEmpty()) {
        return supportedOptions.map { option ->
            ProfileExportChoice(
                selectionKey = option.id,
                protocolOptionId = option.id,
                displayName = exportChoiceDisplayName(option),
            )
        }
    }
    return listOf(
        ProfileExportChoice(
            selectionKey = CURRENT_PROFILE_EXPORT_SELECTION_KEY,
            protocolOptionId = null,
            displayName = exportChoiceDisplayName(profile.protocolHint),
        ),
    )
}

fun createProfileExportArtifact(
    targetDir: File,
    payloads: List<ProfileExportPayload>,
    nowProvider: () -> Long = System::currentTimeMillis,
): PreparedProfileExport {
    require(payloads.isNotEmpty()) { "payloads must not be empty" }
    targetDir.mkdirs()
    cleanupStaleProfileExports(targetDir, nowProvider())
    return if (payloads.size == 1) {
        val payload = payloads.single()
        val normalizedProfileName = normalizedProfileExportName(payload.sourceProfileName)
        val optionSuffix = sanitizeProfileExportSegment(payload.displayName)
        val fileName =
            when {
                optionSuffix.isBlank() -> "$normalizedProfileName.json"
                optionSuffix.equals(normalizedProfileName, ignoreCase = true) -> "$normalizedProfileName.json"
                else -> "$normalizedProfileName-$optionSuffix.json"
            }
        val targetFile = File(targetDir, fileName)
        targetFile.writeText(payload.configJson.trimEnd() + "\n", Charsets.UTF_8)
        PreparedProfileExport(
            file = targetFile,
            fileName = fileName,
            mimeType = "application/json",
        )
    } else {
        val normalizedProfileNames = payloads.map { normalizedProfileExportName(it.sourceProfileName) }.distinct()
        val archivePrefix =
            normalizedProfileNames.singleOrNull()
                ?: "foxhole-profiles"
        val archiveName = "$archivePrefix-export-${nowProvider()}.zip"
        val targetFile = File(targetDir, archiveName)
        val usedEntryNames = linkedSetOf<String>()
        ZipOutputStream(targetFile.outputStream().buffered()).use { zip ->
            payloads.forEach { payload ->
                val entryName = nextEntryName(payload = payload, usedEntryNames = usedEntryNames)
                zip.putNextEntry(ZipEntry(entryName))
                zip.write((payload.configJson.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        PreparedProfileExport(
            file = targetFile,
            fileName = archiveName,
            mimeType = "application/zip",
        )
    }
}

fun cleanupProfileExportArtifacts(targetDir: File) {
    targetDir.listFiles()
        ?.filter(File::isFile)
        ?.forEach(File::delete)
}

fun deleteProfileExportArtifact(document: PreparedProfileExport) {
    document.file.delete()
}

internal fun exportSelectionSummary(
    selectedCount: Int,
    totalCount: Int,
): String =
    when {
        totalCount <= 1 -> "1 / 1"
        selectedCount <= 0 -> "0 / $totalCount"
        else -> "$selectedCount / $totalCount"
    }

private fun exportChoiceDisplayName(option: ProfileProtocolOption): String =
    option.displayName.ifBlank { exportChoiceDisplayName(option.protocolHint) }

private fun exportChoiceDisplayName(protocolHint: ProtocolHint): String =
    protocolHint.name
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .split(' ')
        .joinToString(separator = " ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }.ifBlank { "Current" }

private fun nextEntryName(
    payload: ProfileExportPayload,
    usedEntryNames: MutableSet<String>,
): String {
    val profileName = normalizedProfileExportName(payload.sourceProfileName)
    val baseName =
        buildString {
            append(profileName)
            val normalizedOption = sanitizeProfileExportSegment(payload.displayName)
            if (normalizedOption.isNotBlank() && !normalizedOption.equals(profileName, ignoreCase = true)) {
                append('-')
                append(normalizedOption)
            }
        }.ifBlank { "foxhole-profile" }
    var candidate = "$baseName.json"
    var suffix = 2
    while (!usedEntryNames.add(candidate)) {
        candidate = "$baseName-$suffix.json"
        suffix += 1
    }
    return candidate
}

private fun normalizedProfileExportName(profileName: String): String =
    sanitizeProfileExportSegment(profileName).ifBlank { "foxhole-profile" }

private fun sanitizeProfileExportSegment(value: String): String =
    value
        .trim()
        .replace(Regex("""[^\p{L}\p{N}._-]+"""), "-")
        .replace(Regex("""-+"""), "-")
        .trim('-')
        .take(64)

private fun cleanupStaleProfileExports(
    targetDir: File,
    now: Long,
) {
    val cutoff = now - PROFILE_EXPORT_TTL_MS
    targetDir.listFiles()
        ?.filter { it.isFile && it.lastModified() < cutoff }
        ?.forEach(File::delete)
}

private const val PROFILE_EXPORT_TTL_MS = 5L * 60L * 1000L
