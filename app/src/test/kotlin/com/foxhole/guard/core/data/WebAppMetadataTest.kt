package com.foxhole.guard.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чистые парсеры web apps: метаданные сайта (manifest/иконки/имя) из HTML без DOM-библиотек и
 * `(N)`-эвристика заголовка для вотчдога. Регексы обязаны переживать реальную вёрстку: атрибуты
 * в любом порядке, одинарные/двойные кавычки, относительные href.
 */
internal class WebAppMetadataTest {
    private val base = "https://app.example.com/inbox/"

    @Test
    fun `html parser extracts manifest, icons, site name and title`() {
        val html = """
            <!doctype html><html><head>
            <title>Example Inbox</title>
            <meta property="og:site_name" content="Example &amp; Co">
            <link rel="manifest" href="/manifest.webmanifest">
            <link href='icons/apple.png' rel='apple-touch-icon' sizes='180x180'>
            <link rel="icon" type="image/png" sizes="32x32" href="//cdn.example.com/fav32.png">
            <link rel="shortcut icon" href="/favicon.ico">
            </head><body></body></html>
        """.trimIndent()

        val meta = parseWebAppHtml(base, html)

        assertEquals("https://app.example.com/manifest.webmanifest", meta.manifestUrl)
        assertEquals("Example & Co", meta.siteName)
        assertEquals("Example Inbox", meta.title)
        assertEquals(
            listOf(
                "https://app.example.com/inbox/icons/apple.png",
                "https://cdn.example.com/fav32.png",
                "https://app.example.com/favicon.ico",
            ),
            meta.iconCandidates,
        )
    }

    @Test
    fun `html parser survives a page without any metadata`() {
        val meta = parseWebAppHtml(base, "<html><body>hi</body></html>")
        assertNull(meta.manifestUrl)
        assertNull(meta.siteName)
        assertNull(meta.title)
        assertEquals(emptyList<String>(), meta.iconCandidates)
    }

    @Test
    fun `manifest parser prefers name and biggest icon`() {
        val json = """
            {
              "short_name": "Inbox",
              "name": "Example Inbox App",
              "icons": [
                {"src": "icon-48.png", "sizes": "48x48"},
                {"src": "/icons/icon-512.png", "sizes": "512x512"},
                {"src": "icon-192.png", "sizes": "192x192"}
              ],
              "unknown_field": {"nested": true}
            }
        """.trimIndent()

        val manifest = parseWebAppManifest("https://app.example.com/manifest.webmanifest", json)

        assertEquals("Example Inbox App", manifest.name)
        assertEquals(
            listOf(
                "https://app.example.com/icons/icon-512.png",
                "https://app.example.com/icon-192.png",
                "https://app.example.com/icon-48.png",
            ),
            manifest.iconUrls,
        )
    }

    @Test
    fun `manifest parser falls back to short name and tolerates broken json`() {
        assertEquals("Inbox", parseWebAppManifest(base, """{"short_name":"Inbox"}""").name)
        val broken = parseWebAppManifest(base, "not a json at all")
        assertNull(broken.name)
        assertEquals(emptyList<String>(), broken.iconUrls)
    }

    @Test
    fun `resolve url handles absolute, protocol-relative and relative refs`() {
        assertEquals("https://other.com/x.png", resolveWebAppUrl(base, "https://other.com/x.png"))
        assertEquals("https://cdn.example.com/i.png", resolveWebAppUrl(base, "//cdn.example.com/i.png"))
        assertEquals("https://app.example.com/root.png", resolveWebAppUrl(base, "/root.png"))
        assertEquals("https://app.example.com/inbox/rel.png", resolveWebAppUrl(base, "rel.png"))
        assertNull(resolveWebAppUrl(base, "   "))
    }

    @Test
    fun `input url normalization defaults to https and rejects garbage`() {
        assertEquals("https://web.telegram.org/", normalizeWebAppInputUrl("web.telegram.org"))
        assertEquals("https://web.telegram.org/a/", normalizeWebAppInputUrl(" https://web.telegram.org/a/ "))
        assertNull(normalizeWebAppInputUrl("http://plain.example.com"))
        assertNull(normalizeWebAppInputUrl("not a url"))
        assertNull(normalizeWebAppInputUrl(""))
    }

    @Test
    fun `title badge heuristic reads prefix and modest suffix counters`() {
        assertEquals(3, parseTitleBadge("(3) Inbox"))
        assertEquals(12, parseTitleBadge("chat (12)"))
        assertEquals(0, parseTitleBadge("(0) quiet"))
        assertNull(parseTitleBadge("no badge here"))
        // A large suffix is almost certainly a year or content counter, not a badge.
        assertNull(parseTitleBadge("meeting notes (2026)"))
        assertNull(parseTitleBadge(null))
    }

    @Test
    fun `telegram web app is accepted from every shape a user pastes`() {
        val expected = "https://web.telegram.org/"
        assertEquals(expected, normalizeWebAppInputUrl("https://web.telegram.org"))
        assertEquals(expected, normalizeWebAppInputUrl("https://web.telegram.org/"))
        assertEquals(expected, normalizeWebAppInputUrl("web.telegram.org"))
        assertEquals(expected, normalizeWebAppInputUrl("  https://web.telegram.org  "))
        assertEquals("https://web.telegram.org/k/", normalizeWebAppInputUrl("https://web.telegram.org/k/"))
        // Uppercase scheme and host are still the same app.
        assertEquals(expected, normalizeWebAppInputUrl("HTTPS://WEB.TELEGRAM.ORG"))
    }
}
