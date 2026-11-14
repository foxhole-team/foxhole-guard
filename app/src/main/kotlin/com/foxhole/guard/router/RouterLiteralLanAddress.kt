package com.foxhole.guard.router

internal fun routerLiteralLanAddress(value: String?): String? {
    val text = value?.takeIf { it.isNotEmpty() && it.length <= 64 && it == it.trim() } ?: return null
    val v4 = text.split('.')
    if (v4.size == 4 && v4.all { part -> part.length in 1..3 && part.all { it in '0'..'9' } &&
            (part.length == 1 || part[0] != '0') && part.toIntOrNull()?.let { it in 0..255 } == true }) return text
    if (':' !in text || !text.matches(Regex("[0-9a-fA-F:.]+"))) return null
    return try { java.net.URI("http://[$text]/").host?.takeIf { it.isNotEmpty() }?.let { text } }
        catch (_: java.net.URISyntaxException) { null }
}
