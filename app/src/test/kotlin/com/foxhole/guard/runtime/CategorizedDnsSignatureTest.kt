package com.foxhole.guard.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CategorizedDnsSignatureTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.public.encoded) +
        "\n-----END PUBLIC KEY-----"
    private val client = DnsFilterUpdateClient(OkHttpClient(), Json, publicKeyPem = pem, nowUnixSeconds = { 1500 })

    @Test
    fun signedCanonicalUnsignedMaximumIsAcceptedWithoutWrapping() {
        val bytes = manifestBytes(ULong.MAX_VALUE)
        assertEquals(ULong.MAX_VALUE, client.verifyCategorizedManifest(bytes, sign(bytes)).sequence)
    }

    @Test
    fun wrongSignatureIsRejectedBeforeMalformedJsonParserRuns() {
        val malformed = "{not json".encodeToByteArray()
        val error = assertThrows(IllegalStateException::class.java) {
            client.verifyCategorizedManifest(malformed, sign(manifestBytes()))
        }
        assertTrue(error.message.orEmpty().contains("signature verification failed"))
    }

    @Test
    fun validSignatureNeverAllowsNoncanonicalOrDuplicateJson() {
        val bytes = manifestBytes().decodeToString()
        listOf(bytes + "\n", bytes.replace("\"schema\":3", "\"schema\":3,\"schema\":3")).forEach { text ->
            val input = text.encodeToByteArray()
            assertThrows(IllegalArgumentException::class.java) { client.verifyCategorizedManifest(input, sign(input)) }
        }
    }

    @Test
    fun signedManifestCannotMintItsOwnPublicKeyIdentity() {
        val input = manifestBytes().decodeToString().replace(
            CategorizedDnsContract.sha256(key.public.encoded),
            "0".repeat(64)
        )
            .encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) { client.verifyCategorizedManifest(input, sign(input)) }
    }

    private fun manifestBytes(sequence: ULong = 1uL): ByteArray {
        val manifest = bundle(sequence).manifest.copy(keySha256 = CategorizedDnsContract.sha256(key.public.encoded))
        return (canonicalAsciiJson(Json.parseToJsonElement(Json.encodeToString(manifest))) + "\n").encodeToByteArray()
    }

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private)
        update(bytes)
        sign()
    }
}
