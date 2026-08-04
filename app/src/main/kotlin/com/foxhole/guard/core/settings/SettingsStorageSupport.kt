package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import java.io.File

internal sealed interface EncryptedSettingsLoadResult {
    data object Missing : EncryptedSettingsLoadResult

    data class Loaded(
        val settings: Settings,
    ) : EncryptedSettingsLoadResult

    data class Corrupt(
        val cause: Throwable,
        val preservedCopy: File?,
    ) : EncryptedSettingsLoadResult
}

internal class SettingsCorruptedException(
    val preservedCopy: File?,
    cause: Throwable,
) : IllegalStateException(
    buildString {
        append("secure settings are corrupted")
        preservedCopy?.let { file ->
            append(" (preserved copy: ")
            append(file.name)
            append(')')
        }
    },
    cause,
)

internal fun readEncryptedSettingsResult(
    settingsFile: File,
    readPayload: () -> String,
    decodePayload: (String) -> Settings,
    sanitizePayload: (String) -> String,
    encodeCanonical: (Settings) -> String,
    rewriteCanonical: (Settings) -> Unit,
    preserveCorruptFile: () -> File?,
): EncryptedSettingsLoadResult {
    if (!settingsFile.exists()) {
        return EncryptedSettingsLoadResult.Missing
    }
    return runCatching {
        val payload = readPayload()
        val settings = decodePayload(sanitizePayload(payload))
        // One rewrite predicate covers both theme sanitization and normalization/schema drift:
        // persist only when the on-disk bytes differ from the canonical encoding we just decoded.
        // An already-canonical file (the common cold-start case) is left untouched — no encrypt,
        // no atomic write.
        if (encodeCanonical(settings) != payload) {
            rewriteCanonical(settings)
        }
        EncryptedSettingsLoadResult.Loaded(settings)
    }.getOrElse { error ->
        EncryptedSettingsLoadResult.Corrupt(
            cause = error,
            preservedCopy = runCatching(preserveCorruptFile).getOrNull(),
        )
    }
}
