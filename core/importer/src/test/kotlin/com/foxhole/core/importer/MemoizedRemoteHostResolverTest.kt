package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

// Phase 4b: a refresh must resolve each unique public host once, not once per node per parse.
internal class MemoizedRemoteHostResolverTest {
    private val publicAddress = InetAddress.getByName("93.184.216.34")
    private val privateAddress = InetAddress.getByName("10.10.0.5")

    @Test
    fun `successful public resolution is served from cache within ttl`() {
        var calls = 0
        var clock = 0L
        val resolver =
            memoizedPublicHostResolver(
                delegate = { _ ->
                    calls += 1
                    listOf(publicAddress)
                },
                ttlMs = 1_000L,
                now = { clock },
            )

        repeat(5) { resolver("node.example.org") }

        assertEquals(1, calls)

        clock = 2_000L
        resolver("node.example.org")
        assertEquals(2, calls)
    }

    @Test
    fun `failures and private results are never cached`() {
        var failingCalls = 0
        val failing =
            memoizedPublicHostResolver(
                delegate = { _ ->
                    failingCalls += 1
                    throw UnknownHostException("nope")
                },
                now = { 0L },
            )
        repeat(2) { runCatching { failing("dead.example.org") } }
        assertEquals(2, failingCalls)

        var privateCalls = 0
        val privateResolver =
            memoizedPublicHostResolver(
                delegate = { _ ->
                    privateCalls += 1
                    listOf(privateAddress)
                },
                now = { 0L },
            )
        repeat(2) { privateResolver("lan.example.org") }
        assertEquals(2, privateCalls)
    }

    @Test
    fun `cache size stays bounded`() {
        var calls = 0
        val resolver =
            memoizedPublicHostResolver(
                delegate = { _ ->
                    calls += 1
                    listOf(publicAddress)
                },
                maxEntries = 2,
                now = { 0L },
            )

        resolver("a.example.org")
        resolver("b.example.org")
        resolver("c.example.org")
        // "a" was evicted by "c"; re-resolving it must hit the delegate again.
        resolver("a.example.org")

        assertEquals(4, calls)
    }

    @Test
    fun `multi node subscription parse resolves each unique host once`() {
        var calls = 0
        val parser =
            ProfileImportParser(
                Json {
                    prettyPrint = true
                    explicitNulls = false
                    ignoreUnknownKeys = true
                },
                remoteHostResolver = { _ ->
                    calls += 1
                    listOf(publicAddress)
                },
            )
        val payload =
            """
            vless://11111111-1111-1111-1111-111111111111@shared.example.org:8443?encryption=none&security=none&type=tcp#Alpha
            trojan://secret@shared.example.org:8444?security=tls&type=tcp#Bravo
            hysteria2://secret@shared.example.org:8447/#Charlie
            """.trimIndent()

        parser.parseSubscriptionProfiles(
            rawContent = payload,
            fallbackName = "subscription",
            groupCompatibleSingleServerMultiProtocol = true,
        )

        assertTrue("expected a single resolve for the shared host, got $calls", calls == 1)
    }
}
