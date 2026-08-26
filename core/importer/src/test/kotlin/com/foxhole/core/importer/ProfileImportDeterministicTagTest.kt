package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

internal class ProfileImportDeterministicTagTest : ProfileImportParserTestSupport() {
    private val support = ProfileImportCoreSupport(json)

    @Test
    fun `tagFor is a pure function of node identity`() {
        val first = support.tagFor("My Node", "vless", "a.example.com", 443, "uuid-1")
        val second = support.tagFor("My Node", "vless", "a.example.com", 443, "uuid-1")
        assertEquals(first, second)
    }

    @Test
    fun `tagFor distinguishes nodes by every identity component`() {
        val base = support.tagFor("My Node", "vless", "a.example.com", 443, "uuid-1")
        assertNotEquals(base, support.tagFor("My Node", "trojan", "a.example.com", 443, "uuid-1"))
        assertNotEquals(base, support.tagFor("My Node", "vless", "b.example.com", 443, "uuid-1"))
        assertNotEquals(base, support.tagFor("My Node", "vless", "a.example.com", 8443, "uuid-1"))
        assertNotEquals(base, support.tagFor("My Node", "vless", "a.example.com", 443, "uuid-2"))
        assertNotEquals(base, support.tagFor("Other Node", "vless", "a.example.com", 443, "uuid-1"))
    }

    @Test
    fun `blank display name falls back to the node prefix`() {
        val tag = support.tagFor("  ", "vless", "a.example.com", 443, "uuid-1")
        assertEquals("node", tag.substringBeforeLast('-'))
    }

    @Test
    fun `duplicate tags disambiguate deterministically preserving order`() {
        fun node(tag: String) =
            ProfileImportCoreSupport.ProxyNode(
                displayName = "dup",
                protocolHint = ProtocolHint.VLESS,
                outbound =
                buildJsonObject {
                    put("type", "vless")
                    put("tag", tag)
                    put("server", "a.example.com")
                },
            )

        val deduped = support.deduplicateNodeTags(listOf(node("dup-1234"), node("dup-1234"), node("dup-1234")))

        assertEquals(listOf("dup-1234", "dup-1234-2", "dup-1234-3"), deduped.map { it.tag })

        assertEquals(
            listOf("dup-1234", "dup-1234-2", "dup-1234-3"),
            deduped.map { it.outbound?.get("tag")?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `an unchanged subscription parses to byte-identical profiles`() {
        val payload =
            """
            vless://11111111-1111-1111-1111-111111111111@direct.example.com:8443?encryption=none&security=none&type=tcp#Alpha
            trojan://secret-direct@trojan-direct.example.com:8444?security=tls&type=tcp#Bravo
            hysteria2://secret-direct@hy2-direct.example.com:8447/#Charlie
            """.trimIndent()

        fun parseOnce() =
            parser.parseSubscriptionProfiles(
                rawContent = payload,
                fallbackName = "subscription",
                groupCompatibleSingleServerMultiProtocol = true,
            )

        val first = parseOnce()
        val second = parseOnce()

        assertEquals(
            first.profiles.map(com.foxhole.core.model.ParsedSubscriptionProfile::normalizedConfigJson),
            second.profiles.map(com.foxhole.core.model.ParsedSubscriptionProfile::normalizedConfigJson),
        )
        assertEquals(
            first.profiles.flatMap { profile -> profile.protocolOptions.map { option -> option.normalizedConfigJson } },
            second.profiles.flatMap { profile -> profile.protocolOptions.map { option -> option.normalizedConfigJson } },
        )
    }
}
