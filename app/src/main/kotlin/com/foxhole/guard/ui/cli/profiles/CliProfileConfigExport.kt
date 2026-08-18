package com.foxhole.guard.ui.cli.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.profile.exportableProfileChoices
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfileExportSelectionRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CliProfileConfigExport(
    val profileId: Long,
    val profileName: String,
    val fileBaseName: String,
    val configs: List<String>,
    val isSubscription: Boolean,
)

internal val CliProfileConfigExport.fileExtension: String
    get() = when {
        isSubscription -> "txt"
        configs.size == 1 && configs.single().trimStart().startsWith("{") -> "json"
        else -> "txt"
    }

internal val CliProfileConfigExport.mimeType: String
    get() = if (fileExtension == "json") "application/json" else "text/plain"

internal val CliProfileConfigExport.fileName: String
    get() = "$fileBaseName.$fileExtension"

internal fun CliProfileConfigExport.joinedConfigs(): String =
    configs.joinToString(separator = "\n") { config -> config.trimEnd() }

internal suspend fun HomeViewModel.cliResolveExportConfigs(
    requests: List<ProfileExportSelectionRequest>,
): List<CliProfileConfigExport> =
    requests.mapNotNull { request ->
        val profile =
            container.profileRepository.getProfile(request.profileId) ?: return@mapNotNull null
        val choices =
            exportableProfileChoices(profile).filter { it.selectionKey in request.selectionKeys }
        if (choices.isEmpty()) {
            return@mapNotNull null
        }
        CliProfileConfigExport(
            profileId = profile.id,
            profileName = profile.name,
            fileBaseName = cliSanitizeExportName(profile.name),
            configs = choices.map { choice ->
                container.profileRepository.getResolvedConfig(profile.id, choice.protocolOptionId)
            },
            isSubscription = profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL ||
                exportableProfileChoices(profile).size > 1,
        )
    }

internal fun cliCopyConfigsToClipboard(
    context: Context,
    exports: List<CliProfileConfigExport>,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val text =
        exports
            .flatMap { export -> export.configs }
            .joinToString(separator = "\n") { config -> config.trim() }
    val clip =
        ClipData.newPlainText("FoxHole profiles", text).apply {
            description.extras =
                PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
        }
    clipboard.setPrimaryClip(clip)
}

internal suspend fun cliWriteExportToUri(
    resolver: ContentResolver,
    uri: Uri,
    text: String,
): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write((text.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
    }

internal suspend fun cliWriteExportsToTree(
    resolver: ContentResolver,
    treeUri: Uri,
    exports: List<CliProfileConfigExport>,
): Int =
    withContext(Dispatchers.IO) {
        val parent =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        exports.count { export ->
            runCatching {
                val doc =
                    DocumentsContract.createDocument(
                        resolver,
                        parent,
                        export.mimeType,
                        export.fileName,
                    ) ?: return@runCatching false
                resolver.openOutputStream(doc, "wt")?.use { stream ->
                    stream.write(
                        (export.joinedConfigs().trimEnd() + "\n").toByteArray(Charsets.UTF_8),
                    )
                } != null
            }.getOrDefault(false)
        }
    }

internal fun cliGenerateQrBitmap(
    text: String,
    sizePx: Int,
): Bitmap? =
    runCatching {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        val pixels =
            IntArray(matrix.width * matrix.height) { index ->
                val x = index % matrix.width
                val y = index / matrix.width
                if (matrix.get(x, y)) QR_INK else QR_PAPER
            }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

private fun cliSanitizeExportName(value: String): String =
    value
        .trim()
        .replace(Regex("""[^\p{L}\p{N}._-]+"""), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(64)
        .ifBlank { "foxhole-profile" }

private const val QR_INK = 0xFF000000.toInt()
private const val QR_PAPER = 0xFFFFFFFF.toInt()
