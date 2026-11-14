package com.foxhole.guard.router

import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Test-only transport for immutable locked snapshots not yet present in the public release. */
internal fun ownedLabSnapshotCalls(fixture: File, publicClient: OkHttpClient): Call.Factory {
    fun protected(file: File, maximum: Int): ByteArray {
        val path = file.toPath()
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && file.length() in 1L..maximum.toLong())
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        require(Files.getPosixFilePermissions(requireNotNull(file.parentFile).toPath(), NOFOLLOW_LINKS) == setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        return file.readBytes()
    }
    val fixtureBytes = protected(fixture, 4096)
    val fixtureValue = try { Json.parseToJsonElement(fixtureBytes.toString(Charsets.UTF_8)).jsonObject } finally { fixtureBytes.fill(0) }
    val descriptorPath = fixtureValue["publisherMirrorDescriptor"]?.jsonPrimitive?.content ?: return publicClient
    require(fixtureValue["ownedEphemeral"]!!.jsonPrimitive.boolean)
    val descriptor = File(descriptorPath)
    val value = Json.parseToJsonElement(protected(descriptor, 65536).toString(Charsets.UTF_8)).jsonObject
    require(value["schema"]!!.jsonPrimitive.int == 1 && value["ownedEphemeral"]!!.jsonPrimitive.boolean)
    require(value["host"]!!.jsonPrimitive.content == "127.0.0.1")
    require(value["signedManifestSHA256"]!!.jsonPrimitive.content == "b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d")
    val port = value["port"]!!.jsonPrimitive.int.also { require(it in 20000..65000) }
    val names = value["files"]!!.jsonArray.map { it.jsonPrimitive.content }
    require(names.size == 147 && names.distinct().size == names.size && names.all { it.matches(Regex("apk-[a-f0-9]{64}\\.apk")) })
    val certificateFile = File(value["certificatePemPath"]!!.jsonPrimitive.content)
    require(certificateFile.name == "mirror.crt.pem" && certificateFile.parentFile.canonicalFile == descriptor.parentFile.canonicalFile)
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(protected(certificateFile, 16384).inputStream()) as X509Certificate
    certificate.checkValidity()
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(fingerprint == value["certificateSHA256"]!!.jsonPrimitive.content)
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-snapshot-mirror", certificate) }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.single() as X509TrustManager
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val local = publicClient.newBuilder().sslSocketFactory(context.socketFactory, trust)
        .dns(Dns { host -> require(host == "127.0.0.1"); listOf(InetAddress.getByName(host)) }).build()
    val endpoint = "https://127.0.0.1:$port/".toHttpUrl()
    val prefix = "/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6/"
    return Call.Factory { request ->
        val name = request.url.encodedPath.removePrefix(prefix)
        if (request.url.host == "github.com" && request.url.isHttps && request.url.port == 443 &&
            request.url.query == null && request.url.fragment == null && request.url.encodedPath == prefix + name && name in names) {
            require(request.method == "GET" && request.body == null && request.header("Authorization") == null && request.header("Cookie") == null)
            println("LAB frozen publisher snapshot=$name")
            local.newCall(request.newBuilder().url(endpoint.newBuilder().addPathSegment(name).build()).build())
        } else publicClient.newCall(request)
    }
}
