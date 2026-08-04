package com.foxhole.guard.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DnsFilterBundledAssetContractTest {
    private val assetDir =
        sequenceOf(File("src/main/assets/rule-sets"), File("app/src/main/assets/rule-sets"))
            .first(File::isDirectory)

    @Test
    fun `the pinned constants describe the asset that actually ships`() {
        // The offline-fallback chain is: enabling the filter tries the update
        // channel, and when that fails, prepareVerifiedOrNull() installs the
        // bundled asset — validated against these constants. Nothing else ties
        // the constants to the file, so an asset swap that forgets them turns
        // the fallback off without failing a single existing test: enabling
        // the filter silently starts to require a working network. That is
        // exactly what happened on a Pixel with a stale remote manifest.
        val asset = File(assetDir, DnsFilterAssetInstaller.EMBEDDED_DNS_FILTER_FILE_NAME)
        assertEquals(DnsFilterAssetInstaller.EMBEDDED_DNS_FILTER_SIZE, asset.length())
        assertEquals(
            DnsFilterAssetInstaller.EMBEDDED_DNS_FILTER_SHA256,
            MessageDigest
                .getInstance("SHA-256")
                .digest(asset.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    @Test
    fun `the provenance document describes the same bytes`() {
        // source.json is what a reviewer reads to learn where the bundled list
        // came from; if it describes different bytes than the ones shipped,
        // the provenance is fiction.
        val source =
            Json.parseToJsonElement(File(assetDir, "adguard-dns-filter.source.json").readText())
                .jsonObject
                .getValue("artifact")
                .jsonObject
        assertEquals(
            DnsFilterAssetInstaller.EMBEDDED_DNS_FILTER_SIZE,
            source.getValue("size").jsonPrimitive.long,
        )
        assertEquals(
            DnsFilterAssetInstaller.EMBEDDED_DNS_FILTER_SHA256,
            source.getValue("sha256").jsonPrimitive.content,
        )
    }
}
