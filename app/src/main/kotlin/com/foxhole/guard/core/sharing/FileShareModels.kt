package com.foxhole.guard.core.sharing

data class FileShareUiItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val expiresAtMs: Long,
    val maxDownloads: Int,
    val downloadsAuthorized: Int,
    val passwordProtected: Boolean,
)

data class FileShareUiState(
    val busy: Boolean = false,
    val active: List<FileShareUiItem> = emptyList(),
)

internal enum class FileShareFailureReason {
    TOR_REQUIRED,
    BUSY,
    PASSWORD_INVALID,
    FILE_UNAVAILABLE,
    FILE_TOO_LARGE,
    STORAGE_UNAVAILABLE,
    CORE_UNAVAILABLE,
    VAULT_UNAVAILABLE,
    SHARE_CREATE_FAILED,
    PUBLICATION_FAILED,
    INVITATION_FAILED,
}

internal class FileShareException(
    val reason: FileShareFailureReason,
    cause: Throwable? = null,
) : IllegalStateException(reason.name.lowercase(), cause)

internal data class PreparedShareSource(
    val file: java.io.File,
    val displayName: String,
    val mediaType: String,
    val sizeBytes: Long,
)

internal fun sanitizeFileShareDisplayName(raw: String?): String {
    val cleaned =
        raw.orEmpty()
            .filterNot { character ->
                character.isISOControl() || character == '/' || character == '\\'
            }.trim()
            .ifEmpty { "shared-file" }
    var bounded = cleaned.take(MAX_DISPLAY_NAME_CHARS)
    while (bounded.encodeToByteArray().size > MAX_DISPLAY_NAME_BYTES) {
        bounded = bounded.dropLast(1)
    }
    return bounded.ifEmpty { "shared-file" }
}

internal fun sanitizeFileShareMediaType(raw: String?): String {
    val value = raw.orEmpty().trim()
    return value.takeIf { candidate ->
        candidate.length <= MAX_MEDIA_TYPE_BYTES && FILE_SHARE_MEDIA_TYPE.matches(candidate)
    } ?: DEFAULT_MEDIA_TYPE
}

internal fun validFileSharePassword(password: CharArray?): Boolean {
    if (password == null) return true
    return password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
        password.all { character ->
            character.code in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST
        }
}

internal fun parseFileShareEventKinds(json: String): List<String> =
    FILE_SHARE_EVENT_KIND.findAll(json)
        .map { match -> match.groupValues[1] }
        .filter { kind -> kind in KNOWN_FILE_SHARE_EVENTS }
        .toList()

internal const val FILE_SHARE_MAX_BYTES = 512L * 1024L * 1024L
internal const val FILE_SHARE_AUDIT_INTERVAL_MS = 15_000L
internal const val FILE_SHARE_MIN_LIFETIME_MS = 60L * 60L * 1_000L
internal const val FILE_SHARE_MAX_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L
internal val FILE_SHARE_ALLOWED_DOWNLOAD_LIMITS = setOf(1, 3, 10)

private const val MAX_DISPLAY_NAME_CHARS = 120
private const val MAX_DISPLAY_NAME_BYTES = 240
private const val MAX_MEDIA_TYPE_BYTES = 127
private const val DEFAULT_MEDIA_TYPE = "application/octet-stream"
private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_LENGTH = 128
private const val PRINTABLE_ASCII_FIRST = 0x21
private const val PRINTABLE_ASCII_LAST = 0x7e
private val FILE_SHARE_MEDIA_TYPE = Regex("^[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+$")
private val FILE_SHARE_EVENT_KIND = Regex("\\\"kind\\\":\\\"([a-z_]+)\\\"")
private val KNOWN_FILE_SHARE_EVENTS =
    setOf(
        "created",
        "restored",
        "file_added",
        "download_authorized",
        "download_denied",
        "download_limit_reached",
        "expired",
        "revoked",
        "integrity_failure",
    )
