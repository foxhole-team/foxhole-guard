package com.foxhole.core.network

import okhttp3.HttpUrl

fun String.ensurePublicSubscriptionUrl(
    resolveHost: Boolean = false,
    resolver: RemoteHostResolver? = null,
): HttpUrl {
    val source = trim()
    if (!source.startsWith("ssconf://", ignoreCase = true)) return source.ensurePublicHttpsUrl(resolveHost, resolver)
    require(source.length <= 16 * 1024 && source.none(Char::isISOControl)) { "invalid Outline source" }
    val authority = source.substring("ssconf://".length)
    require(
        authority.isNotEmpty() && !authority.startsWith('/') && !authority.startsWith('\\')
    ) { "invalid Outline source" }
    val url = "https://$authority".ensurePublicHttpsUrl(resolveHost, resolver)
    require(url.username.isEmpty() && url.password.isEmpty()) { "Outline source credentials are not allowed" }
    return url
}
