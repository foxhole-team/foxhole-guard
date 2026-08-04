package com.foxhole.guard.core.data

import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json

/**
 * Resolves the protocol option the runtime should start.
 *
 * Never hard-fails on the per-protocol on/off flag (N1): an explicit request always wins, and when a
 * persisted selection points at a switched-off protocol (stale data, or a profile whose options were
 * all turned off) resolution degrades to the best remaining option instead of refusing to connect.
 */
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
    // An explicit override names one exact protocol (a probe, a TEST run, a reconnect onto the
    // running option). Silently substituting another one would connect through a protocol the caller
    // never asked for, so identity wins here; the enabled flag is enforced where options are *picked*
    // (selectProfileProtocolOption / MultiProtocolProfileSupport.supportedOptions).
    requestedOptionId?.let { requestedId ->
        return protocolOptions.firstOrNull { option -> option.id == requestedId }
            ?: error("protocol option is missing")
    }
    storedSelectedOptionId?.let { selectedId ->
        val selected =
            protocolOptions.firstOrNull { option -> option.id == selectedId }
                ?: error("selected protocol option is missing")
        return selected.takeIf(StoredProfileProtocolOption::enabled)
            ?: protocolOptions.firstEnabledProtocolOption()
            ?: selected
    }
    return protocolOptions.firstEnabledProtocolOption() ?: protocolOptions.firstOrNull()
}

/** An option can become the running protocol only while it is switched on and carries a config. */
internal fun StoredProfileProtocolOption.isSelectableProtocolOption(): Boolean =
    enabled && normalizedConfigJson.isNotBlank()

/** Per-option insecure-TLS requirement: the stored marker, or the config itself asking for it. */
internal fun StoredProfileProtocolOption.requiresInsecureTlsForRuntime(json: Json): Boolean =
    requiresInsecureTls || normalizedConfigJson.requiresInsecureTls(json)

/** The option a manual protocol switch may persist, or null when it must be refused. */
internal fun StoredProfileSecret.selectableProtocolOptionOrNull(optionId: String): StoredProfileProtocolOption? =
    protocolOptions.firstOrNull { option -> option.id == optionId && option.isSelectableProtocolOption() }

internal data class ProtocolOptionEnabledUpdate(
    val secret: StoredProfileSecret,
    /** Non-null when the toggle switched off the selected protocol and the selection had to move. */
    val reselectedOption: StoredProfileProtocolOption?,
)

/**
 * Toggling one protocol of a smart profile on/off (N1).
 *
 * Policy: a smart profile always keeps at least one enabled protocol — disabling the last enabled one
 * is refused — and switching off the *currently selected* protocol re-points the selection at the
 * first remaining option that can actually connect, so `selectedProtocolOptionId` never lingers on a
 * disabled protocol. A selection that was never persisted (`selectedProtocolOptionId == null`, i.e.
 * "whatever comes first") is left alone: [selectedStoredProtocolOptionForRuntime] already skips
 * disabled options in that case.
 */
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

/**
 * Best re-selection target: an option that is switched on, has a config, and does not need an
 * insecure-TLS consent the profile lacks. Falls back down the list rather than returning null, so a
 * re-point never leaves the profile without a selection.
 */
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
