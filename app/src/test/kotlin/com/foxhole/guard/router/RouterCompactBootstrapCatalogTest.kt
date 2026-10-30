package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Synthetic metadata relationships only; none of these digests identify a released package. */
class RouterCompactBootstrapCatalogTest {
    @Test fun defaultIsOneReleasedHysteriaClientWithoutARequiredServer() {
        val catalog = parse(catalog(client("hysteria2")))
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), catalog.defaultSelection?.protocols)
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), catalog.availableProtocols.toSet())
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), catalog.allSelection?.protocols)
    }

    @Test fun vlessOnlyPlatformDoesNotInsertAnUnreleasedHysteriaClient() {
        val catalog = parse(catalog(client("vless")))
        assertEquals(setOf(RouterInstallProtocol.VLESS), catalog.availableProtocols.toSet())
        assertNotNull(catalog.selection(setOf(RouterInstallProtocol.VLESS)))
        assertNull(catalog.defaultSelection)
        assertFalse(catalog.accepts(setOf(RouterInstallProtocol.HYSTERIA2)))
        assertFalse(catalog.accepts(setOf(RouterInstallProtocol.TUIC)))
    }

    @Test fun selectedModulesComposeOnlyWhenTheyHaveACommonStorageMode() {
        val actual = parse(catalog(client("hysteria2"), client("vless"), client("wireguard")))
        val wanted = setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        assertEquals(wanted, actual.selection(wanted)?.protocols)
        assertTrue(actual.accepts(wanted))
        assertFalse(actual.accepts(wanted + RouterInstallProtocol.TUIC))
        val disjoint = parse(catalog(client("hysteria2"), client("vless", "ram")))
        assertFalse(disjoint.accepts(setOf(RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.VLESS)))
    }

    @Test fun allSelectsOnlyReleasedCompactModulesWithACommonStorageMode() {
        val three = setOf(RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        val catalog = parse(catalog(client("hysteria2"), client("vless"), client("wireguard")))
        assertEquals(three, catalog.allSelection?.protocols)
        assertFalse(catalog.accepts(three + RouterInstallProtocol.TUIC))
        val disjoint = parse(catalog(client("hysteria2"), client("vless", "ram")))
        assertNull(disjoint.allSelection)
    }

    @Test fun legacyV1StillRequiresItsExactMonolithicProtocolSet() {
        val legacy = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2), setOf(RouterInstallProtocol.VLESS))
        assertNotNull(legacy.selection(setOf(RouterInstallProtocol.VLESS)))
        assertFalse(legacy.accepts(setOf(RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.VLESS)))
        assertNull(legacy.allSelection)
    }

    @Test fun duplicateModuleAndCrossModuleArtifactIdentitiesAreRejected() {
        val valid = client("vless")
        reject(catalog(valid, valid))
        reject(catalog(valid.with("slotManifest", asset("vless-slot.json", "f".repeat(64)))))
        reject(catalog(valid.with("producer", asset("vless-producer.json", "f".repeat(64)))))
        reject(catalog(valid.with("capabilities", asset("vless-capabilities.json", "f".repeat(64)))))
    }

    @Test fun unsupportedPlatformProtocolAndNonBooleanDevelopmentAreRejected() {
        val valid = catalog(client("hysteria2"))
        reject(valid.with("development", JsonPrimitive("false")))
        reject(valid.with("platforms", JsonArray(listOf(platform(client("hysteria2")).with("supportedTargets", strings("armsr/armv8"))))))
        reject(catalog(client("not-released-protocol")))
        reject(catalog(client("hysteria2").with("storage", JsonPrimitive("auto"))))
    }

    @Test fun unsafeAssetPathsAndMixedCatalogShapesCannotFallBackToLegacy() {
        val valid = client("hysteria2")
        for (name in listOf("../producer.json", "nested/producer.json", "https://example.test/producer.json")) {
            reject(catalog(valid.with("producer", asset(name))))
        }
        reject(catalog(valid).with("variants", JsonArray(emptyList())))
        reject(catalog(valid).with("kind", JsonPrimitive("legacy-client")))
    }

    @Test fun receivedCatalogBytesMustMatchTheirAuthenticatedDigest() {
        val body = catalog(client("hysteria2")).toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            RouterInstallCatalog.parseVerified(body, "f".repeat(64), MANIFEST)
        }
    }

    private fun parse(value: JsonObject): RouterInstallCatalog {
        val bytes = value.toString().toByteArray()
        return RouterInstallCatalog.parseVerified(bytes, sha(bytes), MANIFEST)
    }

    private fun reject(value: JsonObject) {
        assertThrows(RuntimeException::class.java) { parse(value) }
    }

    private fun catalog(vararg modules: JsonObject) = buildJsonObject {
        put("schema", 2); put("kind", "compact-client-engines-v1"); put("development", false)
        put("defaultProtocols", strings("hysteria2"))
        put("allProtocols", strings(*RouterInstallProtocol.entries.map { it.canonical }.sorted().toTypedArray()))
        put("platforms", JsonArray(listOf(platform(*modules))))
    }

    private fun platform(vararg modules: JsonObject) = buildJsonObject {
        put("id", "x86-25.12.5"); put("architecture", "x86_64")
        put("supportedTargets", strings("x86/64")); put("supportedOpenWrt", strings("25.12.5"))
        put("basePackage", buildJsonObject {
            put("name", "foxhole-openwrt-client"); put("file", "panel.apk"); put("sha256", HASH); put("packageIdentity", HASH)
        })
        put("engineCatalog", asset("engines-catalog.json")); put("packageUniverse", asset("universe.json"))
        put("clients", JsonArray(modules.toList())); put("server", JsonNull)
    }

    private fun client(id: String, storage: String = "flash"): JsonObject {
        val slot = sha("synthetic-$id-$storage-slot".toByteArray())
        val name = "foxcore-engine-$id-$storage-$slot"
        return buildJsonObject {
            put("id", id); put("storage", storage)
            put("entry", buildJsonObject {
                put("id", id); put("storage", storage.uppercase()); put("version", "1.0.0"); put("architecture", "x86_64")
                for (field in listOf("binarySha256", "executableSha256", "producerSha256", "capabilitiesSha256", "legalProvenanceSha256", "packageSha256", "indexSha256")) put(field, HASH)
                put("manifestSha256", slot); put("sourceCommit", "e".repeat(40))
                put("binaryBytes", 8192); put("archiveBytes", 1024); put("indexBytes", 512); put("installedBytes", 12288)
                put("runtimeMemoryBytes", 33554432); put("reserveBytes", 16777216)
                put("packageName", name); put("packageVersion", "1.0.0-r1")
                put("packageUrl", "$BASE/$name-1.0.0-r1.apk"); put("indexUrl", "$BASE/engines.adb")
            })
            put("producer", asset("$id-$storage-producer.json")); put("capabilities", asset("$id-$storage-capabilities.json"))
            put("slotManifest", asset("$id-$storage-slot.json", slot))
        }
    }

    private fun asset(file: String, hash: String = HASH) = buildJsonObject { put("file", file); put("sha256", hash) }
    private fun strings(vararg value: String) = JsonArray(value.map(::JsonPrimitive))
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val BASE = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0"
        val HASH = "a".repeat(64)
        val MANIFEST = "b".repeat(64)
    }
}
