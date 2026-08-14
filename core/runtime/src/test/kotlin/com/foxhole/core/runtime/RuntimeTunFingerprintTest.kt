package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTunFingerprintTest {
    private fun config(
        mtu: Int = 1500,
        includePackage: String? = null,
        routeRule: String = """{"package_name":["com.app.a"],"outbound":"block"}""",
    ): String {
        val include = includePackage?.let { """"include_package":["$it"],""" }.orEmpty()
        return """
            {
              "inbounds":[
                {"type":"direct","tag":"dns-in"},
                {"type":"tun","tag":"tun-in",$include"mtu":$mtu,"auto_route":true,"inet4_address":"172.19.0.1/28"}
              ],
              "route":{"rules":[$routeRule]}
            }
        """.trimIndent()
    }

    private fun fingerprint(
        configJson: String?,
        dns: List<String> = listOf("1.1.1.1"),
        network: Long? = 7L,
        metered: Boolean = false,
        packageUidResolver: (String) -> Int? = { null },
    ) = RuntimeTunFingerprint.of(configJson, dns, network, metered, packageUidResolver)

    @Test
    fun `identical configs produce identical fingerprints`() {
        assertEquals(fingerprint(config()), fingerprint(config()))
    }

    @Test
    fun `route rule changes do not change the fingerprint`() {
        // The whole point of the fast path: per-app routing rules move connections inside the
        // box, not the kernel interface — their churn must keep the tun fd reusable.
        val before =
            fingerprint(
                config(routeRule = """{"package_name":["com.app.a"],"outbound":"block"}"""),
            )
        val after =
            fingerprint(
                config(routeRule = """{"package_name":["com.app.b","com.app.c"],"outbound":"tor-out"}"""),
            )
        assertEquals(before, after)
    }

    @Test
    fun `tun inbound changes change the fingerprint`() {
        assertNotEquals(fingerprint(config(mtu = 1500)), fingerprint(config(mtu = 1400)))
        assertNotEquals(fingerprint(config()), fingerprint(config(includePackage = "com.app.a")))
    }

    @Test
    fun `dns network and metering changes change the fingerprint`() {
        val base = fingerprint(config())
        assertNotEquals(base, fingerprint(config(), dns = listOf("9.9.9.9")))
        assertNotEquals(base, fingerprint(config(), network = 8L))
        assertNotEquals(base, fingerprint(config(), metered = true))
    }

    @Test
    fun `kernel filter package uid change changes the fingerprint`() {
        // Uninstall+reinstall keeps the package NAME in include_package but hands it a new UID;
        // a reused fd would keep filtering the dead UID while the reinstalled app bypasses the tun.
        val beforeReinstall =
            fingerprint(config(includePackage = "com.app.a"), packageUidResolver = { 10_001 })
        val afterReinstall =
            fingerprint(config(includePackage = "com.app.a"), packageUidResolver = { 10_777 })
        val uninstalled =
            fingerprint(config(includePackage = "com.app.a"), packageUidResolver = { null })

        assertNotEquals(beforeReinstall, afterReinstall)
        assertNotEquals(beforeReinstall, uninstalled)
        assertEquals(
            fingerprint(config(includePackage = "com.app.a"), packageUidResolver = { 10_001 }),
            beforeReinstall,
        )
    }

    @Test
    fun `unprovable configs yield null - callers must fall back to establish`() {
        assertNull(fingerprint(null))
        assertNull(fingerprint("not json"))
        assertNull(fingerprint("""{"inbounds":[{"type":"direct"}]}"""))
    }
}
