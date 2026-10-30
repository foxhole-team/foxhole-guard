package com.foxhole.guard.core.data

import com.foxhole.guard.runtime.runCatchingUnlessCancelled
import okhttp3.HttpUrl.Companion.toHttpUrl

internal suspend fun fetchWebAppPreview(
    rawUrl: String,
    fetchText: suspend (String, Long) -> String?,
    fetchBytes: suspend (String, Long) -> ByteArray?,
): Result<WebAppPreview> =
    runCatchingUnlessCancelled {
        val url = requireNotNull(normalizeWebAppInputUrl(rawUrl)) { "invalid url" }
        val html = runCatchingUnlessCancelled { fetchText(url, WEB_APP_PAGE_MAX_BYTES) }.getOrNull().orEmpty()
        val meta = parseWebAppHtml(url, html)
        val manifest =
            meta.manifestUrl?.let { manifestUrl ->
                runCatchingUnlessCancelled {
                    parseWebAppManifest(
                        manifestUrl,
                        fetchText(manifestUrl, WEB_APP_MANIFEST_MAX_BYTES).orEmpty(),
                    )
                }.getOrNull()
            }
        val name = manifest?.name ?: meta.siteName ?: meta.title ?: url.toHttpUrl().host
        val iconCandidates =
            (manifest?.iconUrls.orEmpty() + meta.iconCandidates + listOfNotNull(resolveWebAppUrl(url, "/favicon.ico")))
                .distinct()
                .take(WEB_APP_ICON_CANDIDATE_LIMIT)
        val iconBytes =
            iconCandidates.firstNotNullOfOrNull { candidate ->
                runCatchingUnlessCancelled { fetchBytes(candidate, WEB_APP_ICON_MAX_BYTES) }
                    .getOrNull()
                    ?.takeIf(ByteArray::isNotEmpty)
            }
        WebAppPreview(url = url, name = name, iconBytes = iconBytes)
    }

private const val WEB_APP_PAGE_MAX_BYTES = 512L * 1024L
private const val WEB_APP_MANIFEST_MAX_BYTES = 256L * 1024L
private const val WEB_APP_ICON_MAX_BYTES = 512L * 1024L
private const val WEB_APP_ICON_CANDIDATE_LIMIT = 5
