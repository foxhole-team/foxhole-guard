package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.StoredProfileSecret

internal fun StoredProfileSecret.withUpdatedResolvedConfigJson(
    sanitized: String,
    protocolOptionIdOverride: String? = null,
): StoredProfileSecret {
    val targetOption =
        if (protocolOptions.isEmpty()) {
            null
        } else {
            val requestedOptionIds =
                listOfNotNull(
                    protocolOptionIdOverride?.takeIf(String::isNotBlank),
                    selectedProtocolOptionId?.takeIf(String::isNotBlank),
                )
            requestedOptionIds.firstNotNullOfOrNull { optionId ->
                protocolOptions.firstOrNull { option -> option.id == optionId }
            } ?: protocolOptions.firstOrNull()
        }
    return if (targetOption == null) {
        copy(resolvedConfigJson = sanitized)
    } else {
        val shouldMirrorTopLevel =
            selectedProtocolOptionId == targetOption.id ||
                (selectedProtocolOptionId == null && protocolOptions.firstOrNull()?.id == targetOption.id) ||
                resolvedConfigJson == targetOption.normalizedConfigJson
        val updatedProtocolOptions =
            protocolOptions.map { option ->
                if (option.id == targetOption.id) {
                    option.copy(normalizedConfigJson = sanitized)
                } else {
                    option
                }
            }
        copy(
            resolvedConfigJson = if (shouldMirrorTopLevel) sanitized else resolvedConfigJson,
            protocolOptions = updatedProtocolOptions,
        )
    }
}
