package com.foxhole.guard.core.data

import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json

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
        val selected =
            protocolOptions.firstOrNull { option -> option.id == selectedId }
                ?: return protocolOptions.firstEnabledProtocolOption() ?: protocolOptions.firstOrNull()
        return selected.takeIf(StoredProfileProtocolOption::enabled)
            ?: protocolOptions.firstEnabledProtocolOption()
            ?: selected
    }
    return protocolOptions.firstEnabledProtocolOption() ?: protocolOptions.firstOrNull()
}

internal fun StoredProfileProtocolOption.isSelectableProtocolOption(): Boolean =
    enabled && normalizedConfigJson.isNotBlank()

internal fun StoredProfileProtocolOption.requiresInsecureTlsForRuntime(json: Json): Boolean =
    requiresInsecureTls || normalizedConfigJson.requiresInsecureTls(json)

internal fun StoredProfileSecret.selectableProtocolOptionOrNull(optionId: String): StoredProfileProtocolOption? =
    protocolOptions.firstOrNull { option -> option.id == optionId && option.isSelectableProtocolOption() }

internal data class ProtocolOptionEnabledUpdate(
    val secret: StoredProfileSecret,

    val reselectedOption: StoredProfileProtocolOption?,
)

internal fun StoredProfileSecret.protocolOptionEnabledUpdate(
    optionId: String,
    enabled: Boolean,
    json: Json,
    allowInsecureTls: Boolean,
): ProtocolOptionEnabledUpdate {
    require(protocolOptions.any { option -> option.id == optionId }) { "protocol option not found" }
    val updatedOptions =
        protocolOptions.map { option ->
            if (option.id == optionId) option.copy(enabled = enabled) else option
        }
    require(updatedOptions.any(StoredProfileProtocolOption::enabled)) {
        "the last enabled protocol option cannot be disabled"
    }
    val reselectedOption =
        if (!enabled && selectedProtocolOptionId == optionId) {
            updatedOptions.firstConnectableProtocolOption(json = json, allowInsecureTls = allowInsecureTls)
        } else {
            null
        }
    return ProtocolOptionEnabledUpdate(
        secret =
        copy(
            protocolOptions = updatedOptions,
            selectedProtocolOptionId = reselectedOption?.id ?: selectedProtocolOptionId,
        ),
        reselectedOption = reselectedOption,
    )
}

private fun List<StoredProfileProtocolOption>.firstConnectableProtocolOption(
    json: Json,
    allowInsecureTls: Boolean,
): StoredProfileProtocolOption? =
    firstOrNull { option ->
        option.isSelectableProtocolOption() &&
            (allowInsecureTls || !option.requiresInsecureTlsForRuntime(json))
    }
        ?: firstOrNull(StoredProfileProtocolOption::isSelectableProtocolOption)
        ?: firstEnabledProtocolOption()

private fun List<StoredProfileProtocolOption>.firstEnabledProtocolOption(): StoredProfileProtocolOption? =
    firstOrNull(StoredProfileProtocolOption::enabled)

private fun String?.normalizedProtocolOptionId(): String? = this?.trim()?.takeIf(String::isNotBlank)
