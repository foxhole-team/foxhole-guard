package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RouterInstallCatalogTest {
    companion object {
        private val digest = "a".repeat(64)
        private val manifest = "b".repeat(64)

        internal fun catalog(vararg variants: Set<RouterInstallProtocol>, target: String = "x86/64",
            versions: List<List<String>>? = null, serverFields: Map<String, JsonElement> = emptyMap(), architecture: String = "x86_64"): RouterInstallCatalog {
            val all = RouterInstallProtocol.entries.map { it.canonical }.sorted()
            val document = buildJsonObject {
                put("schema", 1); put("development", false)
                put("defaultProtocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
                put("allProtocols", JsonArray(all.map(::JsonPrimitive)))
                put("variants", JsonArray(variants.mapIndexed { index, protocols -> buildJsonObject {
                    put("id", "variant-$index"); put("architecture", architecture)
                    put("supportedOpenWrt", JsonArray((versions?.get(index) ?: listOf("25.12.5")).map(::JsonPrimitive)))
                    put("supportedTargets", JsonArray(listOf(JsonPrimitive(target))))
                    put("protocols", JsonArray(protocols.map { JsonPrimitive(it.canonical) }))
                    put("coreProducerFile", "producer-$index.json"); put("coreProducerSha256", digest)
                    put("coreBinarySha256", digest); put("coreSourceSha256", digest)
                    serverFields.forEach { (key, value) -> put(key, value) }
                    put("capabilitiesFile", "capabilities-$index.json"); put("capabilitiesSha256", digest)
                    put("modes", buildJsonObject {
                        put("flash", buildJsonObject { put("budgetFile", "budget-$index.json"); put("budgetSha256", digest) })
                    })
                } }))
            }.toString().toByteArray()
            return RouterInstallCatalog.parseVerified(document, sha256(document), manifest)
        }

        private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun defaultIsOnlyHysteriaAndUnavailableCombinationsAreNeverExpanded() {
        val hy2 = setOf(RouterInstallProtocol.HYSTERIA2)
        val vless = setOf(RouterInstallProtocol.VLESS)
        val catalog = catalog(hy2, vless)
        assertEquals(hy2, catalog.defaultSelection?.protocols)
        assertTrue(catalog.accepts(vless))
        assertFalse(catalog.accepts(hy2 + vless))
        assertEquals(null, catalog.allSelection)
    }

    @Test fun allIsAvailableOnlyForAnExactFullVerifiedVariant() {
        val catalog = catalog(setOf(RouterInstallProtocol.HYSTERIA2), RouterInstallProtocol.entries.toSet())
        assertEquals(RouterInstallProtocol.entries.toSet(), catalog.allSelection?.protocols)
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), catalog.defaultSelection?.protocols)
    }

    @Test fun inspectionBindingMustMatchSelectedVariantModeAndProducerDigests() {
        val catalog = catalog(setOf(RouterInstallProtocol.HYSTERIA2), setOf(RouterInstallProtocol.VLESS))
        val selection = requireNotNull(catalog.defaultSelection)
        val variant = catalog.variants.first()
        val binding = RouterInstallBinding(selection, variant.id, variant.architecture, RouterStorageMode.FLASH,
            variant.coreProducerSha256, variant.coreBinarySha256, variant.coreSourceSha256, variant.capabilitiesSha256,
            variant.modes.getValue(RouterStorageMode.FLASH).sha256, "x86/64")
        assertTrue(catalog.matches(binding, "25.12.5", catalog.manifestSha256))
        assertFalse(catalog.matches(binding.copy(budgetSha256 = "f".repeat(64)), "25.12.5", catalog.manifestSha256))
        assertFalse(catalog.matches(binding.copy(storageMode = RouterStorageMode.RAM), "25.12.5", catalog.manifestSha256))
        assertFalse(catalog.matches(binding.copy(variantId = catalog.variants.last().id), "25.12.5", catalog.manifestSha256))
        assertFalse(catalog.matches(binding, "25.12.4", catalog.manifestSha256))
        assertFalse(catalog.matches(binding, "25.12.5", "f".repeat(64)))
        assertFalse(catalog.matches(binding.copy(target = "armsr/armv8"), "25.12.5", catalog.manifestSha256))
        assertFalse(catalog.matches(binding.copy(target = "mediatek/filogic"), "25.12.5", catalog.manifestSha256))
    }

    @Test fun aMissingDefaultBuildCannotEnableSetup() {
        assertEquals(null, catalog(setOf(RouterInstallProtocol.VLESS)).defaultSelection)
    }

    @Test fun catalogTrustDigestMustMatchTheExactReceivedBytes() {
        val body = "{}".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            RouterInstallCatalog.parseVerified(body, "f".repeat(64), manifest)
        }
    }

    @Test fun aCatalogCannotAssignAnUntestedOrMismatchedTargetToAnArchitecture() {
        for (target in listOf("armsr/armv8", "mediatek/filogic")) {
            assertThrows(IllegalArgumentException::class.java) {
                catalog(setOf(RouterInstallProtocol.HYSTERIA2), target = target)
            }
        }
    }

    @Test fun overlappingFirmwareCoverageCannotChooseAnAmbiguousPackage() {
        val hy2 = setOf(RouterInstallProtocol.HYSTERIA2)
        assertThrows(IllegalArgumentException::class.java) {
            catalog(hy2, hy2, versions = listOf(listOf("25.12.5"), listOf("25.12.4", "25.12.5")))
        }
    }

    @Test fun historicalServerlessCatalogRemainsReadableAndPairedServerProducerIsRetained() {
        val protocols = setOf(RouterInstallProtocol.HYSTERIA2)
        assertEquals(null, catalog(protocols).variants.single().serverProducer)
        val paired = catalog(protocols, serverFields = mapOf("serverProducerFile" to JsonPrimitive("hy2-server-producer.json"),
            "serverProducerSha256" to JsonPrimitive(digest)))
        assertEquals(RouterInstallAsset("hy2-server-producer.json", digest), paired.variants.single().serverProducer)
    }

    @Test fun aPartialOrUnsafeServerProducerReferenceCannotBeAdmitted() {
        val protocols = setOf(RouterInstallProtocol.HYSTERIA2)
        val valid = mapOf("serverProducerFile" to JsonPrimitive("hy2-server-producer.json"), "serverProducerSha256" to JsonPrimitive(digest))
        for (fields in listOf(valid - "serverProducerFile", valid - "serverProducerSha256",
            valid + ("serverProducerFile" to JsonPrimitive("../producer.json")),
            valid + ("serverProducerFile" to JsonPrimitive("nested/producer.json")),
            valid + ("serverProducerSha256" to JsonPrimitive("A".repeat(64))))) {
            assertThrows(IllegalArgumentException::class.java) { catalog(protocols, serverFields = fields) }
        }
        assertThrows(IllegalStateException::class.java) {
            catalog(protocols, serverFields = mapOf("serverProducerFile" to JsonNull, "serverProducerSha256" to JsonNull))
        }
    }


    @Test fun verifiedFilogicMetadataMapsOnlyToItsExactArchitectureAndTarget() {
        val protocols = setOf(RouterInstallProtocol.HYSTERIA2)
        assertEquals("aarch64_cortex-a53", catalog(protocols, target = "mediatek/filogic", architecture = "aarch64_cortex-a53").variants.single().architecture)
        for ((arch, target) in listOf("aarch64_cortex-a53" to "armsr/armv8", "aarch64_cortex-a53" to "x86/64",
            "aarch64_generic" to "mediatek/filogic", "aarch64_other" to "mediatek/filogic")) {
            assertThrows(IllegalArgumentException::class.java) { catalog(protocols, target = target, architecture = arch) }
        }
    }

}
