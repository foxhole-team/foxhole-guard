package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

/** Escaped standard SIP003 option keys, with synthetic credentials only. */
internal class EscapedSip003KeyInteropTest {
    private val parser = ProfileImportParser(Json)

    @Test
    fun `escaped TLS key is not a TLS presence flag`() {
        refused("v2ray-plugin;mux=0;tls\\=false")
    }

    @Test
    fun `escaped mode key cannot select websocket mode`() {
        refused("v2ray-plugin;mux=0;mode\\=websocket")
    }

    @Test
    fun `escaped host key cannot become a host assignment`() {
        refused("v2ray-plugin;mux=0;host\\=a=b")
    }

    private fun refused(plugin: String) {
        val credentials =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("aes-256-gcm:synthetic-demo-password".toByteArray(Charsets.UTF_8))
        val options = URLEncoder.encode(plugin, "UTF-8").replace("+", "%20")
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parseUserInput("ss://$credentials@1.1.1.1:443/?plugin=$options")
        }
        assertFalse(error.message.orEmpty().contains("synthetic-demo-password"))
        assertFalse(error.message.orEmpty().contains(plugin))
    }
}
