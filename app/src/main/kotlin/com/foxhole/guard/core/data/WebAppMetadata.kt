package com.foxhole.guard.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Pure web-app parsers: regexes over <link>/<meta>/<title> rather than a DOM library. No new parse
// dependency, and on real sites the attributes we need sit in a single tag.

internal data class WebAppHtmlMetadata(
    val manifestUrl: String?,
    // By descending priority: apple-touch-icon, rel=icon (largest sizes first), favicon.ico.
    val iconCandidates: List<String>,
    val siteName: String?,
    val title: String?,
)

internal data class WebAppManifestMetadata(
    val name: String?,
    // Largest icons first.
    val iconUrls: List<String>,
)

internal fun parseWebAppHtml(baseUrl: String, html: String): WebAppHtmlMetadata {
    val head = html.take(WEB_APP_HTML_SCAN_CHARS)
    var manifestUrl: String? = null
    val appleIcons = mutableListOf<Pair<Int, String>>()
    val plainIcons = mutableListOf<Pair<Int, String>>()
    LINK_TAG_REGEX.findAll(head).forEach { match ->
        val tag = match.value
        val rel = tagAttribute(tag, "rel")?.lowercase() ?: return@forEach
        val href = tagAttribute(tag, "href") ?: return@forEach
        val resolved = resolveWebAppUrl(baseUrl, href) ?: return@forEach
        val size = iconSizeRank(tagAttribute(tag, "sizes"))
        when {
            "manifest" in rel -> if (manifestUrl == null) manifestUrl = resolved
            "apple-touch-icon" in rel -> appleIcons += size to resolved
            "icon" in rel -> plainIcons += size to resolved
        }
    }
    val candidates =
        (appleIcons.sortedByDescending { it.first } + plainIcons.sortedByDescending { it.first })
            .map { it.second }
            .distinct()
    return WebAppHtmlMetadata(
        manifestUrl = manifestUrl,
        iconCandidates = candidates,
        siteName = META_OG_SITE_NAME_REGEX.find(head)?.let { tagAttribute(it.value, "content") }
            ?.let(::unescapeHtml)?.trim()?.takeIf(String::isNotEmpty),
        title = TITLE_REGEX.find(head)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim()
            ?.takeIf(String::isNotEmpty),
    )
}

internal fun parseWebAppManifest(manifestUrl: String, json: String): WebAppManifestMetadata {
    val doc =
        runCatching { WEB_APP_MANIFEST_JSON.decodeFromString<WebAppManifestDoc>(json) }
            .getOrElse { return WebAppManifestMetadata(name = null, iconUrls = emptyList()) }
    val icons =
        doc.icons
            .mapNotNull { icon ->
                val src = icon.src?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val resolved = resolveWebAppUrl(manifestUrl, src) ?: return@mapNotNull null
                iconSizeRank(icon.sizes) to resolved
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
    return WebAppManifestMetadata(
        name = (doc.name ?: doc.shortName)?.trim()?.takeIf(String::isNotEmpty),
        iconUrls = icons,
    )
}

internal fun resolveWebAppUrl(baseUrl: String, href: String): String? {
    val trimmed = href.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    return base.resolve(trimmed)?.toString()
}

/**
 * User-entered web-app address: a missing scheme becomes https and http is rejected — the frame
 * and watchdog speak https only, and the fetch SSRF guard would refuse plain http anyway.
 */
internal fun normalizeWebAppInputUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) {
        return null
    }
    val candidate =
        when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // http, and any other explicit scheme, is rejected outright.
            trimmed.startsWith("http://", ignoreCase = true) || "://" in trimmed -> null
            else -> "https://$trimmed"
        } ?: return null
    return candidate.toHttpUrlOrNull()
        ?.takeIf { url -> '.' in url.host }
        ?.toString()
}

/**
 * The `(N)` title heuristic: a leading counter is almost always an unread badge, while a trailing
 * one is accepted only when modest (<=999) — a trailing "(2026)" is a year, not a badge.
 */
internal fun parseTitleBadge(title: String?): Int? {
    if (title.isNullOrBlank()) {
        return null
    }
    TITLE_BADGE_PREFIX_REGEX.find(title)?.let { return it.groupValues[1].toIntOrNull() }
    TITLE_BADGE_SUFFIX_REGEX.find(title)?.let { match ->
        return match.groupValues[1].toIntOrNull()?.takeIf { it <= WEB_APP_SUFFIX_BADGE_MAX }
    }
    return null
}

@Serializable
private data class WebAppManifestDoc(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val icons: List<WebAppManifestIcon> = emptyList(),
)

@Serializable
private data class WebAppManifestIcon(
    val src: String? = null,
    val sizes: String? = null,
)

private val WEB_APP_MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun tagAttribute(tag: String, name: String): String? {
    val match = Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
    return match?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }?.takeIf(String::isNotBlank)
}

// Icon size rank from sizes="WxH [WxH...]": the maximum width; absent or "any" ranks 0.
private fun iconSizeRank(sizes: String?): Int =
    sizes.orEmpty()
        .split(' ')
        .mapNotNull { token -> token.substringBefore('x', "").toIntOrNull() }
        .maxOrNull() ?: 0

private fun unescapeHtml(value: String): String =
    value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

private const val WEB_APP_HTML_SCAN_CHARS = 200_000
private const val WEB_APP_SUFFIX_BADGE_MAX = 999
private val LINK_TAG_REGEX = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
private val TITLE_REGEX =
    Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
private val META_OG_SITE_NAME_REGEX =
    Regex("""<meta\b[^>]*property\s*=\s*["']og:site_name["'][^>]*>""", RegexOption.IGNORE_CASE)
private val TITLE_BADGE_PREFIX_REGEX = Regex("""^\s*\((\d{1,4})\)""")
private val TITLE_BADGE_SUFFIX_REGEX = Regex("""\((\d{1,4})\)\s*$""")
