package com.foxhole.core.runtime

import com.foxhole.core.model.TorBridgeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgeTorrcLinesTest {
    private val snowflakeIdentity = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"
    private val bundledSnowflakeBridge =
        "snowflake 192.0.2.3:80 $snowflakeIdentity " +
            "fingerprint=$snowflakeIdentity url=https://x"
    private val downloadedSnowflakeBridge =
        "snowflake 10.0.0.1:443 0123456789ABCDEF0123456789ABCDEF01234567 " +
            "fingerprint=z url=https://x"
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
            "obfs4": ["obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=abc iat-mode=0"],
            "snowflake": ["$bundledSnowflakeBridge"]
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
    fun `legacy auto resolves to snowflake only`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertEquals("UseBridges 1", lines.first())
        val bridges = lines.drop(1)
        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.all { it.startsWith("Bridge snowflake ") })
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
    fun `transport missing from both sources fails closed`() {
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.WEBTUNNEL),
                transportLines = transportLines,
                downloadedGroupsJson = null,
                bundledPtConfigJson = bundled,
            )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `lines whose transport plugin is absent are dropped`() {
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
    fun `downloaded selected group takes precedence over the bundle`() {
        val downloaded =
            """{"snowflake":["$downloadedSnowflakeBridge"]}"""
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.AUTO),
                transportLines = transportLines,
                downloadedGroupsJson = downloaded,
                bundledPtConfigJson = bundled,
            )
        assertEquals(
            listOf(
                "UseBridges 1",
                "Bridge $downloadedSnowflakeBridge",
            ),
            lines,
        )
    }

    @Test
    fun `downloaded source missing selected transport falls back to the same bundled transport`() {
        val downloaded =
            """{"snowflake":["$downloadedSnowflakeBridge"]}"""
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.OBFS4),
                transportLines = transportLines,
                downloadedGroupsJson = downloaded,
                bundledPtConfigJson = bundled,
            )

        assertEquals("UseBridges 1", lines.first())
        assertTrue(lines.drop(1).isNotEmpty())
        assertTrue(lines.drop(1).all { it.startsWith("Bridge obfs4 ") })
    }

    @Test
    fun `invalid downloaded selected group falls back to the same bundled transport`() {
        val downloaded =
            """{"snowflake":["snowflake 10.0.0.1:443 identity-missing fingerprint=z url=https://x"]}"""
        val lines =
            buildBridgeTorrcLines(
                policy = TorBridgePolicy(transport = TorBridgeTransport.SNOWFLAKE),
                transportLines = transportLines,
                downloadedGroupsJson = downloaded,
                bundledPtConfigJson = bundled,
            )

        assertEquals("UseBridges 1", lines.first())
        assertTrue(lines.drop(1).isNotEmpty())
        assertTrue(lines.drop(1).all { it.startsWith("Bridge snowflake 192.0.2.3:80 ") })
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
        assertTrue(lines.drop(1).isNotEmpty())
        assertTrue(lines.drop(1).all { it.startsWith("Bridge snowflake ") })
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
