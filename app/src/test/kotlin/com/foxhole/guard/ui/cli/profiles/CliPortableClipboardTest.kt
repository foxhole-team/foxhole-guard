package com.foxhole.guard.ui.cli.profiles

import com.foxhole.core.importer.PortableProfileExportFailure
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CliPortableClipboardTest {
    private val json = Json { prettyPrint = true }
    private val parser = ProfileImportParser(json, testRemoteHostResolver())
    private val vless = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Owned%20compound"
    private val trojan = "trojan://owned%2Bpassword@8.8.8.8:443?security=tls&sni=example.com#Owned%20compound"
    private val shadowsocks = "ss://YWVzLTI1Ni1nY206Zml4dHVyZQ@9.9.9.9:8388#Owned%20second"

    @Test
    fun `a default Guard resolved config exports and reimports without changing its policy`() {
        val resolved = resolved(vless)

        val copied = cliPortableClipboardText(listOf(export(1L, "Owned compound", listOf(resolved))))
        val imported = parser.parseUserInput(copied)

        assertTrue(copied.startsWith("vless://"))
        assertEquals("Owned compound", imported.displayName)
        assertEquals(
            json.parseToJsonElement(resolved),
            json.parseToJsonElement(requireNotNull(imported.normalizedConfigJson)),
        )
    }

    @Test
    fun `multiple parents keep every selected protocol and profile title in portable clipboard text`() {
        val originals = listOf(resolved(vless), resolved(trojan), resolved(shadowsocks))
        val copied = cliPortableClipboardText(
            listOf(
                export(1L, "Owned compound", originals.take(2)),
                export(2L, "Owned second", originals.takeLast(1)),
            ),
        )

        assertEquals(3, copied.lines().size)
        copied.lines().zip(originals).forEach { (line, original) ->
            val imported = parser.parseUserInput(line)
            assertEquals(
                json.parseToJsonElement(original),
                json.parseToJsonElement(requireNotNull(imported.normalizedConfigJson)),
            )
        }
        val combined = parser.parseUserInput(copied)
        assertEquals(3, combined.nodesCount)
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.TROJAN, ProtocolHint.SHADOWSOCKS),
            combined.protocolOptions.map { it.protocolHint },
        )
        assertEquals("Owned second", parser.parseUserInput(copied.lines().last()).displayName)
    }

    @Test
    fun `custom policy refuses the complete selection instead of silently dropping it`() {
        val original = resolved(vless)
        val customDns = original.replace("prefer_ipv4", "prefer_ipv6")
        assertTrue(customDns != original)

        assertThrows(PortableProfileExportFailure::class.java) {
            cliPortableClipboardText(
                listOf(export(1L, "Owned compound", listOf(original)), export(2L, "Custom policy", listOf(customDns))),
            )
        }
    }

    @Test
    fun `the clipboard bounds the total protocol selection across parent profiles`() {
        val config = resolved(vless)
        assertThrows(PortableProfileExportFailure::class.java) {
            cliPortableClipboardText(
                listOf(export(1L, "First", List(128) { config }), export(2L, "Second", List(129) { config })),
            )
        }
    }

    @Test
    fun `separately valid parent documents cannot exceed the aggregate clipboard input budget`() {
        val config = " ".repeat(2 * 1024 * 1024) + resolved(vless)
        assertThrows(PortableProfileExportFailure::class.java) {
            cliPortableClipboardText(
                listOf(export(1L, "Owned compound", listOf(config)), export(2L, "Owned compound", listOf(config))),
            )
        }
    }

    private fun resolved(input: String): String = parser.sanitizeResolvedConfig(
        requireNotNull(parser.parseUserInput(input).normalizedConfigJson),
    )

    private fun export(id: Long, name: String, configs: List<String>) =
        CliProfileConfigExport(id, name, "owned-profile", configs, configs.size > 1)
}
