package com.foxhole.beta.core.settings

import com.foxhole.beta.core.model.Settings
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
    rewriteSanitized: (Settings) -> Unit,
    preserveCorruptFile: () -> File?,
): EncryptedSettingsLoadResult {
    if (!settingsFile.exists()) {
        return EncryptedSettingsLoadResult.Missing
    }
    return runCatching {
        val payload = readPayload()
        val sanitizedPayload = sanitizePayload(payload)
        val settings = decodePayload(sanitizedPayload)
        if (sanitizedPayload != payload) {
            rewriteSanitized(settings)
        }
        EncryptedSettingsLoadResult.Loaded(settings)
    }.getOrElse { error ->
        EncryptedSettingsLoadResult.Corrupt(
            cause = error,
            preservedCopy = runCatching(preserveCorruptFile).getOrNull(),
        )
    }
}
