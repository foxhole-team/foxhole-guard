package com.foxhole.guard.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ThreatIntelCancellationRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun `cancelling the verified artifact fetch cannot install or publish stale progress`() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val installs = AtomicInteger()
        val staleCallbacks = AtomicInteger()
        val feed = feed("2026-10-02T01:00:00Z", "com.example.new")
        val client = feed.client { call, bytes ->
            gatedBody(bytes, entered, gate) { call.isCanceled() }
        }
        val store = object : ThreatIntelStore {
            override suspend fun installVerifiedThreatIntel(
                documentBytes: ByteArray,
                manifest: ThreatIntelManifest
            ): String {
                installs.incrementAndGet()
                return "owned-test-feed"
            }
        }
        val job = launch(Dispatchers.IO) {
            client.update(
                URL,
                store,
                onPhase = { if (cancelled.get()) staleCallbacks.incrementAndGet() },
                onProgress = { if (cancelled.get()) staleCallbacks.incrementAndGet() }
            )
        }
        try {
            assertTrue("artifact fetch must enter before cancellation", entered.await(3, TimeUnit.SECONDS))
            cancelled.set(true)
            job.cancel()
            gate.countDown()
            withTimeout(3_000) { job.join() }
            assertEquals("a cancelled refresh changed its store", 0, installs.get())
            assertEquals("a cancelled refresh published stale UI callbacks", 0, staleCallbacks.get())
        } finally {
            gate.countDown()
            job.cancel()
            withTimeout(3_000) { job.join() }
            feed.close()
        }
    }

    private fun feed(stamp: String, packageName: String): Feed {
        val payload = "{\n  \"schema\":4,\n  \"packages\":[\"$packageName\"]\n}\n".toByteArray()
        val manifest = ThreatIntelManifest(
            1,
            "foxhole-sentinel-threat-intel",
            "sentinel-threat-intel-json",
            stamp,
            ThreatIntelManifestSource("owned-fixture", "CC0-1.0", 1),
            ThreatIntelManifestArtifact("threat-intel.json", payload.size.toLong(), payload.sha256Hex()),
            ThreatIntelManifestCompatibility("0.0.1")
        )
        return Feed(payload, manifest)
    }

    private inner class Feed(val payload: ByteArray, val manifest: ThreatIntelManifest) {
        private val keys = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private val manifestBytes = json.encodeToString(ThreatIntelManifest.serializer(), manifest).toByteArray()
        private val signature = Signature.getInstance("SHA256withECDSA")
            .apply {
                initSign(keys.private)
                update(manifestBytes)
            }.sign()
        private val clients = mutableListOf<OkHttpClient>()
        fun client(artifact: ((okhttp3.Call, ByteArray) -> ResponseBody)? = null): ThreatIntelUpdateClient {
            val http = OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    val body = when (chain.request().url.encodedPath) {
                        "/threat-intel-manifest.json" -> manifestBytes.toResponseBody()
                        "/threat-intel-manifest.json.sig" -> signature.toResponseBody()
                        "/threat-intel.json" ->
                            artifact?.invoke(chain.call(), payload) ?: payload.toResponseBody()
                        else -> error("unexpected owned fixture path")
                    }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(body).build()
                }
            ).build()
            clients += http
            val pem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----"
            return ThreatIntelUpdateClient(
                http,
                json,
                pem,
                "0.1.1",
                { listOf(InetAddress.getByName("8.8.8.8")) },
                { now },
            )
        }
        fun close() {
            clients.forEach {
                it.dispatcher.executorService.shutdown()
                it.connectionPool.evictAll()
            }
        }
    }

    private fun gatedBody(
        bytes: ByteArray,
        entered: CountDownLatch,
        gate: CountDownLatch,
        isCancelled: () -> Boolean,
    ): ResponseBody =
        object : ResponseBody() {
            private val source = object : Source {
                private var delivered = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (delivered) return -1
                    entered.countDown()
                    check(gate.await(3, TimeUnit.SECONDS)) { "fixture was not released" }
                    if (isCancelled()) throw IOException("owned call cancelled")
                    delivered = true
                    sink.write(bytes)
                    return bytes.size.toLong()
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = bytes.size.toLong()
            override fun source(): BufferedSource = source
        }

    private companion object { const val URL = "https://updates.example.com/threat-intel-manifest.json" }
}
