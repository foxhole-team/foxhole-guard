package com.foxhole.beta.vpn

import com.foxhole.beta.core.data.SubscriptionTlsTrustRequiredException
import java.io.IOException

internal enum class SubscriptionRefreshWorkDecision {
    SUCCESS,
    RETRY,
}

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
    if (error is SubscriptionTlsTrustRequiredException || error.hasCause<SubscriptionTlsTrustRequiredException>()) {
        return false
    }
    if (error.hasCause<IOException>()) {
        return true
    }
    val httpCode = HTTP_STATUS_PATTERN.find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    return httpCode != null && httpCode >= 500
}

private val HTTP_STATUS_PATTERN = Regex("""http\s+(\d{3})""", RegexOption.IGNORE_CASE)

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { cause -> cause is T }
