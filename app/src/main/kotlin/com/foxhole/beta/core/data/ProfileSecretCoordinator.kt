package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.StoredProfileSecret

internal fun StoredProfileSecret.withUpdatedResolvedConfigJson(
    sanitized: String,
    protocolOptionIdOverride: String? = null,
): StoredProfileSecret {
    if (protocolOptions.isEmpty()) {
        return copy(resolvedConfigJson = sanitized)
    }
    val targetOption =
        protocolOptionIdOverride
            ?.takeIf(String::isNotBlank)
            ?.let { overrideId -> protocolOptions.firstOrNull { option -> option.id == overrideId } }
            ?: selectedProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
            ?: protocolOptions.firstOrNull()
            ?: return copy(resolvedConfigJson = sanitized)
    val shouldMirrorTopLevel =
        selectedProtocolOptionId == targetOption.id ||
            (selectedProtocolOptionId == null && protocolOptions.firstOrNull()?.id == targetOption.id) ||
            resolvedConfigJson == targetOption.normalizedConfigJson
    return copy(
        resolvedConfigJson = if (shouldMirrorTopLevel) sanitized else resolvedConfigJson,
        protocolOptions =
            protocolOptions.map { option ->
                if (option.id == targetOption.id) {
                    option.copy(normalizedConfigJson = sanitized)
                } else {
                    option
                }
            },
    )
}
