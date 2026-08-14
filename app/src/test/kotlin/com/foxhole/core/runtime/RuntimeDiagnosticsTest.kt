package com.foxhole.core.runtime

import com.foxhole.guard.runtime.redactedRuntimeDnsShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test
    fun `redacted dns shape records resolver topology without endpoints`() {
        val shape =
            redactedRuntimeDnsShape(
                """
                {
                  "dns": {
                    "servers": [
                      { "tag": "dns-local", "type": "local" },
                      { "tag": "dns-direct", "type": "local" },
                      { "tag": "dns-remote", "type": "https", "server": "cloudflare-dns.com", "server_port": 443, "path": "/dns-query", "detour": "proxy" },
                      { "tag": "profile-edge", "address": "https://dns.example.com/query", "detour": "profile-proxy" }
                    ]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            "dns-local:platform:default:no_detour|dns-direct:platform:default:no_detour|dns-remote:https:443:proxy|custom:custom:default:custom_detour",
            shape,
        )
        assertFalse(shape.contains("1.1.1.1"))
        assertFalse(shape.contains("cloudflare-dns.com"))
        assertFalse(shape.contains("dns.example.com"))
        assertFalse(shape.contains("profile-edge"))
        assertFalse(shape.contains("profile-proxy"))
    }
}
