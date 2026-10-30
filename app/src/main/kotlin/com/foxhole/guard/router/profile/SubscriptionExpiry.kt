package com.foxhole.guard.router.profile

import com.foxhole.core.importer.SubscriptionMetadataParser

internal fun validSubscriptionExpiry(value: Long?): Long? = value?.takeIf { it in 1..MAX_EXPIRY_MILLIS }

internal fun subscriptionExpiry(header: String?, sourceUrl: String? = null): Long? {
    val userInfo = header?.takeIf { it.length <= 1024 }?.let(SubscriptionMetadataParser::parseSubscriptionUserInfo)
    return validSubscriptionExpiry(userInfo?.expiresAt)
        ?: validSubscriptionExpiry(SubscriptionMetadataParser.expirationFromSubscriptionUrl(sourceUrl))
}

// Bound display formatting to 9999-12-31; expiry remains informational.
private const val MAX_EXPIRY_MILLIS = 253402300799999L
