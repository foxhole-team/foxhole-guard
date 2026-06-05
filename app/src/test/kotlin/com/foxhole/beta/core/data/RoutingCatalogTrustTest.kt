package com.foxhole.beta.core.data

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingCatalogTrustTest {
    @Test
    fun `trusted routing catalog checksum accepts payload without user warning`() {
        val body = """{"presets":[]}"""
        val checksum = body.toByteArray(Charsets.UTF_8).routingCatalogSha256Hex()

        requireTrustedRoutingCatalogPayload(
            catalogUrl = CATALOG_URL,
            finalUrl = CATALOG_URL,
            warningAcceptedAt = null,
            body = body,
            trustedCatalogSha256ByUrl = mapOf(CATALOG_URL to checksum),
        )
    }

    @Test
    fun `trusted routing catalog checksum rejects tampered payload`() {
        val body = """{"presets":[]}"""
        val error =
            runCatching {
                requireTrustedRoutingCatalogPayload(
                    catalogUrl = CATALOG_URL,
                    finalUrl = CATALOG_URL,
                    warningAcceptedAt = null,
                    body = body,
                    trustedCatalogSha256ByUrl = mapOf(CATALOG_URL to "0".repeat(64)),
                )
            }.exceptionOrNull() ?: throw AssertionError("expected routing catalog checksum mismatch")

        assertEquals("routing catalog sha256 mismatch", error.message)
    }

    @Test
    fun `user supplied routing catalog requires warning acceptance when no shipped checksum exists`() {
        requireTrustedRoutingCatalogPayload(
            catalogUrl = CATALOG_URL,
            finalUrl = CATALOG_URL,
            warningAcceptedAt = 1_706_000_000_000L,
            body = """{"presets":[]}""",
            trustedCatalogSha256ByUrl = emptyMap(),
        )

        val error =
            runCatching {
                requireTrustedRoutingCatalogPayload(
                    catalogUrl = CATALOG_URL,
                    finalUrl = CATALOG_URL,
                    warningAcceptedAt = null,
                    body = """{"presets":[]}""",
                    trustedCatalogSha256ByUrl = emptyMap(),
                )
            }.exceptionOrNull() ?: throw AssertionError("expected routing catalog warning requirement")

        assertEquals("routing catalog requires trusted checksum or user warning", error.message)
    }

    @Test
    fun `routing catalog refresh only accepts json-like content types`() {
        requireRoutingCatalogJsonContent(Headers.headersOf("Content-Type", "application/json; charset=utf-8"))
        requireRoutingCatalogJsonContent(Headers.headersOf("Content-Type", "text/plain"))

        val error =
            runCatching {
                requireRoutingCatalogJsonContent(Headers.headersOf("Content-Type", "application/x-msdownload"))
            }.exceptionOrNull() ?: throw AssertionError("expected executable routing catalog content type rejection")

        assertEquals("routing catalog response must be JSON", error.message)
    }

    @Test
    fun `routing catalog refresh rejects executable filenames`() {
        requireSafeRoutingCatalogUrlPath(CATALOG_URL.toHttpUrl())

        val error =
            runCatching {
                requireSafeRoutingCatalogUrlPath("https://catalogs.example.org/routing/catalog.apk".toHttpUrl())
            }.exceptionOrNull() ?: throw AssertionError("expected executable routing catalog filename rejection")

        assertEquals("routing catalog URL points to executable content", error.message)
    }

    private companion object {
        const val CATALOG_URL = "https://catalogs.example.org/routing/catalog.json"
    }
}
