package com.foxhole.core.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

object SubscriptionMetadataParser {
    private val subscriptionUserinfoExpireRegex =
        Regex("""(?:^|[;\s])expire=([0-9T:\-+.Z]+)""", RegexOption.IGNORE_CASE)
    private val expirationKeys =
        listOf(
            "expire",
            "expires",
            "expiry",
            "expiration",
            "subscription-expire",
            "subscription_expires_at",
            "subscription-expiration",
        )

    fun expirationFromSubscriptionUrl(sourceUrl: String?): Long? =
        runCatching { URI(sourceUrl).rawQuery }.getOrNull()?.let(::expirationFromRawQuery)

    fun expirationFromRawQuery(rawQuery: String?): Long? {
        if (rawQuery.isNullOrBlank()) {
            return null
        }
        return rawQuery
            .split('&')
            .mapNotNull { item ->
                if (item.isBlank()) {
                    null
                } else {
                    val key = URLDecoder.decode(item.substringBefore('='), StandardCharsets.UTF_8.name())
                    val value = URLDecoder.decode(item.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                    key to value
                }
            }.firstNotNullOfOrNull { (key, value) ->
                value.takeIf { key.lowercase() in expirationKeys }?.let(::parseExpirationValue)
            }
    }

    fun expirationFromSubscriptionUserinfo(headerValue: String?): Long? {
        val raw = headerValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val match =
            subscriptionUserinfoExpireRegex
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
        return parseExpirationValue(match)
    }

    fun parseExpirationValue(rawValue: String?): Long? {
        val value = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        value.toLongOrNull()?.let { numeric ->
            return when {
                value.length >= 13 -> numeric
                value.length >= 10 -> numeric * 1000L
                else -> null
            }
        }
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }
}
