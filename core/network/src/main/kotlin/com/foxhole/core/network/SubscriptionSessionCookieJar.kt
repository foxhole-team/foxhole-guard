package com.foxhole.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Cookies live only for one bounded subscription request and its redirects. */
class SubscriptionSessionCookieJar : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)
    private val cookies = linkedMapOf<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        removeExpired()
        for (cookie in cookies) {
            val key = Key(cookie.name, cookie.domain, cookie.path)
            this.cookies.remove(key)
            if (cookie.expiresAt <= System.currentTimeMillis() || cookie.toString().toByteArray(Charsets.UTF_8).size > MAX_COOKIE_BYTES) continue
            this.cookies[key] = cookie
            while (this.cookies.size > MAX_COOKIES || this.cookies.values.sumOf { it.toString().toByteArray(Charsets.UTF_8).size } > MAX_TOTAL_BYTES) {
                this.cookies.remove(this.cookies.keys.first())
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpired()
        return cookies.values.filter { it.matches(url) }
    }

    private fun removeExpired() {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt <= now }
    }

    private companion object {
        const val MAX_COOKIES = 32
        const val MAX_COOKIE_BYTES = 4096
        const val MAX_TOTAL_BYTES = 16 * 1024
    }
}
