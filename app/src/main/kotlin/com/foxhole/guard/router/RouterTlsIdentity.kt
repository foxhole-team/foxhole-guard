package com.foxhole.guard.router

import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
internal data class RouterTlsIdentity(val endpoint: RouterEndpoint, val certificateSha256: String) {
    init { require(certificateSha256.matches(Regex("[a-f0-9]{64}"))) }
    val displayFingerprint: String get() = certificateSha256.chunked(4).joinToString(" ")
}

internal class RouterIdentityChanged : IOException("router_identity_changed")

internal fun inspectRouterIdentity(connection: RouterWifiConnection, operation: RouterIoOperation): RouterTlsIdentity {
    connection.requireCurrent()
    val observed = AtomicReference<X509Certificate?>()
    val observer = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || chain.size > 8 || chain[0].encoded.size > 16384) throw CertificateException()
            chain[0].checkValidity()
            observed.set(chain[0])
            // Observation cannot authenticate or carry a PIN; the handshake is deliberately refused.
            throw CertificateException("router_identity_review")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(observer), null) }
    connection.createSocket().use { socket ->
        operation.register(socket)
        socket.connect(connection.endpoint.socketAddress(), 15000)
        socket.soTimeout = 15000
        (context.socketFactory.createSocket(socket, connection.endpoint.address, connection.endpoint.port, true) as SSLSocket).use { secure ->
            secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            try { secure.startHandshake() } catch (_: IOException) { /* Candidate is inspected below. */ }
        }
    }
    val candidate = observed.get() ?: throw RouterIdentityChanged()
    connection.requireCurrent()
    return RouterTlsIdentity(connection.endpoint, sha256RouterCertificate(candidate.encoded))
}

internal class RouterPinnedTls(private val identity: RouterTlsIdentity) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty() || chain.size > 8 || chain[0].encoded.size > 16384) throw CertificateException()
        chain[0].checkValidity()
        val expected = identity.certificateSha256.toByteArray(Charsets.US_ASCII)
        val actual = sha256RouterCertificate(chain[0].encoded).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("router_identity_changed")
    }
    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(this@RouterPinnedTls), null)
    }.socketFactory
}

internal fun sha256RouterCertificate(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
