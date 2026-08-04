package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret

@Suppress("ReturnCount")
internal fun ParsedSubscriptionProfile.resolveRefreshSelectedProtocolOptionId(
    previousSecret: StoredProfileSecret?,
): String? {
    val previousSelectedId = previousSecret?.selectedProtocolOptionId.normalizedProtocolOptionId()
    if (previousSelectedId != null) {
        protocolOptions.firstOrNull { option -> option.id == previousSelectedId }?.let { option ->
            return option.id
        }
        recoverProtocolOptionByFingerprint(
            previousSecret = previousSecret,
            previousSelectedId = previousSelectedId,
        )?.let { option ->
            return option.id
        }
        return previousSelectedId
    }
    return selectedProtocolOptionId.normalizedProtocolOptionId()
}

private fun ParsedSubscriptionProfile.recoverProtocolOptionByFingerprint(
    previousSecret: StoredProfileSecret?,
    previousSelectedId: String,
): StoredProfileProtocolOption? {
    val previousFingerprint =
        previousSecret
            ?.protocolOptions
            ?.firstOrNull { option -> option.id == previousSelectedId }
            ?.stableProtocolOptionFingerprint()
            ?: return null
    return protocolOptions
        .filter { option -> option.stableProtocolOptionFingerprint() == previousFingerprint }
        .singleOrNull()
}

internal fun StoredProfileProtocolOption.stableProtocolOptionFingerprint(): String? =
    stableSubscriptionProfileFingerprint(
        protocolHint = protocolHint,
        normalizedConfigJson = normalizedConfigJson,
        protocolOptions = emptyList(),
    )

private fun String?.normalizedProtocolOptionId(): String? = this?.trim()?.takeIf(String::isNotBlank)
