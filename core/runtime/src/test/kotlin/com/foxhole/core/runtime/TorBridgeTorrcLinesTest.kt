package com.foxhole.core.runtime

import com.foxhole.core.model.TorBridgeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgeTorrcLinesTest {
    private val transportLines =
        listOf(
            "ClientTransportPlugin meek_lite,obfs2,obfs3,obfs4,scramblesuit,webtunnel exec /lib/liblyrebird.so",
            "ClientTransportPlugin snowflake exec /lib/liblyrebird.so",
            "ClientTransportPlugin conjure exec /lib/libconjure_client.so",
        )

    private val bundled =
        """
        {
          "recommendedDefault": "obfs4",
          "bridges": {
            "meek": ["meek_lite 192.0.2.20:80 url=https://cdn front=x utls=HelloRandomizedALPN"],
            "obfs4": ["obfs4 37.218.245.14:38224 D9A82D2F cert=abc iat-mode=0"],
            "snowflake": ["snowflake 192.0.2.3:80 2B280B23 fingerprint=2B280B23 url=https://x"]
          }
        }
        """.trimIndent()

    @Test
    fun `disabled policy emits no bridge lines`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(enabled = false),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `auto orders the recommended group first and enables bridges`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertEquals("UseBridges 1", lines.first())
        val bridges = lines.drop(1)
        assertTrue(bridges.first().startsWith("Bridge obfs4 "))
        assertTrue(bridges.any { it.startsWith("Bridge meek_lite ") })
        assertTrue(bridges.any { it.startsWith("Bridge snowflake ") })
    }

    @Test
    fun `specific transport narrows to that group only`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.SNOWFLAKE),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        val bridges = lines.drop(1)
        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.all { it.startsWith("Bridge snowflake ") })
    }

    @Test
    fun `transport with no ready bridges falls back to the auto set`() {
        // No webtunnel bridge line exists in the source, so WEBTUNNEL must not leave Tor bridge-less.
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.WEBTUNNEL),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertEquals("UseBridges 1", lines.first())
        assertTrue(lines.drop(1).any { it.startsWith("Bridge obfs4 ") })
    }

    @Test
    fun `lines whose transport plugin is absent are dropped`() {
        // Only snowflake plugin present: obfs4/meek lines must be filtered out.
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = listOf("ClientTransportPlugin snowflake exec /lib/liblyrebird.so"),
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertTrue(lines.drop(1).all { it.startsWith("Bridge snowflake ") })
    }

    @Test
    fun `downloaded bare-groups payload takes precedence over the bundle`() {
        val downloaded =
            """{"obfs4":["obfs4 10.0.0.1:443 AAAA cert=z iat-mode=0"]}"""
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = transportLines,
                downloadedGroupsJson = downloaded,
                bundledPtConfigJson = bundled,
            )
        assertEquals(listOf("UseBridges 1", "Bridge obfs4 10.0.0.1:443 AAAA cert=z iat-mode=0"), lines)
    }

    @Test
    fun `malformed downloaded payload falls back to the bundle`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = transportLines,
                downloadedGroupsJson = "not json {",
                bundledPtConfigJson = bundled,
            )
        assertTrue(lines.drop(1).any { it.startsWith("Bridge obfs4 ") })
    }

    @Test
    fun `no source at all emits nothing`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = null,
            )
        assertFalse(lines.contains("UseBridges 1"))
        assertTrue(lines.isEmpty())
    }
}
