package com.foxhole.guard.router

import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterContractFixtureTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(
        "/android-router-contract-v1/$name",
    )).use { input -> input.readNBytes(524289).also { require(it.size in 1..524288) } }

    @Test fun commonCatalogFixturesUseTheProductionParser() {
        val body = fixture("catalog-valid.json")
        val digest = sha256RouterCertificate(body)
        val catalog = RouterInstallCatalog.parseVerified(body, digest, "c".repeat(64))
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), catalog.defaultSelection?.protocols)
        assertTrue(catalog.accepts(setOf(RouterInstallProtocol.HYSTERIA2)))
        val invalid = fixture("catalog-invalid.json")
        assertThrows(IllegalArgumentException::class.java) {
            RouterInstallCatalog.parseVerified(invalid, sha256RouterCertificate(invalid), "c".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouterInstallCatalog.parseVerified(body + byteArrayOf(32), digest, "c".repeat(64))
        }
    }

    @Test fun commonTlsFixturesRetainPinAndCertificateValidityChecks() {
        val data = Json.parseToJsonElement(fixture("tls.json").toString(Charsets.UTF_8)).jsonObject
        val encoded = data.getValue("encodedHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pin = data.getValue("sha256").jsonPrimitive.content
        assertEquals(pin, sha256RouterCertificate(encoded))
        val endpoint = RouterEndpoint(data.getValue("syntheticLanAddress").jsonPrimitive.content)
        val identity = RouterTlsIdentity(endpoint, pin)
        assertEquals(data.getValue("displayFingerprint").jsonPrimitive.content, identity.displayFingerprint)
        val trust = RouterPinnedTls(identity)
        trust.checkServerTrusted(arrayOf(SyntheticCertificate(encoded)), "RSA")
        assertThrows(CertificateException::class.java) {
            RouterPinnedTls(RouterTlsIdentity(endpoint, data.getValue("wrongPin").jsonPrimitive.content))
                .checkServerTrusted(arrayOf(SyntheticCertificate(encoded)), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(SyntheticCertificate(encoded, expired = true)), "RSA")
        }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(Array(9) { SyntheticCertificate(encoded) }, "RSA")
        }
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(SyntheticCertificate(ByteArray(16385))), "RSA")
        }
        assertThrows(CertificateException::class.java) { trust.checkClientTrusted(arrayOf(SyntheticCertificate(encoded)), "RSA") }
        data.getValue("invalidPins").jsonArray.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RouterTlsIdentity(endpoint, value.jsonPrimitive.content) }
        }
    }

    // This tests trust-manager admission with synthetic bytes; it is not a DER/parser or TLS handshake test.
    private class SyntheticCertificate(private val bytes: ByteArray, private val expired: Boolean = false) : X509Certificate() {
        override fun checkValidity() { if (expired) throw CertificateExpiredException() }
        override fun checkValidity(date: Date?) = checkValidity()
        override fun getEncoded(): ByteArray = bytes.copyOf()
        override fun getVersion() = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = X500Principal("CN=synthetic")
        override fun getSubjectDN(): Principal = X500Principal("CN=synthetic")
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = bytes.copyOf()
        override fun getSignature() = byteArrayOf()
        override fun getSigAlgName() = "NONE"
        override fun getSigAlgOID() = "0.0"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, provider: String?) = Unit
        override fun getPublicKey(): PublicKey = object : PublicKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "X.509"
            override fun getEncoded() = byteArrayOf()
        }
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun toString() = "SyntheticCertificate"
    }
}
