package com.foxhole.core.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

internal class CategorizedDnsTranslationTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun adsAndTelemetryHaveIndependentExplicitMasks() {
        listOf("ads", "telemetry").forEach { category ->
            assertEquals(
                listOf(category),
                ruleSet(category).getValue("enabled_categories").jsonArray.map {
                    it.jsonPrimitive.content
                }
            )
        }
    }

    @Test
    fun unsignedFloorAboveSignedMaximumIsAnExactUnquotedNumber() {
        listOf(Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE).forEach { sequence ->
            val primitive = ruleSet("ads", bootstrap(sequence)).getValue("minimum_sequence").jsonPrimitive
            assertFalse(primitive.isString)
            assertEquals(sequence.toString(), primitive.content)
        }
    }

    @Test
    fun independentTagsCannotSilentlyUseLegacyMergedFeed() {
        assertThrows(IllegalArgumentException::class.java) { ruleSet("ads", bootstrap().copy(categoryVariant = null)) }
    }

    @Test
    fun missingRequiredCategoryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ruleSet("telemetry", bootstrap().copy(availableCategories = setOf(DnsFilterCategory.ADS)))
        }
    }

    @Test
    fun allOffAndMasterOffCreateNoDnsRulesEvenWithCapturedBootstrap() {
        val paths = DnsFilterRuntimePaths("/owned/artifact", bootstrap())
        assertEquals(emptyList<Any>(), buildDnsRules(DnsSettings(filteringEnabled = false), paths))
        assertEquals(
            emptyList<Any>(),
            buildDnsRules(
                DnsSettings(
                    filteringEnabled = true,
                    blockAds = false,
                    blockTrackers = false,
                    blockAppTelemetry = false,
                    blockMaliciousDomains = false
                ),
                paths
            )
        )
    }

    @Test
    fun generatedSelectedTagsDoNotRequireRouteCategoryPaths() {
        val rules = buildDnsRules(
            DnsSettings(
                filteringEnabled = true,
                blockAds = false,
                blockTrackers = false,
                blockMaliciousDomains = false
            ),
            DnsFilterRuntimePaths("/owned/artifact", bootstrap())
        )
        assertEquals(
            listOf("foxhole-dns-telemetry"),
            rules.single().getValue("rule_set").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
    }

    private fun bootstrap(sequence: ULong = 1uL) = FoxCoreDnsRuleSetBootstrap(
        name = "foxhole-dns-categories-normal-medium",
        artifactPath = "/owned/artifact",
        artifactSha256 = "a".repeat(64),
        publicKeyBase64 = "AQID",
        categoryMinimumSequence = sequence,
        categoryVariant = "normal-medium",
        availableCategories = DnsFilterCategory.entries.toSet(),
    )

    private fun ruleSet(category: String, value: FoxCoreDnsRuleSetBootstrap = bootstrap()) = engine(
        translate(
            hint = ProtocolHint.SHADOWSOCKS,
            primary = primary("shadowsocks") {
                put("server", "owned.invalid")
                put("server_port", 443)
                put("method", "aes-128-gcm")
                put("password", "synthetic-owned-fixture")
            },
            dnsRules = listOf(
                buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive("foxhole-dns-$category"))))
                    put("action", "predefined")
                    put("rcode", "NXDOMAIN")
                }
            ),
            dnsRuleSetBootstrap = value,
        )
    ).getValue("dns").jsonObject.getValue("rule_sets").jsonArray.single().jsonObject
}
