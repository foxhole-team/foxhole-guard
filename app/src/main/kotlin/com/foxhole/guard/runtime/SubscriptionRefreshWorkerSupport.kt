package com.foxhole.guard.runtime

import com.foxhole.core.network.isTlsTrustFailure
import java.io.IOException

internal fun decideScheduledRefreshOutcome(
    targetedProfiles: Int,
    successfulProfiles: Int,
    retryableFailures: Int,
): SubscriptionRefreshWorkDecision =
    if (targetedProfiles > 0 && successfulProfiles == 0 && retryableFailures > 0) {
        SubscriptionRefreshWorkDecision.RETRY
    } else {
        SubscriptionRefreshWorkDecision.SUCCESS
    }

internal fun isRetryableScheduledRefreshFailure(error: Throwable): Boolean {
    val httpCode = HTTP_STATUS_PATTERN.find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when {
        isTlsTrustFailure(error) -> false
        error.hasCause<IOException>() -> true
        else -> httpCode != null && httpCode >= 500
    }
}

internal fun shouldSkipScheduledRefreshForNetwork(
    skipOnCellular: Boolean,
    transport: String?,
    isMetered: Boolean,
): Boolean =
    skipOnCellular && (transport?.trim()?.lowercase() == "cellular" || isMetered)

private val HTTP_STATUS_PATTERN = Regex("""http\s+(\d{3})""", RegexOption.IGNORE_CASE)

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { cause -> cause is T }
