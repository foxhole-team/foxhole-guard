package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsFilterCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CategorizedDnsApkCatalogTest {
    @Test
    fun packageCatalogHasKnownVariantsAndExplicitAvailability() {
        val catalog = parse(bytes())
        assertEquals(4, catalog.variants.size)
        val entry = CategorizedDnsApkContract.select(catalog, selection(DnsFilterCategory.ADS))
        assertEquals(setOf(DnsFilterCategory.ADS), entry.availableCategories())
        entry.requireArtifact(bundle().artifactBytes)
    }

    @Test
    fun missingRequiredCategoryNeverUsesLegacyMergedFallback() {
        assertThrows(IllegalArgumentException::class.java) {
            CategorizedDnsApkContract.select(parse(bytes()), selection(DnsFilterCategory.TELEMETRY))
        }
    }

    @Test
    fun unknownFieldsDuplicateKeysAndNumericStringsAreRefused() {
        val valid = bytes().decodeToString()
        listOf(
            valid.replace("\"schema\":1", "\"schema\":1,\"unknown\":true"),
            valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            valid.replace("\"size\":100", "\"size\":\"100\"")
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parse(raw.encodeToByteArray()) }
        }
    }

    @Test
    fun pathEscapeUnknownVariantAndTierNameMismatchAreRefused() {
        val valid = bytes().decodeToString()
        listOf(
            valid.replace("dns-categories/normal-medium/dns-categories.fhds", "../downloaded/dns-categories.fhds"),
            valid.replace("normal-medium", "unknown-medium"),
            valid.replace("foxhole-dns-categories-normal-medium", "foxhole-dns-categories-pro-medium")
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parse(raw.encodeToByteArray()) }
        }
    }

    @Test
    fun packageArtifactHeaderOrHashMismatchIsRefused() {
        val entry = parse(bytes()).variants.getValue("normal-medium")
        val artifact = bundle().artifactBytes.copyOf()
        artifact[8] = 1
        assertThrows(IllegalArgumentException::class.java) { entry.requireArtifact(artifact) }
        assertThrows(IllegalArgumentException::class.java) { entry.requireArtifact(byteArrayOf(1)) }
    }

    private fun selection(category: DnsFilterCategory) = CategorizedDnsSelection(
        CategorizedDnsTiers("normal", "medium"),
        setOf(category),
    )

    private fun bytes(): ByteArray {
        val artifact = bundle().artifactBytes
        val variants = listOf("normal-medium", "normal-full", "pro-medium", "pro-full").associateWith { variant ->
            CategorizedDnsApkEntry(
                CategorizedDnsContract.NAME_PREFIX + variant,
                "dns-categories/$variant/dns-categories.fhds",
                CategorizedDnsContract.sha256(artifact),
                100uL,
                1uL,
                CategorizedDnsCounts(0uL, 0uL, 0uL, 1uL),
                "dns-categories/$variant/source-info.json"
            )
        }
        val element = Json.parseToJsonElement(Json.encodeToString(CategorizedDnsApkCatalog(1, variants)))
        return (canonicalAsciiJson(element) + "\n").encodeToByteArray()
    }

    private fun parse(bytes: ByteArray) = CategorizedDnsApkContract.parse(bytes)
}
