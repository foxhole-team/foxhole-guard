package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret

@Suppress("ReturnCount")
internal fun StoredProfileSecret.selectedStoredProtocolOptionForRuntime(
    overrideOptionId: String? = null,
): StoredProfileProtocolOption? {
    val requestedOptionId = overrideOptionId.normalizedProtocolOptionId()
    val storedSelectedOptionId = selectedProtocolOptionId.normalizedProtocolOptionId()
    if (protocolOptions.isEmpty()) {
        require(requestedOptionId == null && storedSelectedOptionId == null) {
            "protocol option is missing"
        }
        return null
    }
    requestedOptionId?.let { requestedId ->
        return protocolOptions.firstOrNull { option -> option.id == requestedId }
            ?: error("protocol option is missing")
    }
    storedSelectedOptionId?.let { selectedId ->
        return protocolOptions.firstOrNull { option -> option.id == selectedId }
            ?: error("selected protocol option is missing")
    }
    return protocolOptions.firstOrNull()
}

private fun String?.normalizedProtocolOptionId(): String? = this?.trim()?.takeIf(String::isNotBlank)
