package com.foxhole.core.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

data class SubscriptionUserInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
) {
    val usedBytes: Long?
        get() =
            listOfNotNull(uploadBytes, downloadBytes)
                .takeIf { it.isNotEmpty() }
                ?.sum()

    val remainingBytes: Long?
        get() = totalBytes?.let { total -> usedBytes?.let { used -> (total - used).coerceAtLeast(0L) } }

    val hasContent: Boolean
        get() = uploadBytes != null || downloadBytes != null || totalBytes != null || expiresAt != null
}

data class SubscriptionHeaderMetadata(
    val title: String? = null,
    val updateIntervalHours: Int? = null,
    val announcement: String? = null,
    val announcementUrl: String? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val userInfo: SubscriptionUserInfo? = null,
    val etag: String? = null,
)

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

    fun parseHeaderMetadata(header: (String) -> String?): SubscriptionHeaderMetadata =
        SubscriptionHeaderMetadata(
            title = decodeMetadataHeader(header("profile-title"), MAX_TITLE_LENGTH),
            updateIntervalHours = parseUpdateIntervalHours(header("profile-update-interval")),
            announcement = decodeMetadataHeader(header("announce"), MAX_ANNOUNCEMENT_LENGTH),
            announcementUrl = parseMetadataUrl(header("announce-url")),
            supportUrl = parseMetadataUrl(header("support-url")),
            webPageUrl = parseMetadataUrl(header("profile-web-page-url")),
            userInfo = parseSubscriptionUserInfo(header("subscription-userinfo")),
            etag = header("etag")?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_ETAG_LENGTH },
        )

    fun decodeMetadataHeader(
        rawValue: String?,
        maxLength: Int = MAX_ANNOUNCEMENT_LENGTH,
    ): String? {
        val raw = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val decoded =
            if (raw.startsWith(BASE64_HEADER_PREFIX, ignoreCase = true)) {
                decodeBase64Header(raw.substring(BASE64_HEADER_PREFIX.length).trim()) ?: return null
            } else {
                raw
            }
        return decoded
            .filterNot { character -> character.isISOControl() }
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(maxLength)
    }

    fun parseUpdateIntervalHours(rawValue: String?): Int? {
        val value = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val hours = value.toDoubleOrNull()?.takeIf { it > 0.0 && !it.isNaN() } ?: return null
        return hours
            .coerceIn(MIN_UPDATE_INTERVAL_HOURS.toDouble(), MAX_UPDATE_INTERVAL_HOURS.toDouble())
            .toInt()
    }

    fun parseSubscriptionUserInfo(headerValue: String?): SubscriptionUserInfo? {
        val raw = headerValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.length > MAX_USERINFO_LENGTH) {
            return null
        }
        val values =
            raw
                .split(';', ',')
                .mapNotNull { item ->
                    val key = item.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
                    val value = item.substringAfter('=', missingDelimiterValue = "").trim()
                    if (key.isBlank() || value.isBlank()) null else key to value
                }.toMap()
        val userInfo =
            SubscriptionUserInfo(
                uploadBytes = values["upload"]?.toNonNegativeLongOrNull(),
                downloadBytes = values["download"]?.toNonNegativeLongOrNull(),
                // Providers encode unlimited quota as total=0, not as exhausted.
                totalBytes = values["total"]?.toNonNegativeLongOrNull()?.takeIf { it > 0L },
                expiresAt = parseExpirationValue(values["expire"]),
            )
        return userInfo.takeIf { it.hasContent }
    }

    fun parseMetadataUrl(rawValue: String?): String? {
        val raw = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.length > MAX_URL_LENGTH || raw.any(Char::isISOControl)) {
            return null
        }
        val scheme = runCatching { URI(raw) }.getOrNull()?.scheme?.lowercase() ?: return null
        return raw.takeIf { scheme == "https" || scheme == "http" }
    }

    private fun decodeBase64Header(value: String): String? {
        val compact = value.filterNot { character -> character.isWhitespace() }
        if (compact.isBlank() || compact.length > MAX_BASE64_HEADER_LENGTH) {
            return null
        }
        val normalized =
            compact
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
                .let { padded -> padded.padEnd(padded.length + ((4 - padded.length % 4) % 4), '=') }
        return runCatching {
            String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun String.toNonNegativeLongOrNull(): Long? = toLongOrNull()?.takeIf { it >= 0L }

    private const val BASE64_HEADER_PREFIX = "base64:"
    private const val MAX_TITLE_LENGTH = 256
    private const val MAX_ANNOUNCEMENT_LENGTH = 4096
    private const val MAX_ETAG_LENGTH = 256
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_USERINFO_LENGTH = 1024
    private const val MAX_BASE64_HEADER_LENGTH = 8192
    private const val MIN_UPDATE_INTERVAL_HOURS = 1
    private const val MAX_UPDATE_INTERVAL_HOURS = 168
}
