package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSip008ImportTest {
    private val parser = ProfileImportParser(Json { ignoreUnknownKeys = false })
    private val node = """{"remarks":"Reference","server":"1.1.1.1","server_port":8388,"method":"aes-256-gcm","password":"synthetic"}"""

    @Test
    fun `direct JSON paste imports SIP008 without canonical interception`() {
        val result = parser.parseUserInput("""{"version":1,"servers":[$node]}""")
        assertEquals(ProtocolHint.SHADOWSOCKS, result.protocolHint)
        assertEquals("Reference", result.displayName)
        assertEquals(1, result.nodesCount)
        assertTrue(result.normalizedConfigJson.orEmpty().contains("aes-256-gcm"))
    }

    @Test
    fun `a URL inside structured input never becomes a subscription source`() {
        var lookups = 0
        val checkedParser = ProfileImportParser(Json { ignoreUnknownKeys = false }) {
            lookups += 1
            listOf(java.net.InetAddress.getByName("1.1.1.1"))
        }
        val input = """{"version":1,"servers":[$node],"support":"https://example.com/private-source"}"""
        assertThrows(IllegalArgumentException::class.java) { checkedParser.parseUserInput(input) }
        assertEquals(0, lookups)
    }

    @Test
    fun `multiple SIP008 servers remain independently selectable`() {
        val second = node.replace("Reference", "Second").replace("1.1.1.1", "8.8.8.8")
        val result = parser.parseUserInput("""{"version":1,"servers":[$node,$second]}""")
        assertEquals(2, result.nodesCount)
        assertEquals(2, result.protocolOptions.size)
        assertEquals(result.protocolOptions.first().id, result.selectedProtocolOptionId)
    }

    @Test
    fun `SIP008 cannot grant private host admission or conceal plugins and routing`() {
        for (input in listOf(
            """{"version":1,"servers":[${node.replace("1.1.1.1", "127.0.0.1")}]}""",
            """{"version":1,"servers":[$node],"outbounds":[]}""",
            """{"version":1,"servers":[${node.dropLast(1)},"plugin_opts":"tls;host=example.com"}]}""",
            """{"version":2,"servers":[$node]}""",
            """{"version":1,"servers":[]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(input) }
        }
    }
}
