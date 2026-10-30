package com.foxhole.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SubscriptionSessionCookieJarTest {
    private val origin = "https://subscription.example/start".toHttpUrl()

    @Test
    fun `challenge cookie survives a same host redirect but stays out of other hosts and sessions`() {
        val jar = SubscriptionSessionCookieJar()
        jar.saveFromResponse(origin, listOf(requireNotNull(Cookie.parse(origin, "challenge=synthetic; Path=/; Secure; HttpOnly"))))
        assertEquals(listOf("synthetic"), jar.loadForRequest("https://subscription.example/next".toHttpUrl()).map { it.value })
        assertTrue(jar.loadForRequest("https://other.example/next".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://subscription.example/next".toHttpUrl()).isEmpty())
        assertTrue(SubscriptionSessionCookieJar().loadForRequest(origin).isEmpty())
    }

    @Test
    fun `path domain expiry replacement and bounded storage follow cookie semantics`() {
        val jar = SubscriptionSessionCookieJar()
        jar.saveFromResponse(origin, listOf(requireNotNull(Cookie.parse(origin, "token=old; Path=/later"))))
        assertTrue(jar.loadForRequest(origin).isEmpty())
        val later = "https://subscription.example/later/entry".toHttpUrl()
        assertEquals("old", jar.loadForRequest(later).single().value)
        jar.saveFromResponse(origin, listOf(requireNotNull(Cookie.parse(origin, "token=new; Path=/later"))))
        assertEquals("new", jar.loadForRequest(later).single().value)
        jar.saveFromResponse(origin, listOf(requireNotNull(Cookie.parse(origin, "token=gone; Path=/later; Max-Age=0"))))
        assertTrue(jar.loadForRequest(later).isEmpty())
        repeat(40) { index -> jar.saveFromResponse(origin, listOf(Cookie.Builder().name("n$index").value("v".repeat(1000)).hostOnlyDomain(origin.host).build())) }
        assertTrue(jar.loadForRequest(origin).size <= 32)
        assertTrue(jar.loadForRequest(origin).sumOf { it.toString().toByteArray(Charsets.UTF_8).size } <= 16 * 1024)
        jar.saveFromResponse(origin, listOf(Cookie.Builder().name("huge").value("x".repeat(4097)).hostOnlyDomain(origin.host).build()))
        assertTrue(jar.loadForRequest(origin).none { it.name == "huge" })
    }
}
